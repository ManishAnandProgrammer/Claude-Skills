package com.example.app.planning.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.pagination.Paging;
import com.example.app.common.security.CurrentUser;
import com.example.app.common.wrapper.CursorWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.planning.domain.Plan;
import com.example.app.planning.dto.internal.PlanRow;
import com.example.app.planning.dto.request.PlanCreateRequest;
import com.example.app.planning.dto.request.PlanFilter;
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
        if (!todo.open()) {
            throw AppException.of(ErrorCode.PLAN_TODO_DONE, todo.id());
        }
        Plan saved = repository.saveAndFlush(mapper.toEntity(request, ownerId));
        return ObjectWrapper.withObject(mapper.toResponse(saved, todo));
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
        return ObjectWrapper.withObject(mapper.toResponse(plan, todo));
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
                responses.add(mapper.toResponse(row, todo));
            }
        }
        return responses;
    }

    private Plan getOwnedPlan(UUID id, UUID ownerId) {
        return repository.findByIdAndOwnerId(id, ownerId).orElseThrow(AppException.supply(ErrorCode.PLAN_NOT_FOUND, id));
    }
}
