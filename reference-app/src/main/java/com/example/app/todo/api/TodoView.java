package com.example.app.todo.api;

import java.util.UUID;

public record TodoView(UUID id, String title, boolean open) {
}
