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

    /**
     * The days of the owner's live plans (not CANCELLED) that share a day with {@code from}..{@code to}, both
     * inclusive, in calendar order: one statement over {@code idx_plans_owner_id_starts_on_id}.
     */
    List<PlanDays> findLiveDays(UUID ownerId, LocalDate from, LocalDate to);
}
