package com.example.app.todo.service;

import com.example.app.common.dropdown.DropdownFor;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.dropdown.DropdownStatus;
import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.exception.RequestError;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.pagination.Paging;
import com.example.app.common.security.AllowedActionMapper;
import com.example.app.common.security.CurrentUser;
import com.example.app.common.security.Permission;
import com.example.app.common.security.Reach;
// tenancy-row:begin
// import com.example.app.common.tenancy.TenantContext;
// tenancy-row:end
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
import com.example.app.todo.dto.internal.TodoResponseParts;
import com.example.app.todo.dto.request.TimeEntryCreateRequest;
import com.example.app.todo.dto.request.TimeEntryFilter;
import com.example.app.todo.dto.request.TimeEntryUpdateRequest;
import com.example.app.todo.dto.request.TodoCreateRequest;
import com.example.app.todo.dto.request.TodoDropdownFilter;
import com.example.app.todo.dto.request.TodoFilter;
import com.example.app.todo.dto.request.TodoStatusRequest;
import com.example.app.todo.dto.request.TodoTagsRequest;
import com.example.app.todo.dto.request.TodoUpdateRequest;
import com.example.app.todo.dto.response.TimeEntryResponse;
import com.example.app.todo.dto.response.TodoAllowedActionsResponse;
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
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TodoService implements Todos {

    // metadata:begin
    static final String NUMBER_COUNTER = "TODO_NUMBER";

    // metadata:end
    private final TodoRepository repository;
    private final TodoMapper mapper;
    private final TimeEntryRepository timeEntryRepository;
    private final TimeEntryMapper timeEntryMapper;
    private final TodoTagRepository todoTagRepository;
    private final TodoPolicy policy;
    private final AllowedActionMapper allowedActionMapper;
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
        Todo todo = repository.findWithTodoListById(id).orElseThrow(AppException.supply(ErrorCode.TODO_NOT_FOUND, id));
        policy.read(todo, currentUser.id()).orThrow();
        return ObjectWrapper.withObject(toResponse(todo));
    }

    public CursorWrapper<TodoSummaryResponse> findByFilter(TodoFilter filter) {
        UUID ownerId = currentUser.id();
        CursorBatch<TodoSummaryResponse> batch =
                repository.findByFilter(filter, policy.listScope(ownerId), paging.cursorSize(filter));
        return CursorWrapper.of(withTags(withTodoLists(batch.items(), ownerId)), batch.nextCursor());
    }

    public CursorWrapper<DropdownItem> dropdown(Permission action, TodoDropdownFilter filter) {
        DropdownFor.requireReach(action, currentUser.permissions(), EnumSet.of(Reach.OWN));
        if (filter.getStatus() != DropdownStatus.ACTIVE) {
            throw AppException.withErrors(ErrorCode.REQUEST_PARAMETER_INVALID,
                    List.of(RequestError.atParameter("status", RequestError.INVALID_VALUE)));
        }
        CursorBatch<TodoView> batch =
                repository.findDropdownViews(filter, policy.listScope(currentUser.id()), paging.cursorSize(filter));
        return CursorWrapper.of(batch.items().stream().map(mapper::toDropdownItem).toList(), batch.nextCursor());
    }

    @Transactional
    public ObjectWrapper<TodoResponse> update(UUID id, TodoUpdateRequest request) {
        UUID ownerId = currentUser.id();
        Todo todo = lockEditableTodo(id, ownerId);
        todo.checkVersion(request.version());
        TodoList todoList = todoListService.getOwnedTodoList(request.todoListId(), ownerId);
        // Only someone new: a PUT re-sends the person it read, who may have left since.
        if (Objects.nonNull(request.requesterId()) && !request.requesterId().equals(todo.getRequesterId())) {
            requireRequester(request.requesterId());
        }
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

    /** The count is checked only when the files change, so a lowered limit doesn't block editing the title. */
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
     * A tag's delete removes its links by the database's cascade, outside the todo's lock: a replace that drops a
     * link being removed that way finds it gone at the flush and answers 409 {@code OPTIMISTIC_LOCK_CONFLICT}.
     */
    @Transactional
    public ObjectWrapper<TodoResponse> replaceTags(UUID id, TodoTagsRequest request) {
        UUID ownerId = currentUser.id();
        Todo todo = lockEditableTodo(id, ownerId);
        todo.checkVersion(request.version());
        todo.replaceTags(tagService.getOwnedTags(request.tagIds(), ownerId));
        repository.flush();
        return ObjectWrapper.withObject(toResponse(todo));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void moveTagLinks(UUID sourceTagId, UUID targetTagId, UUID ownerId) {
        Instant now = clock.instant();
        UUID userId = currentUser.id();
        // tenancy-row:begin
        // UUID tenantId = TenantContext.id();
        // repository.lockAllCarryingTag(tenantId, sourceTagId, ownerId);
        // repository.bumpVersionsOfAllCarryingTag(sourceTagId, ownerId, now, userId);
        // todoTagRepository.addTargetWhereSourceIs(tenantId, sourceTagId, targetTagId, ownerId, now, userId);
        // tenancy-row:else
        repository.lockAllCarryingTag(sourceTagId, ownerId);
        repository.bumpVersionsOfAllCarryingTag(sourceTagId, ownerId, now, userId);
        todoTagRepository.addTargetWhereSourceIs(sourceTagId, targetTagId, ownerId, now, userId);
        // tenancy-row:end
        todoTagRepository.deleteAllOfTag(sourceTagId, ownerId);
    }

    @Transactional
    public ObjectWrapper<TodoResponse> changeStatus(UUID id, TodoStatusRequest request) {
        Todo todo = getEditableTodoAtVersion(id, currentUser.id(), request.version());
        todo.changeStatus(request.status(), clock.instant());
        repository.flush();
        return ObjectWrapper.withObject(toResponse(todo));
    }

    @Transactional
    public void delete(UUID id) {
        Todo todo = lockEditableTodo(id, currentUser.id());
        todoTagRepository.deleteAllOfTodo(id);
        // media:begin
        List<UUID> attachments = attachmentRepository.findMediaFileIdsOfTodo(id);
        // media:end
        repository.delete(todo);
        repository.flush();
        // media:begin
        uploads.release(attachments);
        // media:end
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public TodoView getOwnedTodoViewForUpdate(UUID id, UUID ownerId) {
        return mapper.toView(lockEditableTodo(id, ownerId));
    }

    @Override
    public Optional<TodoView> findOwnedTodoView(UUID id, UUID ownerId) {
        return repository.findById(id).filter(todo -> policy.edit(todo, ownerId).isAllowed()).map(mapper::toView);
    }

    @Override
    public List<TodoView> getOwnedTodoViews(Collection<UUID> ids, UUID ownerId) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return repository.findViewsByIdInAndOwnerId(ids, ownerId);
    }

    @Override
    public CursorBatch<TodoView> getOwnedTodoViewBatch(UUID todoListId, String query, String after, int size, UUID ownerId) {
        TodoDropdownFilter filter = new TodoDropdownFilter();
        filter.setTodoListId(todoListId);
        filter.setQuery(query);
        filter.setAfter(after);
        return repository.findDropdownViews(filter, policy.listScope(ownerId), size);
    }

    @Transactional
    public ObjectWrapper<TimeEntryResponse> createTimeEntry(UUID todoId, TimeEntryCreateRequest request) {
        Todo todo = getEditableTodo(todoId, currentUser.id());
        TimeEntry saved = timeEntryRepository.saveAndFlush(timeEntryMapper.toEntity(request, todo));
        return ObjectWrapper.withObject(timeEntryMapper.toResponse(saved));
    }

    public ObjectWrapper<TimeEntryResponse> findTimeEntry(UUID todoId, UUID id) {
        return ObjectWrapper.withObject(timeEntryMapper.toResponse(getOwnedTimeEntry(todoId, id, currentUser.id())));
    }

    /**
     * The batch is read before the todo's check, so a tampered cursor is a 400 whatever the todo; it holds the
     * owner's rows only, so nothing of another's todo is read.
     */
    public CursorWrapper<TimeEntryResponse> findTimeEntries(UUID todoId, TimeEntryFilter filter) {
        UUID ownerId = currentUser.id();
        CursorBatch<TimeEntryResponse> batch =
                timeEntryRepository.findByFilter(filter, todoId, ownerId, paging.cursorSize(filter));
        getEditableTodo(todoId, ownerId);
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
        return mapper.toResponse(todo, new TodoResponseParts(
                // media:begin
                attachmentsOf(todo),
                // media:end
                // tenancy:begin
                // requesterOf(todo),
                // tenancy:end
                todoTagRepository.findTagsOfTodo(todo.getId()),
                new TodoAllowedActionsResponse(allowedActionMapper.toAllowedAction("edit", Permission.TODO_EDIT_OWN, policy.edit(todo, currentUser.id())))));
    }
    // tenancy:begin

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

    private List<TodoSummaryResponse> withTodoLists(List<TodoSummaryResponse> rows, UUID ownerId) {
        if (rows.isEmpty()) {
            return rows;
        }
        Set<UUID> listIds = rows.stream().map(TodoSummaryResponse::todoListId).collect(Collectors.toSet());
        return mapper.withTodoLists(rows, todoListService.findOwnedTodoListNames(listIds, ownerId));
    }

    private List<TodoSummaryResponse> withTags(List<TodoSummaryResponse> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        List<UUID> ids = rows.stream().map(TodoSummaryResponse::id).toList();
        return mapper.withTags(rows, todoTagRepository.findTagRowsOfTodos(ids));
    }

    /** With its list: a change to the status it has takes no row lock, so nothing keeps its list there. */
    private Todo getEditableTodoAtVersion(UUID id, UUID callerId, Long expectedVersion) {
        Todo todo = repository.findWithTodoListById(id).orElseThrow(AppException.supply(ErrorCode.TODO_NOT_FOUND, id));
        policy.edit(todo, callerId).orThrow();
        todo.checkVersion(expectedVersion);
        return todo;
    }

    private Todo lockEditableTodo(UUID id, UUID callerId) {
        Optional<Todo> locked = repository.findForUpdate(id, policy.editScope(callerId));
        if (locked.isEmpty()) {
            Todo todo = repository.findById(id).orElseThrow(AppException.supply(ErrorCode.TODO_NOT_FOUND, id));
            policy.edit(todo, callerId).orThrow();
        }
        return locked.orElseThrow(AppException.supply(ErrorCode.TODO_NOT_FOUND, id));
    }

    private Todo getEditableTodo(UUID id, UUID callerId) {
        Todo todo = repository.findById(id).orElseThrow(AppException.supply(ErrorCode.TODO_NOT_FOUND, id));
        policy.edit(todo, callerId).orThrow();
        return todo;
    }

    private void requireRequester(UUID requesterId) {
        // tenancy:begin
        // // A removed member's row stays in members, so the foreign key alone would accept them.
        // if (!members.isMember(requesterId)) {
        //     throw AppException.of(ErrorCode.TENANT_MEMBER_NOT_FOUND, requesterId);
        // }
        // tenancy:else
        users.requireUser(requesterId);
        // tenancy:end
    }

    private TimeEntry getOwnedTimeEntry(UUID todoId, UUID id, UUID ownerId) {
        getEditableTodo(todoId, ownerId);
        return timeEntryRepository.findByIdAndTodoIdAndOwnerId(id, todoId, ownerId)
                .orElseThrow(AppException.supply(ErrorCode.TIME_ENTRY_NOT_FOUND, id));
    }
}
