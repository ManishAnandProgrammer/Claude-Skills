package com.example.app.user.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The current tenant's members, for the modules whose records name a person (an assignee, a reviewer):
 * they ask here, never the membership or user repositories. Every lookup is scoped to the tenant of the
 * request ({@code TenantContext}): a member of another tenant is not a member here, like an unknown id.
 * A removed member is not a member, though their row in the tenant's own {@code members} table stays.
 */
public interface Members {

    /** Whether the account belongs to the current tenant now: for a write that names a person. */
    boolean isMember(UUID userId);

    /** The current tenant's member. 404 {@code TENANT_MEMBER_NOT_FOUND} when the account isn't one. */
    MemberView getMemberView(UUID userId);

    /**
     * The current tenant's members among {@code userIds}, in one statement, for a page of records that
     * name them; ids that aren't members here are simply absent.
     */
    List<MemberView> getMemberViews(Collection<UUID> userIds);
}
