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

    /** The plan takes the caller as its owner, which the composite foreign key requires of its todo too. */
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

    /** A list row, with its todo from the batch's one lookup. */
    public PlanResponse toResponse(PlanRow row, TodoView todo, PlanAllowedActionsResponse allowedActions) {
        return new PlanResponse(row.id(), toTodoResponse(todo), row.ownerId(), row.startsOn(), row.endsOn(), row.status(),
                row.doneOn(), allowedActions, row.createdAt(), row.updatedAt(), row.createdBy(), row.updatedBy(), row.version());
    }

    /**
     * Every day from {@code from} to {@code to} in one span: the taken ones (disjoint and in calendar order, as
     * {@code findLiveDays} reads them) cut to the range, and the gaps between them free.
     */
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

    /** The todo module's view, in this API's own shape (ARCH-06: no other module's type on the wire). */
    private static PlanTodoResponse toTodoResponse(TodoView todo) {
        return new PlanTodoResponse(todo.id(), todo.title());
    }
}
