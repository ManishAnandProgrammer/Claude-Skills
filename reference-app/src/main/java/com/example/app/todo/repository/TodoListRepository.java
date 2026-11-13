package com.example.app.todo.repository;

import com.example.app.todo.domain.TodoList;
import com.example.app.todo.repository.custom.CustomTodoListRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

public interface TodoListRepository extends JpaRepository<TodoList, UUID>, CustomTodoListRepository {

    Optional<TodoList> findByIdAndOwnerId(UUID id, UUID ownerId);

    /**
     * The owner's list, locked {@code FOR SHARE} until the transaction ends: a todo written into it
     * can't race the list's delete (the delete waits, then answers {@code TODO_LIST_IN_USE}).
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<TodoList> findForShareByIdAndOwnerId(UUID id, UUID ownerId);
}
