package com.example.app.user.repository.custom;

// tenancy:begin
// tenancy:else
// import com.example.app.common.dropdown.DropdownFilter;
// import com.example.app.common.dropdown.DropdownItem;
// import com.example.app.common.pagination.CursorBatch;
// tenancy:end
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
    // tenancy:begin
    // tenancy:else
    //
    // /** Active accounts but {@code leftOut} (null for none) holding every one of {@code permissions} now, none of them denied. */
    // CursorBatch<DropdownItem> findHolders(Collection<String> permissions, UUID leftOut, DropdownFilter filter, int size,
    //         Instant now);
    // tenancy:end
}
