package com.example.app.todo.repository;

import com.example.app.todo.domain.Todo;
import com.example.app.todo.repository.custom.CustomTodoRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TodoRepository extends JpaRepository<Todo, UUID>, CustomTodoRepository {

    Optional<Todo> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<Todo> findByIdInAndOwnerId(Collection<UUID> ids, UUID ownerId);

    /**
     * Delete guard: the owner's todo, locked {@code FOR KEY SHARE} until the transaction ends, so a time
     * entry written into it can't race the todo's delete (the delete waits, then answers
     * {@code TODO_IN_USE}), while an edit of the todo, which changes no key, doesn't wait. Native, because
     * JPA's lock modes stop at {@code FOR SHARE}, which would block the edit too.
     */
    @Query(value = """
            select * from todos
             where id = :id and owner_id = :ownerId
               for key share""",
            nativeQuery = true)
    Optional<Todo> findForKeyShareByIdAndOwnerId(UUID id, UUID ownerId);

    /**
     * Owner of a write: the owner's todo, locked {@code FOR NO KEY UPDATE} (Hibernate's PostgreSQL write
     * lock) until the transaction ends, for every write that changes its member rows (tags, attachments)
     * and for its delete. A second writer with the same version waits here, before touching anything,
     * then reads the new version and answers 409, instead of colliding on a member row; and the todo is
     * always locked before its member rows, so two such writes, or one and the todo's delete, can't
     * deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Todo> findForUpdateByIdAndOwnerId(UUID id, UUID ownerId);
}
