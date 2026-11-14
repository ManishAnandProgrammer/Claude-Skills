package com.example.app.planning.repository.custom;

import com.example.app.common.pagination.CursorBatch;
import com.example.app.planning.dto.internal.PlanRow;
import com.example.app.planning.dto.request.PlanFilter;

import java.util.UUID;

public interface CustomPlanRepository {

    CursorBatch<PlanRow> findByFilter(PlanFilter filter, UUID ownerId, int size);
}
