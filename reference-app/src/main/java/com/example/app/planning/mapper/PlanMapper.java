package com.example.app.planning.mapper;

import com.example.app.common.security.AllowedAction;
import com.example.app.planning.domain.Plan;
import com.example.app.planning.dto.internal.PlanDays;
import com.example.app.planning.dto.internal.PlanRow;
import com.example.app.planning.dto.request.PlanCreateRequest;
import com.example.app.planning.dto.response.PlanAllowedActionsResponse;
import com.example.app.planning.dto.response.PlanAvailabilityResponse;
import com.example.app.planning.dto.response.PlanAvailabilitySpan;
import com.example.app.planning.dto.response.PlanResponse;
import com.example.app.planning.dto.response.PlanTodoResponse;
import com.example.app.todo.api.TodoView;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class PlanMapper {

    public Plan toEntity(PlanCreateRequest request, UUID ownerId) {
        return Plan.builder()
                .todoId(request.todoId())
                .ownerId(ownerId)
                .startsOn(request.startsOn())
                .endsOn(request.endsOn())
                .build();
    }

    public PlanResponse toResponse(Plan plan, TodoView todo, PlanAllowedActionsResponse allowedActions) {
        return new PlanResponse(
                plan.getId(),
                toTodoResponse(todo),
                plan.getOwnerId(),
                plan.getStartsOn(),
                plan.getEndsOn(),
                plan.getStatus(),
                plan.getDoneOn(),
                allowedActions,
                plan.getCreatedAt(),
                plan.getUpdatedAt(),
                plan.getCreatedBy(),
                plan.getUpdatedBy(),
                plan.getVersion());
    }

    public PlanResponse toResponse(PlanRow row, TodoView todo, PlanAllowedActionsResponse allowedActions) {
        return new PlanResponse(row.id(), toTodoResponse(todo), row.ownerId(), row.startsOn(), row.endsOn(), row.status(),
                row.doneOn(), allowedActions, row.createdAt(), row.updatedAt(), row.createdBy(), row.updatedBy(), row.version());
    }

    /** {@code taken} must be disjoint and in calendar order, as {@code findLiveDays} reads them. */
    public PlanAvailabilityResponse toAvailability(LocalDate from, LocalDate to, List<PlanDays> taken,
                                                   AllowedAction free, AllowedAction busy) {
        List<PlanAvailabilitySpan> spans = new ArrayList<>();
        LocalDate next = from;
        for (PlanDays days : taken) {
            LocalDate first = days.startsOn().isBefore(from) ? from : days.startsOn();
            LocalDate last = days.endsOn().isAfter(to) ? to : days.endsOn();
            if (first.isAfter(next)) {
                spans.add(new PlanAvailabilitySpan(next, first.minusDays(1), free));
            }
            spans.add(new PlanAvailabilitySpan(first, last, busy));
            next = last.plusDays(1);
        }
        if (!next.isAfter(to)) {
            spans.add(new PlanAvailabilitySpan(next, to, free));
        }
        return new PlanAvailabilityResponse(from, to, spans);
    }

    private static PlanTodoResponse toTodoResponse(TodoView todo) {
        return new PlanTodoResponse(todo.id(), todo.title());
    }
}
