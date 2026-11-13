package com.example.app.todo.service;

import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.dropdown.DropdownStatus;
import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.pagination.Paging;
import com.example.app.common.security.CurrentUser;
import com.example.app.common.wrapper.CursorWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.common.wrapper.PaginationWrapper;
import com.example.app.todo.domain.TodoList;
import com.example.app.todo.dto.request.TodoListCreateRequest;
import com.example.app.todo.dto.request.TodoListFilter;
import com.example.app.todo.dto.request.TodoListUpdateRequest;
import com.example.app.todo.dto.response.TodoListResponse;
import com.example.app.todo.mapper.TodoListMapper;
import com.example.app.todo.repository.TodoListRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Every read and write is scoped to the caller: another user's list answers 404, like a missing one. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TodoListService {

    private final TodoListRepository repository;
    private final TodoListMapper mapper;
    private final CurrentUser currentUser;
    private final Paging paging;

    /** A taken name is refused at the flush by {@code uq_todo_lists_owner_id_name}: 409 {@code TODO_LIST_NAME_TAKEN}. */
    @Transactional
    public ObjectWrapper<TodoListResponse> create(TodoListCreateRequest request) {
        TodoList saved = repository.saveAndFlush(mapper.toEntity(request, currentUser.id()));
        return ObjectWrapper.withObject(mapper.toResponse(saved));
    }

    public ObjectWrapper<TodoListResponse> findById(UUID id) {
        return ObjectWrapper.withObject(mapper.toResponse(getOwnedTodoList(id, currentUser.id())));
    }

    public PaginationWrapper<TodoListResponse> findByFilter(TodoListFilter filter) {
        return PaginationWrapper.fromPage(
                repository.findByFilter(filter, currentUser.id(), paging.offset(filter, filter.getSort().toSort())));
    }

    @Transactional
    public ObjectWrapper<TodoListResponse> update(UUID id, TodoListUpdateRequest request) {
        TodoList todoList = getOwnedTodoList(id, currentUser.id());
        todoList.checkVersion(request.version());
        mapper.update(todoList, request);
        repository.flush();
        return ObjectWrapper.withObject(mapper.toResponse(todoList));
    }

    /** A list that still has todos is refused at the flush by the todos' foreign key: 409 {@code TODO_LIST_IN_USE}. */
    @Transactional
    public void delete(UUID id) {
        repository.delete(getOwnedTodoList(id, currentUser.id()));
        repository.flush();
    }

    /** Lists have no active flag, so a {@code status} other than the default is refused, not ignored. */
    public CursorWrapper<DropdownItem> dropdown(DropdownFilter filter) {
        if (filter.getStatus() != DropdownStatus.ACTIVE) {
            throw AppException.of(ErrorCode.REQUEST_PARAMETER_INVALID).withExtension("parameter", "status");
        }
        CursorBatch<DropdownItem> batch = repository.findDropdownItems(filter, currentUser.id(), paging.cursorSize(filter));
        return CursorWrapper.of(batch.items(), batch.nextCursor());
    }

    /**
     * Locked {@code FOR SHARE} until the caller's transaction ends, so a todo written into the list
     * can't race the list's delete. Only inside that write transaction: a lock outside one would end
     * at once.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TodoList getOwnedTodoListForWrite(UUID id, UUID ownerId) {
        return repository.findForShareByIdAndOwnerId(id, ownerId).orElseThrow(AppException.supply(ErrorCode.TODO_LIST_NOT_FOUND, id));
    }

    private TodoList getOwnedTodoList(UUID id, UUID ownerId) {
        return repository.findByIdAndOwnerId(id, ownerId).orElseThrow(AppException.supply(ErrorCode.TODO_LIST_NOT_FOUND, id));
    }
}
