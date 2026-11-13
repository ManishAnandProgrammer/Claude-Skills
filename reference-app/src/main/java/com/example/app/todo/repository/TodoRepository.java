package com.example.app.todo.repository;

import com.example.app.todo.domain.Todo;
import com.example.app.todo.repository.custom.CustomTodoRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TodoRepository extends JpaRepository<Todo, UUID>, CustomTodoRepository {

    Optional<Todo> findByIdAndOwnerId(UUID id, UUID ownerId);
}
