package com.example.app.user.repository.custom;

import com.example.app.user.domain.AccessRow;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CustomRoleAssignmentRepository {

    /**
     * Active accounts only, so a suspended one holds nothing from its next request. Read on every authenticated
     * request.
     */
    List<AccessRow> findAccessRows(Collection<UUID> userIds, Instant now);

    List<AccessRow> findAccessRowsWhateverTheAccountStatus(Collection<UUID> userIds, Instant now);
    // tenancy:begin

    List<AccessRow> findAccessRowsWhateverTheTenantStatus(Collection<UUID> userIds, Instant now);
    // tenancy:end

    /** Candidates only: their DENYs are read with their rows. */
    List<UUID> findHoldersForGood(Collection<String> permissions);
}
