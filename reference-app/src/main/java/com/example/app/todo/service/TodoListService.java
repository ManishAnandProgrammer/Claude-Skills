package com.example.app.todo.service;

import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownFor;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.dropdown.DropdownStatus;
import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.exception.RequestError;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.pagination.Paging;
import com.example.app.common.security.CurrentUser;
import com.example.app.common.security.Permission;
import com.example.app.common.security.Reach;
import com.example.app.common.wrapper.CursorWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.common.wrapper.PaginationWrapper;
import com.example.app.todo.domain.TodoList;
import com.example.app.todo.dto.internal.TodoListName;
import com.example.app.todo.dto.request.TodoListCreateRequest;
import com.example.app.todo.dto.request.TodoListFilter;
import com.example.app.todo.dto.request.TodoListUpdateRequest;
import com.example.app.todo.dto.response.TodoListResponse;
import com.example.app.todo.mapper.TodoListMapper;
import com.example.app.todo.repository.TodoListRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
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

    /**
     * The lists the action {@code action} may use ({@link DropdownFor}): the caller's own, the one reach this
     * dropdown lists, so the owner condition is the reach's and the batch stays one statement. Lists have no
     * active flag, so a {@code status} other than the default is refused, not ignored.
     */
    public CursorWrapper<DropdownItem> dropdown(Permission action, DropdownFilter filter) {
        DropdownFor.check(action, currentUser.permissions(), EnumSet.of(Reach.OWN));
        if (filter.getStatus() != DropdownStatus.ACTIVE) {
            throw AppException.withErrors(ErrorCode.REQUEST_PARAMETER_INVALID,
                    List.of(RequestError.atParameter("status", RequestError.INVALID_VALUE)));
        }
        CursorBatch<DropdownItem> batch = repository.findDropdownItems(filter, currentUser.id(), paging.cursorSize(filter));
        return CursorWrapper.of(batch.items(), batch.nextCursor());
    }

    /**
     * The names of the owner's lists among {@code ids}, for a page of todos (TodoService.findByFilter): one
     * statement, absent ids simply absent (a list deleted since the page was read), none for no ids.
     */
    public List<TodoListName> findOwnedTodoListNames(Collection<UUID> ids, UUID ownerId) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return repository.findNamesByIdInAndOwnerId(ids, ownerId);
    }

    /**
     * Not locked: a todo written into the list while the list is deleted is refused by its foreign key,
     * 404 {@code TODO_LIST_GONE}.
     */
    public TodoList getOwnedTodoList(UUID id, UUID ownerId) {
        return repository.findByIdAndOwnerId(id, ownerId).orElseThrow(AppException.supply(ErrorCode.TODO_LIST_NOT_FOUND, id));
    }
}
