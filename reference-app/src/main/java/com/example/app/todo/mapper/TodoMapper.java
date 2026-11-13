package com.example.app.todo.mapper;

import com.example.app.todo.domain.Todo;
import com.example.app.todo.domain.TodoList;
import com.example.app.todo.dto.request.TodoCreateRequest;
import com.example.app.todo.dto.request.TodoUpdateRequest;
import com.example.app.todo.dto.response.TodoResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.UUID;

@Component
public class TodoMapper {

    public Todo toEntity(TodoCreateRequest request, TodoList todoList, UUID ownerId) {
        return Todo.builder()
                .todoList(todoList)
                .ownerId(ownerId)
                .title(request.title().strip())
                .notes(notesOrNone(request.notes()))
                .priority(request.priority())
                .dueDate(request.dueDate())
                .build();
    }

    public void update(Todo todo, TodoUpdateRequest request, TodoList todoList) {
        todo.moveTo(todoList);
        todo.setTitle(request.title().strip());
        todo.setNotes(notesOrNone(request.notes()));
        todo.setPriority(request.priority());
        todo.setDueDate(request.dueDate());
    }

    public TodoResponse toResponse(Todo todo) {
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
                todo.getCreatedAt(),
                todo.getUpdatedAt(),
                todo.getCreatedBy(),
                todo.getUpdatedBy(),
                todo.getVersion());
    }

    /** One representation of "no notes": blank is stored as null. */
    private static String notesOrNone(String notes) {
        return StringUtils.hasText(notes) ? notes : null;
    }
}
