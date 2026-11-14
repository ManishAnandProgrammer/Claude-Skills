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
     * The owner's plan writes queue first ({@code lockPlansOfOwner}), then the todo, the todo module's,
     * is validated and locked through its facade, so the plan can't race the todo's delete. A plan
     * sharing a day with another of the owner's is refused at the flush by {@code ex_plans_owner_id_dates},
     * also when two creates race: 409 {@code PLAN_OVERLAPS}.
     */
    @Transactional
    public ObjectWrapper<PlanResponse> create(PlanCreateRequest request) {
        UUID ownerId = currentUser.id();
        repository.lockPlansOfOwner(ownerId);
        TodoView todo = todos.getOwnedTodoViewForWrite(request.todoId(), ownerId);
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

    /** The todo can't be gone: its delete is refused while it has plans. */
    public ObjectWrapper<PlanResponse> findById(UUID id) {
        UUID ownerId = currentUser.id();
        Plan plan = getOwnedPlan(id, ownerId);
        return ObjectWrapper.withObject(mapper.toResponse(plan, todos.getOwnedTodoView(plan.getTodoId(), ownerId)));
    }

    @Transactional
    public void delete(UUID id) {
        repository.delete(getOwnedPlan(id, currentUser.id()));
        repository.flush();
    }

    private List<PlanResponse> withTodos(List<PlanRow> rows, UUID ownerId) {
        Set<UUID> todoIds = rows.stream().map(PlanRow::todoId).collect(Collectors.toSet());
        Map<UUID, TodoView> todosById = todos.getOwnedTodoViews(todoIds, ownerId).stream()
                .collect(Collectors.toMap(TodoView::id, Function.identity()));
        List<PlanResponse> responses = new ArrayList<>();
        for (PlanRow row : rows) {
            responses.add(mapper.toResponse(row, todosById.get(row.todoId())));
        }
        return responses;
    }

    private Plan getOwnedPlan(UUID id, UUID ownerId) {
        return repository.findByIdAndOwnerId(id, ownerId).orElseThrow(AppException.supply(ErrorCode.PLAN_NOT_FOUND, id));
    }
}
