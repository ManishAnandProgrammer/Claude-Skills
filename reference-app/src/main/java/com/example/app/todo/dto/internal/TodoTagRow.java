package com.example.app.todo.dto.internal;

import java.util.UUID;

public record TodoTagRow(UUID todoId, UUID tagId, String name) {
}
