package com.example.app.todo.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.pagination.Paging;
import com.example.app.common.security.CurrentUser;
import com.example.app.common.wrapper.CursorWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.todo.domain.TimeEntry;
import com.example.app.todo.domain.Todo;
import com.example.app.todo.domain.TodoList;
import com.example.app.todo.dto.request.TimeEntryCreateRequest;
import com.example.app.todo.dto.request.TimeEntryFilter;
import com.example.app.todo.dto.request.TimeEntryUpdateRequest;
import com.example.app.todo.dto.request.TodoCreateRequest;
import com.example.app.todo.dto.request.TodoFilter;
import com.example.app.todo.dto.request.TodoStatusRequest;
import com.example.app.todo.dto.request.TodoUpdateRequest;
import com.example.app.todo.dto.response.TimeEntryResponse;
import com.example.app.todo.dto.response.TodoResponse;
import com.example.app.todo.dto.response.TodoSummaryResponse;
import com.example.app.todo.mapper.TimeEntryMapper;
import com.example.app.todo.mapper.TodoMapper;
import com.example.app.todo.repository.TimeEntryRepository;
import com.example.app.todo.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Every read and write is scoped to the caller: another user's todo, or list, answers 404 like a
 * missing one. The todo's aggregate includes its time entries: they are read and written only here,
 * always through their todo.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TodoService {

    private final TodoRepository repository;
    private final TodoMapper mapper;
    private final TimeEntryRepository timeEntryRepository;
    private final TimeEntryMapper timeEntryMapper;
    private final TodoListService todoListService;
    private final CurrentUser currentUser;
    private final Clock clock;
    private final Paging paging;

    @Transactional
    public ObjectWrapper<TodoResponse> create(TodoCreateRequest request) {
        UUID ownerId = currentUser.id();
        TodoList todoList = todoListService.getOwnedTodoListForWrite(request.todoListId(), ownerId);
        Todo saved = repository.saveAndFlush(mapper.toEntity(request, todoList, ownerId));
        return ObjectWrapper.withObject(mapper.toResponse(saved));
    }

    public ObjectWrapper<TodoResponse> findById(UUID id) {
        return ObjectWrapper.withObject(mapper.toResponse(getOwnedTodo(id, currentUser.id())));
    }

    public CursorWrapper<TodoSummaryResponse> findByFilter(TodoFilter filter) {
        CursorBatch<TodoSummaryResponse> batch = repository.findByFilter(filter, currentUser.id(), paging.cursorSize(filter));
        return CursorWrapper.of(batch.items(), batch.nextCursor());
    }

    @Transactional
    public ObjectWrapper<TodoResponse> update(UUID id, TodoUpdateRequest request) {
        UUID ownerId = currentUser.id();
        Todo todo = getEditableTodo(id, ownerId, request.version());
        TodoList todoList = todoListService.getOwnedTodoListForWrite(request.todoListId(), ownerId);
        mapper.update(todo, request, todoList);
        repository.flush();
        return ObjectWrapper.withObject(mapper.toResponse(todo));
    }

    @Transactional
    public ObjectWrapper<TodoResponse> changeStatus(UUID id, TodoStatusRequest request) {
        Todo todo = getEditableTodo(id, currentUser.id(), request.version());
        todo.changeStatus(request.status(), clock.instant());
        repository.flush();
        return ObjectWrapper.withObject(mapper.toResponse(todo));
    }

    /** A todo with time entries is refused at the flush by their foreign key: 409 {@code TODO_IN_USE}. */
    @Transactional
    public void delete(UUID id) {
        repository.delete(getOwnedTodo(id, currentUser.id()));
        repository.flush();
    }

    /** The todo is locked {@code FOR SHARE}, so the entry can't race the todo's delete. */
    @Transactional
    public ObjectWrapper<TimeEntryResponse> createTimeEntry(UUID todoId, TimeEntryCreateRequest request) {
        Todo todo = repository.findForShareByIdAndOwnerId(todoId, currentUser.id())
                .orElseThrow(AppException.supply(ErrorCode.TODO_NOT_FOUND, todoId));
        TimeEntry saved = timeEntryRepository.saveAndFlush(timeEntryMapper.toEntity(request, todo));
        return ObjectWrapper.withObject(timeEntryMapper.toResponse(saved));
    }

    public ObjectWrapper<TimeEntryResponse> findTimeEntry(UUID todoId, UUID id) {
        return ObjectWrapper.withObject(timeEntryMapper.toResponse(getOwnedTimeEntry(todoId, id, currentUser.id())));
    }

    /** An unknown or foreign todo is a 404, not an empty list. */
    public CursorWrapper<TimeEntryResponse> findTimeEntries(UUID todoId, TimeEntryFilter filter) {
        UUID ownerId = currentUser.id();
        getOwnedTodo(todoId, ownerId);
        CursorBatch<TimeEntryResponse> batch =
                timeEntryRepository.findByFilter(filter, todoId, ownerId, paging.cursorSize(filter));
        return CursorWrapper.of(batch.items(), batch.nextCursor());
    }

    @Transactional
    public ObjectWrapper<TimeEntryResponse> updateTimeEntry(UUID todoId, UUID id, TimeEntryUpdateRequest request) {
        TimeEntry timeEntry = getOwnedTimeEntry(todoId, id, currentUser.id());
        timeEntry.checkVersion(request.version());
        timeEntryMapper.update(timeEntry, request);
        timeEntryRepository.flush();
        return ObjectWrapper.withObject(timeEntryMapper.toResponse(timeEntry));
    }

    @Transactional
    public void deleteTimeEntry(UUID todoId, UUID id) {
        timeEntryRepository.delete(getOwnedTimeEntry(todoId, id, currentUser.id()));
        timeEntryRepository.flush();
    }

    private Todo getEditableTodo(UUID id, UUID ownerId, Long expectedVersion) {
        Todo todo = getOwnedTodo(id, ownerId);
        todo.checkVersion(expectedVersion);
        return todo;
    }

    private Todo getOwnedTodo(UUID id, UUID ownerId) {
        return repository.findByIdAndOwnerId(id, ownerId).orElseThrow(AppException.supply(ErrorCode.TODO_NOT_FOUND, id));
    }

    /** The todo first, so an unknown or foreign todo answers TODO_NOT_FOUND, and an entry of another todo is not found. */
    private TimeEntry getOwnedTimeEntry(UUID todoId, UUID id, UUID ownerId) {
        getOwnedTodo(todoId, ownerId);
        return timeEntryRepository.findByIdAndTodoIdAndOwnerId(id, todoId, ownerId)
                .orElseThrow(AppException.supply(ErrorCode.TIME_ENTRY_NOT_FOUND, id));
    }
}
