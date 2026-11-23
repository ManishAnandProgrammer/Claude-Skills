package com.example.app.planning.repository.custom;

import com.example.app.common.pagination.CursorBatch;
import com.example.app.planning.dto.internal.PlanDays;
import com.example.app.planning.dto.internal.PlanRow;
import com.example.app.planning.dto.request.PlanFilter;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface CustomPlanRepository {

    CursorBatch<PlanRow> findByFilter(PlanFilter filter, UUID ownerId, int size);

    List<PlanDays> findLiveDays(UUID ownerId, LocalDate from, LocalDate to);
}
