package com.example.app.todo.repository;

import com.example.app.todo.domain.Todo;
import com.example.app.todo.repository.custom.CustomTodoRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

public interface TodoRepository extends JpaRepository<Todo, UUID>, CustomTodoRepository {

    Optional<Todo> findByIdAndOwnerId(UUID id, UUID ownerId);

    /**
     * The owner's todo, locked {@code FOR SHARE} until the transaction ends: a time entry written
     * into it can't race the todo's delete (the delete waits, then answers {@code TODO_IN_USE}).
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<Todo> findForShareByIdAndOwnerId(UUID id, UUID ownerId);

    /**
     * The owner's todo, locked {@code FOR NO KEY UPDATE} (Hibernate's PostgreSQL write lock) until the
     * transaction ends, for the writes that touch its tag rows: a second writer with the same version
     * waits, then reads the new version and answers 409, instead of colliding on a tag row; and the
     * todo is always locked before its tag rows, so a tag change and the todo's delete can't deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Todo> findForUpdateByIdAndOwnerId(UUID id, UUID ownerId);
}
