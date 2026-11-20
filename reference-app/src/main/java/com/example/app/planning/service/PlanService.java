package com.example.app.planning.service;

import com.example.app.common.dropdown.SelectableDropdownItem;
import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.pagination.Paging;
import com.example.app.common.security.AllowedAction;
import com.example.app.common.security.AllowedActionMapper;
import com.example.app.common.security.CurrentUser;
import com.example.app.common.wrapper.CursorWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.planning.domain.Plan;
import com.example.app.planning.domain.PlanStatus;
import com.example.app.planning.dto.internal.PlanDays;
import com.example.app.planning.dto.internal.PlanRow;
import com.example.app.planning.dto.request.PlanAvailabilityFilter;
import com.example.app.planning.dto.request.PlanCreateRequest;
import com.example.app.planning.dto.request.PlanFilter;
import com.example.app.planning.dto.request.PlanStatusRequest;
import com.example.app.planning.dto.request.PlanTodoOptionsFilter;
import com.example.app.planning.dto.request.PlanUpdateRequest;
import com.example.app.planning.dto.response.PlanAllowedActionsResponse;
import com.example.app.planning.dto.response.PlanAvailabilityResponse;
import com.example.app.planning.dto.response.PlanResponse;
import com.example.app.planning.mapper.PlanMapper;
import com.example.app.planning.repository.PlanRepository;
import com.example.app.todo.api.TodoView;
import com.example.app.todo.api.Todos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Every read and write is scoped to the caller: another user's plan, or todo, answers 404 like a missing one. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlanService {

    private final PlanRepository repository;
    private final PlanMapper mapper;
    private final PlanPolicy policy;
    private final AllowedActionMapper allowedActionMapper;
    private final Todos todos;
    private final CurrentUser currentUser;
    private final Paging paging;

    /**
     * A plan is only for an open todo, a rule no constraint can hold (it reads another module's table):
     * so the todo is read through its facade <b>locked</b>, and the todo's completion and delete wait for
     * this create. A completion that commits first makes it 409 {@code PLAN_TODO_DONE}; a delete that
     * waited finds the plan and answers 409 {@code TODO_IN_USE}, so the insert never meets a deleted todo
     * ({@code TODO_GONE} can't happen here). A plan sharing a day with another of the owner's is refused at
     * the flush by {@code ex_plans_owner_id_dates}: 409 {@code PLAN_OVERLAPS}. The lock queues the creates
     * of one todo, not of one owner: overlapping creates for two of the owner's todos at the same instant
     * can still deadlock in that constraint, and the victim answers 503 {@code DATABASE_CONTENTION}. That
     * is rare, the data stays right (one plan), and the retry it asks for answers 409.
     */
    @Transactional
    public ObjectWrapper<PlanResponse> create(PlanCreateRequest request) {
        UUID ownerId = currentUser.id();
        TodoView todo = todos.getOwnedTodoViewForUpdate(request.todoId(), ownerId);
        policy.create(todo).orThrow();
        Plan saved = repository.saveAndFlush(mapper.toEntity(request, ownerId));
        return ObjectWrapper.withObject(toResponse(saved, todo));
    }

    /**
     * The todos of one of the caller's lists a new plan may be for, each flagged by the create's own rule
     * ({@link PlanPolicy#create}), so a screen greys out a done todo with the reason the create would answer. One
     * statement: the todo module's batch, whose views carry {@code open}. The create still decides, on the locked
     * todo; a list that is missing or someone else's offers nothing, as the plain todo dropdown does.
     */
    public CursorWrapper<SelectableDropdownItem> todoOptions(PlanTodoOptionsFilter filter) {
        CursorBatch<TodoView> batch = todos.getOwnedTodoViewBatch(filter.getTodoListId(), filter.getQuery(),
                filter.getAfter(), paging.cursorSize(filter), currentUser.id());
        return CursorWrapper.of(batch.items().stream().map(this::toTodoOption).toList(), batch.nextCursor());
    }

    /**
     * The caller's days from {@code from} to {@code to}, both inclusive, as free and taken spans: taken by a plan
     * that isn't cancelled (the days {@code ex_plans_owner_id_dates} holds), each refused for a new plan by the
     * reason the create would answer. One statement over the starts_on index, bounded as the list's plannedFrom
     * filter is (a plan spans at most {@code plan.max-days}); the range itself at most
     * {@code plan.max-availability-days}. The create still decides, in the constraint.
     */
    public ObjectWrapper<PlanAvailabilityResponse> availability(PlanAvailabilityFilter filter) {
        List<PlanDays> taken = repository.findLiveDays(currentUser.id(), filter.getFrom(), filter.getTo());
        AllowedAction free = allowedActionMapper.toAllowedAction("plan", policy.planDays(false));
        AllowedAction busy = allowedActionMapper.toAllowedAction("plan", policy.planDays(true));
        return ObjectWrapper.withObject(mapper.toAvailability(filter.getFrom(), filter.getTo(), taken, free, busy));
    }

    /**
     * One statement for the batch, one for its todos through the todo module's facade (a join into
     * another module's table is ARCH-04's to refuse), whatever the batch size. The cursor is read before
     * either, so a tampered one is a 400 even when no plan would match.
     */
    public CursorWrapper<PlanResponse> findByFilter(PlanFilter filter) {
        UUID ownerId = currentUser.id();
        CursorBatch<PlanRow> batch = repository.findByFilter(filter, ownerId, paging.cursorSize(filter));
        return CursorWrapper.of(withTodos(batch.items(), ownerId), batch.nextCursor());
    }

    /**
     * Two reads, the plan and then its todo, each seeing the database at its own moment. The todo's delete
     * is refused while it has plans, so a todo missing at the second read went after its plan: the plan
     * is gone too, and answers {@code PLAN_NOT_FOUND}, as it would a moment later.
     */
    public ObjectWrapper<PlanResponse> findById(UUID id) {
        UUID ownerId = currentUser.id();
        Plan plan = getOwnedPlan(id, ownerId);
        TodoView todo = todos.findOwnedTodoView(plan.getTodoId(), ownerId)
                .orElseThrow(AppException.supply(ErrorCode.PLAN_NOT_FOUND, id));
        return ObjectWrapper.withObject(toResponse(plan, todo));
    }

    /**
     * New days for a plan, the version checked first, then the policy's {@code reschedule}, the rule the plan's
     * allowed actions show: its own status first (a final plan is frozen, whatever its todo's state), then its
     * todo's. A plan's days move only while its todo is open, the create's rule, read the create's way: the todo
     * locked through its facade before the policy decides, so the todo's completion waits for this reschedule,
     * and one that commits first makes it 409 {@code PLAN_TODO_DONE}. Locks in the create's order, the todo before the
     * plan's row (the update's). The plan's row lock queues reschedules of one plan; reschedules of two of the
     * owner's plans onto the same days meet in {@code ex_plans_owner_id_dates}: one wins, the other is 409
     * {@code PLAN_OVERLAPS}, or 503 {@code DATABASE_CONTENTION} when the constraint's check deadlocks (the
     * retry then answers 409). The todo can't be deleted while it has this plan, so the locked view is the
     * response's.
     */
    @Transactional
    public ObjectWrapper<PlanResponse> update(UUID id, PlanUpdateRequest request) {
        UUID ownerId = currentUser.id();
        Plan plan = getOwnedPlan(id, ownerId);
        plan.checkVersion(request.version());
        TodoView todo = todos.getOwnedTodoViewForUpdate(plan.getTodoId(), ownerId);
        policy.reschedule(plan.getId(), plan.getStatus(), todo).orThrow();
        plan.reschedule(request.startsOn(), request.endsOn());
        repository.flush();
        return ObjectWrapper.withObject(toResponse(plan, todo));
    }

    /** A status change (PlanStatus's moves), the version checked first; the todo read as on the update. */
    @Transactional
    public ObjectWrapper<PlanResponse> changeStatus(UUID id, PlanStatusRequest request) {
        UUID ownerId = currentUser.id();
        Plan plan = getOwnedPlan(id, ownerId);
        plan.checkVersion(request.version());
        policy.changeStatus(plan.getStatus(), request.status()).orThrow();
        plan.changeStatus(request.status(), request.doneOn());
        repository.flush();
        TodoView todo = todos.findOwnedTodoView(plan.getTodoId(), ownerId)
                .orElseThrow(AppException.supply(ErrorCode.PLAN_NOT_FOUND, id));
        return ObjectWrapper.withObject(toResponse(plan, todo));
    }

    @Transactional
    public void delete(UUID id) {
        repository.delete(getOwnedPlan(id, currentUser.id()));
        repository.flush();
    }

    /**
     * The batch's todos in one lookup. The two statements see the database at two moments: a plan whose
     * todo the lookup misses was deleted with it in between (as {@link #findById} says), so it is left out
     * of the batch, as a read a moment later would leave it out. The cursor still continues after the last
     * row the batch read.
     */
    private List<PlanResponse> withTodos(List<PlanRow> rows, UUID ownerId) {
        Set<UUID> todoIds = rows.stream().map(PlanRow::todoId).collect(Collectors.toSet());
        Map<UUID, TodoView> todosById = todos.getOwnedTodoViews(todoIds, ownerId).stream()
                .collect(Collectors.toMap(TodoView::id, Function.identity()));
        List<PlanResponse> responses = new ArrayList<>();
        for (PlanRow row : rows) {
            TodoView todo = todosById.get(row.todoId());
            if (Objects.nonNull(todo)) {
                responses.add(mapper.toResponse(row, todo, allowedActions(row.id(), row.status(), todo)));
            }
        }
        return responses;
    }

    private SelectableDropdownItem toTodoOption(TodoView todo) {
        return new SelectableDropdownItem(todo.id(), todo.title(),
                allowedActionMapper.toAllowedAction("create", policy.create(todo)));
    }

    private PlanResponse toResponse(Plan plan, TodoView todo) {
        return mapper.toResponse(plan, todo, allowedActions(plan.getId(), plan.getStatus(), todo));
    }

    /** From the fields the row already has and its todo's view: no statement of its own (DATA-26). */
    private PlanAllowedActionsResponse allowedActions(UUID planId, PlanStatus status, TodoView todo) {
        return new PlanAllowedActionsResponse(
                allowedActionMapper.toAllowedAction("reschedule", policy.reschedule(planId, status, todo)),
                allowedActionMapper.toAllowedAction("markDone", policy.changeStatus(status, PlanStatus.DONE)),
                allowedActionMapper.toAllowedAction("cancel", policy.changeStatus(status, PlanStatus.CANCELLED)));
    }

    private Plan getOwnedPlan(UUID id, UUID ownerId) {
        return repository.findByIdAndOwnerId(id, ownerId).orElseThrow(AppException.supply(ErrorCode.PLAN_NOT_FOUND, id));
    }
}
