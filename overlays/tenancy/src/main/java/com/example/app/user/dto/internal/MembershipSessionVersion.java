package com.example.app.user.dto.internal;

import java.util.UUID;

public record MembershipSessionVersion(UUID membershipId, int sessionVersion) {
}
