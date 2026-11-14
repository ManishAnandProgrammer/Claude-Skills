package com.example.app.todo.dto.internal;

import java.util.UUID;

/** One tag of one todo, as the list's second step reads the tags of a whole batch at once. */
public record TodoTagRow(UUID todoId, UUID tagId, String name) {
}
