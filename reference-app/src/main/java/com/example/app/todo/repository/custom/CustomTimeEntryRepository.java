package com.example.app.todo.repository.custom;

import com.example.app.common.pagination.CursorBatch;
import com.example.app.todo.dto.request.TimeEntryFilter;
import com.example.app.todo.dto.response.TimeEntryResponse;

import java.util.UUID;

public interface CustomTimeEntryRepository {

    CursorBatch<TimeEntryResponse> findByFilter(TimeEntryFilter filter, UUID todoId, UUID ownerId, int size);
}
