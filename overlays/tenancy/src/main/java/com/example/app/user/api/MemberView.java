package com.example.app.user.api;

import java.util.UUID;

public record MemberView(UUID userId, String email, String fullName) {
}
