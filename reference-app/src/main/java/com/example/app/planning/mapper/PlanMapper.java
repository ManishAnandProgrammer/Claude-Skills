package com.example.app.planning.mapper;

import com.example.app.planning.domain.Plan;
import com.example.app.planning.dto.internal.PlanRow;
import com.example.app.planning.dto.request.PlanCreateRequest;
import com.example.app.planning.dto.response.PlanResponse;
import com.example.app.planning.dto.response.PlanTodoResponse;
import com.example.app.todo.api.TodoView;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class PlanMapper {

    /** The plan takes the caller as its owner, which the composite foreign key requires of its todo too. */
    public Plan toEntity(PlanCreateRequest request, UUID ownerId) {
        return Plan.builder()
                .todoId(request.todoId())
                .ownerId(ownerId)
                .startsOn(request.startsOn())
                .endsOn(request.endsOn())
                .build();
    }

    public PlanResponse toResponse(Plan plan, TodoView todo) {
        return new PlanResponse(
                plan.getId(),
                toTodoResponse(todo),
                plan.getOwnerId(),
                plan.getStartsOn(),
                plan.getEndsOn(),
                plan.getCreatedAt(),
                plan.getUpdatedAt(),
                plan.getCreatedBy(),
                plan.getUpdatedBy(),
                plan.getVersion());
    }

    /** A list row, with its todo from the batch's one lookup. */
    public PlanResponse toResponse(PlanRow row, TodoView todo) {
        return new PlanResponse(row.id(), toTodoResponse(todo), row.ownerId(), row.startsOn(), row.endsOn(),
                row.createdAt(), row.updatedAt(), row.createdBy(), row.updatedBy(), row.version());
    }

    /** The todo module's view, in this API's own shape (ARCH-06: no other module's type on the wire). */
    private static PlanTodoResponse toTodoResponse(TodoView todo) {
        return new PlanTodoResponse(todo.id(), todo.title());
    }
}
