package com.example.app.todo.mapper;

import com.example.app.todo.domain.TimeEntry;
import com.example.app.todo.domain.Todo;
import com.example.app.todo.dto.request.TimeEntryCreateRequest;
import com.example.app.todo.dto.request.TimeEntryUpdateRequest;
import com.example.app.todo.dto.response.TimeEntryResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TimeEntryMapper {

    /** The entry takes its todo's owner, which the composite foreign key requires. */
    public TimeEntry toEntity(TimeEntryCreateRequest request, Todo todo) {
        return TimeEntry.builder()
                .todo(todo)
                .ownerId(todo.getOwnerId())
                .minutes(request.minutes())
                .spentOn(request.spentOn())
                .note(noteOrNone(request.note()))
                .billable(request.billable())
                .build();
    }

    public void update(TimeEntry timeEntry, TimeEntryUpdateRequest request) {
        timeEntry.setMinutes(request.minutes());
        timeEntry.setSpentOn(request.spentOn());
        timeEntry.setNote(noteOrNone(request.note()));
        timeEntry.setBillable(request.billable());
    }

    public TimeEntryResponse toResponse(TimeEntry timeEntry) {
        return new TimeEntryResponse(
                timeEntry.getId(),
                timeEntry.getTodo().getId(),
                timeEntry.getOwnerId(),
                timeEntry.getMinutes(),
                timeEntry.getSpentOn(),
                timeEntry.getNote(),
                timeEntry.isBillable(),
                timeEntry.getCreatedAt(),
                timeEntry.getUpdatedAt(),
                timeEntry.getCreatedBy(),
                timeEntry.getUpdatedBy(),
                timeEntry.getVersion());
    }

    /** One representation of "no note": blank is stored as null. */
    private static String noteOrNone(String note) {
        return StringUtils.hasText(note) ? note : null;
    }
}
