package com.example.app.todo.repository;

import com.example.app.todo.domain.TodoList;
import com.example.app.todo.repository.custom.CustomTodoListRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TodoListRepository extends JpaRepository<TodoList, UUID>, CustomTodoListRepository {

    Optional<TodoList> findByIdAndOwnerId(UUID id, UUID ownerId);
}
