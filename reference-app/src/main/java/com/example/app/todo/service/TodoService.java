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
// metadata:begin
import com.example.app.metadata.api.Counters;
// metadata:end
import com.example.app.todo.api.TodoView;
import com.example.app.todo.api.Todos;
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
// tenancy:begin
// import com.example.app.todo.dto.response.TodoRequesterResponse;
// tenancy:end
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
// tenancy:begin
// import com.example.app.user.api.MemberView;
// import com.example.app.user.api.Members;
// tenancy:else
import com.example.app.user.api.Users;
// tenancy:end
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Every read and write is scoped to the caller: another user's todo, or list, answers 404 like a
 * missing one. The todo's aggregate includes its time entries and its tag rows (which tags it
 * carries): they are read and written only here, always through their todo. The tags themselves are
 * another aggregate's, reached through {@link TagService}. Other modules reach a todo only through
 * {@link Todos}, which this service implements.
 *
 * <p>A response shows the todo's list by name, so it is read with the todo in one statement where the
 * todo isn't locked (the detail, a status change that may change nothing); a locked todo's list can't go
 * while the lock is held (the list's delete is refused while it has todos, and the todo's delete waits),
 * so the writes that lock it read the list afterwards. The requester is another module's person: an id,
 * checked through that module's facade at the create, and (with tenancy) shown by name while they are a
 * member.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TodoService implements Todos {

    // metadata:begin
    /** The display numbers' counter (Counters): one for the application, or one per tenant with tenancy. */
    static final String NUMBER_COUNTER = "TODO_NUMBER";

    // metadata:end
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
    // tenancy:begin
    // private final Members members;
    // tenancy:else
    private final Users users;
    // tenancy:end
    // metadata:begin
    private final Counters counters;
    // metadata:end
    // media:begin
    private final TodoAttachmentRepository attachmentRepository;
    private final Uploads uploads;
    // media:end

    @Transactional
    public ObjectWrapper<TodoResponse> create(TodoCreateRequest request) {
        UUID ownerId = currentUser.id();
        TodoList todoList = todoListService.getOwnedTodoList(request.todoListId(), ownerId);
        List<Tag> tags = tagService.getOwnedTags(Objects.requireNonNullElse(request.tagIds(), Set.of()), ownerId);
        if (Objects.nonNull(request.requesterId())) {
            requireRequester(request.requesterId());
        }
        Todo todo = mapper.toEntity(request, todoList, tags, ownerId);
        // media:begin
        Set<String> attachmentKeys = Objects.requireNonNullElse(request.attachmentKeys(), Set.of());
        uploads.fileCountRange(UploadPurpose.TODO_ATTACHMENT).requireWithin(attachmentKeys.size());
        todo.attach(uploads.claim(UploadPurpose.TODO_ATTACHMENT, attachmentKeys));
        // media:end
        // metadata:begin
        // tenancy-row:begin
        // // A tenant activated by an instance without TodoNumberSeed (a rolling deploy) numbers from 1 too.
        // counters.createIfAbsent(NUMBER_COUNTER, 0);
        // tenancy-row:end
        // Last, after every check: the counter's row stays locked until the commit.
        todo.assignNumber(counters.next(NUMBER_COUNTER));
        // metadata:end
        Todo saved = repository.saveAndFlush(todo);
        return ObjectWrapper.withObject(toResponse(saved));
    }

    public ObjectWrapper<TodoResponse> findById(UUID id) {
        return ObjectWrapper.withObject(toResponse(getOwnedTodoWithItsList(id, currentUser.id())));
    }

    public CursorWrapper<TodoSummaryResponse> findByFilter(TodoFilter filter) {
        CursorBatch<TodoSummaryResponse> batch = repository.findByFilter(filter, currentUser.id(), paging.cursorSize(filter));
        return CursorWrapper.of(withTags(batch.items()), batch.nextCursor());
    }

    /**
     * The todo is locked first ({@code FOR NO KEY UPDATE}), before its list, its files or any other row:
     * the update may change its member rows, and two concurrent changes of them with one version then
     * queue here and the second answers 409. Without it, both would get as far as inserting their link
     * rows (each insert's foreign key check holds the todo {@code FOR KEY SHARE}), and an owner whose
     * update also changes a unique column (a code, an email) needs a key lock for that update: each would
     * wait for the other's key share, a deadlock answered 503 instead of 409.
     */
    @Transactional
    public ObjectWrapper<TodoResponse> update(UUID id, TodoUpdateRequest request) {
        UUID ownerId = currentUser.id();
        Todo todo = getOwnedTodoForUpdate(id, ownerId);
        todo.checkVersion(request.version());
        TodoList todoList = todoListService.getOwnedTodoList(request.todoListId(), ownerId);
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

    /**
     * Replaces the tags as a whole; the version increments when they change (Todo.tagLinks). A tag
     * delete removes its links by the database's cascade, which the todo's lock doesn't cover: a
     * replace that drops a link such a delete is removing at that moment finds it gone at the flush and
     * answers 409 {@code OPTIMISTIC_LOCK_CONFLICT}, as documented; the client reloads and retries.
     */
    @Transactional
    public ObjectWrapper<TodoResponse> replaceTags(UUID id, TodoTagsRequest request) {
        UUID ownerId = currentUser.id();
        Todo todo = getOwnedTodoForUpdate(id, ownerId);
        todo.checkVersion(request.version());
        todo.replaceTags(tagService.getOwnedTags(request.tagIds(), ownerId));
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
     * Its tag rows go with it (the tags stay). A todo with time entries or plans is refused at the
     * flush by their foreign keys: 409 {@code TODO_IN_USE}. Locked first, like the update, so it queues
     * behind a change of its member rows instead of deadlocking with one. It takes no version and never
     * answers 409 {@code OPTIMISTIC_LOCK_CONFLICT}: the lock keeps the todo's own writers out, and its
     * tag rows, which a tag's delete removes by cascade without the todo's lock, go first in one
     * statement that doesn't count them (a versioned delete per row would miss the removed ones).
     */
    @Transactional
    public void delete(UUID id) {
        Todo todo = getOwnedTodoForUpdate(id, currentUser.id());
        todoTagRepository.deleteAllOfTodo(id);
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

    /** Locked as the todo's own writes lock it (findForUpdateByIdAndOwnerId), so they queue behind the caller. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public TodoView getOwnedTodoViewForUpdate(UUID id, UUID ownerId) {
        return mapper.toView(getOwnedTodoForUpdate(id, ownerId));
    }

    @Override
    public Optional<TodoView> findOwnedTodoView(UUID id, UUID ownerId) {
        return repository.findByIdAndOwnerId(id, ownerId).map(mapper::toView);
    }

    @Override
    public List<TodoView> getOwnedTodoViews(Collection<UUID> ids, UUID ownerId) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return repository.findViewsByIdInAndOwnerId(ids, ownerId);
    }

    /** Not locked: an entry written while its todo is deleted is refused by its foreign key, 404 {@code TODO_GONE}. */
    @Transactional
    public ObjectWrapper<TimeEntryResponse> createTimeEntry(UUID todoId, TimeEntryCreateRequest request) {
        Todo todo = getOwnedTodo(todoId, currentUser.id());
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
                // tenancy:begin
                // requesterOf(todo),
                // tenancy:end
                todoTagRepository.findTagsOfTodo(todo.getId()));
    }
    // tenancy:begin

    // /** By name while they are a member; a former member by id alone (their todos stay as they were). */
    // private TodoRequesterResponse requesterOf(Todo todo) {
    //     if (Objects.isNull(todo.getRequesterId())) {
    //         return null;
    //     }
    //     String fullName = members.getMemberViews(List.of(todo.getRequesterId())).stream()
    //             .map(MemberView::fullName)
    //             .findFirst()
    //             .orElse(null);
    //     return new TodoRequesterResponse(todo.getRequesterId(), fullName);
    // }
    // tenancy:end
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

    /** With its list: a status it already has changes nothing, so no row lock keeps its list there. */
    private Todo getEditableTodo(UUID id, UUID ownerId, Long expectedVersion) {
        Todo todo = getOwnedTodoWithItsList(id, ownerId);
        todo.checkVersion(expectedVersion);
        return todo;
    }

    private Todo getOwnedTodoForUpdate(UUID id, UUID ownerId) {
        return repository.findForUpdateByIdAndOwnerId(id, ownerId).orElseThrow(AppException.supply(ErrorCode.TODO_NOT_FOUND, id));
    }

    private Todo getOwnedTodo(UUID id, UUID ownerId) {
        return repository.findByIdAndOwnerId(id, ownerId).orElseThrow(AppException.supply(ErrorCode.TODO_NOT_FOUND, id));
    }

    private Todo getOwnedTodoWithItsList(UUID id, UUID ownerId) {
        return repository.findWithTodoListByIdAndOwnerId(id, ownerId).orElseThrow(AppException.supply(ErrorCode.TODO_NOT_FOUND, id));
    }

    /**
     * A person the create names: an account (with tenancy, a member now: 404 {@code TENANT_MEMBER_NOT_FOUND}
     * for a removed one, whose row in {@code members} the foreign key would still accept).
     */
    private void requireRequester(UUID requesterId) {
        // tenancy:begin
        // if (!members.isMember(requesterId)) {
        //     throw AppException.of(ErrorCode.TENANT_MEMBER_NOT_FOUND, requesterId);
        // }
        // tenancy:else
        users.requireUser(requesterId);
        // tenancy:end
    }

    /** The todo first, so an unknown or foreign todo answers TODO_NOT_FOUND, and an entry of another todo is not found. */
    private TimeEntry getOwnedTimeEntry(UUID todoId, UUID id, UUID ownerId) {
        getOwnedTodo(todoId, ownerId);
        return timeEntryRepository.findByIdAndTodoIdAndOwnerId(id, todoId, ownerId)
                .orElseThrow(AppException.supply(ErrorCode.TIME_ENTRY_NOT_FOUND, id));
    }
}
