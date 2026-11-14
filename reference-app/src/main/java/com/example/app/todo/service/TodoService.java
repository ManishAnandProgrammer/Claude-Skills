package com.example.app.todo.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.pagination.Paging;
import com.example.app.common.security.CurrentUser;
import com.example.app.common.wrapper.CursorWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
// media:begin
import com.example.app.media.api.MediaFileView;
import com.example.app.media.api.UploadPurpose;
import com.example.app.media.api.Uploads;
// media:end
import com.example.app.todo.domain.Tag;
import com.example.app.todo.domain.TimeEntry;
import com.example.app.todo.domain.Todo;
import com.example.app.todo.domain.TodoList;
import com.example.app.todo.dto.request.TimeEntryCreateRequest;
import com.example.app.todo.dto.request.TimeEntryFilter;
import com.example.app.todo.dto.request.TimeEntryUpdateRequest;
import com.example.app.todo.dto.request.TodoCreateRequest;
import com.example.app.todo.dto.request.TodoFilter;
import com.example.app.todo.dto.request.TodoStatusRequest;
import com.example.app.todo.dto.request.TodoTagsRequest;
import com.example.app.todo.dto.request.TodoUpdateRequest;
import com.example.app.todo.dto.response.TimeEntryResponse;
import com.example.app.todo.dto.response.TodoResponse;
import com.example.app.todo.dto.response.TodoSummaryResponse;
import com.example.app.todo.mapper.TimeEntryMapper;
import com.example.app.todo.mapper.TodoMapper;
import com.example.app.todo.repository.TimeEntryRepository;
// media:begin
import com.example.app.todo.repository.TodoAttachmentRepository;
// media:end
import com.example.app.todo.repository.TodoRepository;
import com.example.app.todo.repository.TodoTagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Every read and write is scoped to the caller: another user's todo, or list, answers 404 like a
 * missing one. The todo's aggregate includes its time entries and its tag rows (which tags it
 * carries): they are read and written only here, always through their todo. The tags themselves are
 * another aggregate's, reached through {@link TagService}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TodoService {

    private final TodoRepository repository;
    private final TodoMapper mapper;
    private final TimeEntryRepository timeEntryRepository;
    private final TimeEntryMapper timeEntryMapper;
    private final TodoTagRepository todoTagRepository;
    private final TodoListService todoListService;
    private final TagService tagService;
    private final CurrentUser currentUser;
    private final Clock clock;
    private final Paging paging;
    // media:begin
    private final TodoAttachmentRepository attachmentRepository;
    private final Uploads uploads;
    // media:end

    @Transactional
    public ObjectWrapper<TodoResponse> create(TodoCreateRequest request) {
        UUID ownerId = currentUser.id();
        TodoList todoList = todoListService.getOwnedTodoListForWrite(request.todoListId(), ownerId);
        List<Tag> tags = tagService.getOwnedTagsForWrite(Objects.requireNonNullElse(request.tagIds(), Set.of()), ownerId);
        Todo todo = mapper.toEntity(request, todoList, tags, ownerId);
        // media:begin
        Set<String> attachmentKeys = Objects.requireNonNullElse(request.attachmentKeys(), Set.of());
        uploads.fileCountRange(UploadPurpose.TODO_ATTACHMENT).requireWithin(attachmentKeys.size());
        todo.attach(uploads.claim(UploadPurpose.TODO_ATTACHMENT, attachmentKeys));
        // media:end
        Todo saved = repository.saveAndFlush(todo);
        return ObjectWrapper.withObject(toResponse(saved));
    }

    public ObjectWrapper<TodoResponse> findById(UUID id) {
        return ObjectWrapper.withObject(toResponse(getOwnedTodo(id, currentUser.id())));
    }

    public CursorWrapper<TodoSummaryResponse> findByFilter(TodoFilter filter) {
        CursorBatch<TodoSummaryResponse> batch = repository.findByFilter(filter, currentUser.id(), paging.cursorSize(filter));
        return CursorWrapper.of(withTags(batch.items()), batch.nextCursor());
    }

    @Transactional
    public ObjectWrapper<TodoResponse> update(UUID id, TodoUpdateRequest request) {
        UUID ownerId = currentUser.id();
        Todo todo = getEditableTodo(id, ownerId, request.version());
        TodoList todoList = todoListService.getOwnedTodoListForWrite(request.todoListId(), ownerId);
        // media:begin
        // Before any other change: the claim's statement flushes what is pending, and a change flushed
        // there and another at the end would increment the version twice.
        Set<UUID> removed = changeAttachments(todo,
                Objects.requireNonNullElse(request.addAttachmentKeys(), Set.of()),
                Objects.requireNonNullElse(request.removeAttachmentIds(), Set.of()));
        // media:end
        mapper.update(todo, request, todoList);
        repository.flush();
        // media:begin
        // After the flush, which checked the version: a removal racing this one is refused 409 before
        // either releases anything.
        uploads.release(removed);
        // media:end
        return ObjectWrapper.withObject(toResponse(todo));
    }
    // media:begin

    /**
     * Applies the add/remove of a todo's files. Every attached file counts (MISSING ones too), and the
     * count is checked only when the files change, so a shrunk policy doesn't block editing the title.
     * A removed id must be one of its files: 404 {@code TODO_ATTACHMENT_NOT_FOUND}.
     *
     * @return the removed ids, to release once the todo's version check has passed
     */
    private Set<UUID> changeAttachments(Todo todo, Set<String> addKeys, Set<UUID> removeIds) {
        if (addKeys.isEmpty() && removeIds.isEmpty()) {
            return Set.of();
        }
        List<UUID> current = attachmentRepository.findMediaFileIdsOfTodo(todo.getId());
        for (UUID removeId : removeIds) {
            if (!current.contains(removeId)) {
                throw AppException.of(ErrorCode.TODO_ATTACHMENT_NOT_FOUND, removeId);
            }
        }
        uploads.fileCountRange(UploadPurpose.TODO_ATTACHMENT).requireWithin(current.size(), removeIds.size(), addKeys.size());
        List<UUID> added = uploads.claim(UploadPurpose.TODO_ATTACHMENT, addKeys);
        todo.detach(removeIds);
        todo.attach(added);
        return removeIds;
    }
    // media:end

    /** Replaces the tags as a whole; the version increments when they change (Todo.tagLinks). */
    @Transactional
    public ObjectWrapper<TodoResponse> replaceTags(UUID id, TodoTagsRequest request) {
        UUID ownerId = currentUser.id();
        Todo todo = getOwnedTodoForUpdate(id, ownerId);
        todo.checkVersion(request.version());
        todo.replaceTags(tagService.getOwnedTagsForWrite(request.tagIds(), ownerId));
        repository.flush();
        return ObjectWrapper.withObject(toResponse(todo));
    }

    @Transactional
    public ObjectWrapper<TodoResponse> changeStatus(UUID id, TodoStatusRequest request) {
        Todo todo = getEditableTodo(id, currentUser.id(), request.version());
        todo.changeStatus(request.status(), clock.instant());
        repository.flush();
        return ObjectWrapper.withObject(toResponse(todo));
    }

    /**
     * Its tag rows go with it (the tags stay). A todo with time entries is refused at the flush by
     * their foreign key: 409 {@code TODO_IN_USE}.
     */
    @Transactional
    public void delete(UUID id) {
        Todo todo = getOwnedTodoForUpdate(id, currentUser.id());
        // media:begin
        List<UUID> attachments = attachmentRepository.findMediaFileIdsOfTodo(id);
        // media:end
        repository.delete(todo);
        repository.flush();
        // media:begin
        // Its files go with it: the cleanup job deletes them.
        uploads.release(attachments);
        // media:end
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

    private TodoResponse toResponse(Todo todo) {
        return mapper.toResponse(todo,
                // media:begin
                attachmentsOf(todo),
                // media:end
                todoTagRepository.findTagsOfTodo(todo.getId()));
    }
    // media:begin

    private List<MediaFileView> attachmentsOf(Todo todo) {
        return uploads.describe(attachmentRepository.findMediaFileIdsOfTodo(todo.getId()));
    }
    // media:end

    /** Two steps, never a collection fetch (DATA-26): the batch, then one query for all its tags. */
    private List<TodoSummaryResponse> withTags(List<TodoSummaryResponse> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        List<UUID> ids = rows.stream().map(TodoSummaryResponse::id).toList();
        return mapper.withTags(rows, todoTagRepository.findTagRowsOfTodos(ids));
    }

    private Todo getEditableTodo(UUID id, UUID ownerId, Long expectedVersion) {
        Todo todo = getOwnedTodo(id, ownerId);
        todo.checkVersion(expectedVersion);
        return todo;
    }

    private Todo getOwnedTodoForUpdate(UUID id, UUID ownerId) {
        return repository.findForUpdateByIdAndOwnerId(id, ownerId).orElseThrow(AppException.supply(ErrorCode.TODO_NOT_FOUND, id));
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
