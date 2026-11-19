package com.example.app.user.repository.custom;

import com.example.app.user.domain.AccessRow;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CustomRoleAssignmentRepository {

    /**
     * The current access rows of these people, in one indexed statement: the permissions of their role assignments
     * and their personal grants that haven't ended at {@code now}, for active accounts only (a suspended one gets
     * none, so it holds nothing from its next request). Read on every authenticated request.
     */
    List<AccessRow> findAccessRows(Collection<UUID> userIds, Instant now);

    /**
     * The active accounts that may hold one of {@code permissions} for good: a role giving it assigned without an
     * end, or a personal ALLOW of it without an end (safety rule 6's candidates; their DENYs are read with their rows).
     */
    List<UUID> findHoldersForGood(Collection<String> permissions);
}
