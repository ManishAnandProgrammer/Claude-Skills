package com.example.app.user.dto.internal;

import java.util.UUID;

/** A membership's session version as stored, read after a bump: what Redis gets once it commits. */
public record MembershipSessionVersion(UUID membershipId, int sessionVersion) {
}
