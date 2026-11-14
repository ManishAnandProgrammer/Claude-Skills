package com.example.app.todo.mapper;

// media:begin
import com.example.app.media.api.MediaFileView;
// media:end
import com.example.app.todo.domain.Tag;
import com.example.app.todo.domain.Todo;
import com.example.app.todo.domain.TodoList;
import com.example.app.todo.dto.internal.TodoTagRow;
import com.example.app.todo.dto.request.TodoCreateRequest;
import com.example.app.todo.dto.request.TodoUpdateRequest;
import com.example.app.todo.dto.response.TodoResponse;
import com.example.app.todo.dto.response.TodoSummaryResponse;
import com.example.app.todo.dto.response.TodoTagResponse;
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
                .title(request.title().strip())
                .notes(notesOrNone(request.notes()))
                .priority(request.priority())
                .dueDate(request.dueDate())
                .build();
        todo.replaceTags(tags);
        return todo;
    }

    public void update(Todo todo, TodoUpdateRequest request, TodoList todoList) {
        todo.moveTo(todoList);
        todo.setTitle(request.title().strip());
        todo.setNotes(notesOrNone(request.notes()));
        todo.setPriority(request.priority());
        todo.setDueDate(request.dueDate());
    }

    public TodoResponse toResponse(Todo todo,
                                   // media:begin
                                   List<MediaFileView> attachments,
                                   // media:end
                                   List<TodoTagResponse> tags) {
        return new TodoResponse(
                todo.getId(),
                todo.getTodoList().getId(),
                todo.getOwnerId(),
                todo.getTitle(),
                todo.getNotes(),
                todo.getStatus(),
                todo.getPriority(),
                todo.getDueDate(),
                todo.getCompletedAt(),
                tags,
                // media:begin
                attachments,
                // media:end
                todo.getCreatedAt(),
                todo.getUpdatedAt(),
                todo.getCreatedBy(),
                todo.getUpdatedBy(),
                todo.getVersion());
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
