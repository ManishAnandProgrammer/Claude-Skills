package com.example.app.user.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** A removed member is not a member, though their row in the tenant's own {@code members} table stays. */
public interface Members {

    boolean isMember(UUID userId);

    MemberView getMemberView(UUID userId);

    List<MemberView> getMemberViews(Collection<UUID> userIds);
}
