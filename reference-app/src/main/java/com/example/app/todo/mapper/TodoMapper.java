package com.example.app.todo.mapper;

// media:begin
import com.example.app.media.api.MediaFileView;
// media:end
import com.example.app.todo.api.TodoView;
import com.example.app.todo.domain.Tag;
import com.example.app.todo.domain.Todo;
import com.example.app.todo.domain.TodoList;
import com.example.app.todo.domain.TodoStatus;
import com.example.app.todo.dto.internal.TodoListName;
import com.example.app.todo.dto.internal.TodoTagRow;
import com.example.app.todo.dto.request.TodoCreateRequest;
import com.example.app.todo.dto.request.TodoUpdateRequest;
import com.example.app.todo.dto.response.TodoAllowedActionsResponse;
// media:begin
import com.example.app.todo.dto.response.TodoAttachmentResponse;
// media:end
// tenancy:begin
// import com.example.app.todo.dto.response.TodoRequesterResponse;
// tenancy:end
import com.example.app.todo.dto.response.TodoResponse;
import com.example.app.todo.dto.response.TodoSummaryResponse;
import com.example.app.todo.dto.response.TodoTagResponse;
import com.example.app.todo.dto.response.TodoTodoListResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class TodoMapper {

    public Todo toEntity(TodoCreateRequest request, TodoList todoList, List<Tag> tags, UUID ownerId) {
        Todo todo = Todo.builder()
                .todoList(todoList)
                .ownerId(ownerId)
                .requesterId(request.requesterId())
                .title(request.title().strip())
                .notes(notesOrNone(request.notes()))
                .priority(request.priority())
                .dueDate(request.dueDate())
                .build();
        todo.replaceTags(tags);
        return todo;
    }

    /** The todo as other modules see it (the todo.api facade). */
    public TodoView toView(Todo todo) {
        return new TodoView(todo.getId(), todo.getTitle(), todo.getStatus() == TodoStatus.OPEN);
    }

    public void update(Todo todo, TodoUpdateRequest request, TodoList todoList) {
        todo.moveTo(todoList);
        todo.setTitle(request.title().strip());
        todo.setNotes(notesOrNone(request.notes()));
        todo.setPriority(request.priority());
        todo.setDueDate(request.dueDate());
        todo.setRequesterId(request.requesterId());
    }

    /** The todo's list must be loaded: TodoService reads it with the todo, or under the todo's lock. */
    public TodoResponse toResponse(Todo todo,
                                   // media:begin
                                   List<MediaFileView> attachments,
                                   // media:end
                                   // tenancy:begin
                                   // TodoRequesterResponse requester,
                                   // tenancy:end
                                   List<TodoTagResponse> tags,
                                   boolean editable) {
        return new TodoResponse(
                todo.getId(),
                todo.getTodoList().getId(),
                new TodoTodoListResponse(todo.getTodoList().getId(), todo.getTodoList().getName()),
                // metadata:begin
                todo.getNumber(),
                // metadata:end
                todo.getOwnerId(),
                // tenancy:begin
                // requester,
                // tenancy:else
                todo.getRequesterId(),
                // tenancy:end
                todo.getTitle(),
                todo.getNotes(),
                todo.getStatus(),
                todo.getPriority(),
                todo.getDueDate(),
                todo.getCompletedAt(),
                tags,
                // media:begin
                attachments.stream().map(TodoMapper::toAttachmentResponse).toList(),
                // media:end
                new TodoAllowedActionsResponse(editable),
                todo.getCreatedAt(),
                todo.getUpdatedAt(),
                todo.getCreatedBy(),
                todo.getUpdatedBy(),
                todo.getVersion());
    }

    // media:begin
    private static TodoAttachmentResponse toAttachmentResponse(MediaFileView file) {
        return new TodoAttachmentResponse(file.id(), file.fileName(), file.contentType(), file.sizeBytes(),
                file.status().name(), file.url());
    }

    // media:end
    /**
     * Each row gets its list's name; a row whose list {@code names} misses (deleted with its todos between
     * the batch's statement and this one) is left out, never shown without its list.
     */
    public List<TodoSummaryResponse> withTodoLists(List<TodoSummaryResponse> rows, List<TodoListName> names) {
        Map<UUID, TodoListName> byId = names.stream().collect(Collectors.toMap(TodoListName::id, name -> name));
        return rows.stream()
                .filter(row -> byId.containsKey(row.todoListId()))
                .map(row -> row.withTodoList(toTodoListResponse(byId.get(row.todoListId()))))
                .toList();
    }

    private static TodoTodoListResponse toTodoListResponse(TodoListName name) {
        return new TodoTodoListResponse(name.id(), name.name());
    }

    /** Each row gets its todo's tags, in the order of {@code tagRows} (tag id order). */
    public List<TodoSummaryResponse> withTags(List<TodoSummaryResponse> rows, List<TodoTagRow> tagRows) {
        Map<UUID, List<TodoTagResponse>> tagsByTodo = tagRows.stream().collect(Collectors.groupingBy(TodoTagRow::todoId,
                Collectors.mapping(TodoMapper::toTagResponse, Collectors.toList())));
        return rows.stream().map(row -> row.withTags(tagsByTodo.getOrDefault(row.id(), List.of()))).toList();
    }

    private static TodoTagResponse toTagResponse(TodoTagRow row) {
        return new TodoTagResponse(row.tagId(), row.name());
    }

    /** One representation of "no notes": blank is stored as null. */
    private static String notesOrNone(String notes) {
        return StringUtils.hasText(notes) ? notes : null;
    }
}
