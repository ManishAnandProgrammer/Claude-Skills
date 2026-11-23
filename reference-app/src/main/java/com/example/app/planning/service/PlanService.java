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
     * The todo is locked, because no constraint can hold "only for an open todo" (it reads another module's
     * table): its completion and delete wait for this create. The overlap is left to
     * {@code ex_plans_owner_id_dates}, which alone sees concurrent writes; the lock queues the creates of one
     * todo only, so overlapping creates for two of the owner's todos can deadlock in that constraint (503
     * {@code DATABASE_CONTENTION}, whose retry answers 409).
     */
    @Transactional
    public ObjectWrapper<PlanResponse> create(PlanCreateRequest request) {
        UUID ownerId = currentUser.id();
        TodoView todo = todos.getOwnedTodoViewForUpdate(request.todoId(), ownerId);
        policy.create(todo).orThrow();
        Plan saved = repository.saveAndFlush(mapper.toEntity(request, ownerId));
        return ObjectWrapper.withObject(toResponse(saved, todo));
    }

    public CursorWrapper<SelectableDropdownItem> todoOptions(PlanTodoOptionsFilter filter) {
        CursorBatch<TodoView> batch = todos.getOwnedTodoViewBatch(filter.getTodoListId(), filter.getQuery(),
                filter.getAfter(), paging.cursorSize(filter), currentUser.id());
        return CursorWrapper.of(batch.items().stream().map(this::toTodoOption).toList(), batch.nextCursor());
    }

    public ObjectWrapper<PlanAvailabilityResponse> availability(PlanAvailabilityFilter filter) {
        List<PlanDays> taken = repository.findLiveDays(currentUser.id(), filter.getFrom(), filter.getTo());
        AllowedAction free = allowedActionMapper.toAllowedAction("plan", policy.planDays(false));
        AllowedAction busy = allowedActionMapper.toAllowedAction("plan", policy.planDays(true));
        return ObjectWrapper.withObject(mapper.toAvailability(filter.getFrom(), filter.getTo(), taken, free, busy));
    }

    public CursorWrapper<PlanResponse> findByFilter(PlanFilter filter) {
        UUID ownerId = currentUser.id();
        CursorBatch<PlanRow> batch = repository.findByFilter(filter, ownerId, paging.cursorSize(filter));
        return CursorWrapper.of(withTodos(batch.items(), ownerId), batch.nextCursor());
    }

    /**
     * A todo missing at the second read was deleted after the plan was read (its delete is refused while it
     * has plans), so the plan is gone too: {@code PLAN_NOT_FOUND}.
     */
    public ObjectWrapper<PlanResponse> findById(UUID id) {
        UUID ownerId = currentUser.id();
        Plan plan = getOwnedPlan(id, ownerId);
        TodoView todo = todos.findOwnedTodoView(plan.getTodoId(), ownerId)
                .orElseThrow(AppException.supply(ErrorCode.PLAN_NOT_FOUND, id));
        return ObjectWrapper.withObject(toResponse(plan, todo));
    }

    /**
     * Locks in the create's order, the todo before the plan's row (taken at the flush), so the todo's completion
     * waits for this reschedule.
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

    /** A plan whose todo the lookup misses was deleted with it between the two statements, so it is left out. */
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
