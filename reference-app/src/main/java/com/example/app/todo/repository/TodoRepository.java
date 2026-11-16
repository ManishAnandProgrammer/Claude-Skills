package com.example.app.todo.repository;

import com.example.app.todo.api.TodoView;
import com.example.app.todo.domain.Todo;
import com.example.app.todo.repository.custom.CustomTodoRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TodoRepository extends JpaRepository<Todo, UUID>, CustomTodoRepository {

    Optional<Todo> findByIdAndOwnerId(UUID id, UUID ownerId);

    /**
     * The owner's todo with its list, in one statement (a join): what a response that shows the list's
     * name reads when the todo isn't locked. A lazy list read afterwards would be a second statement, and a
     * todo deleted with its list between the two would surface as an {@code EntityNotFoundException}, a
     * 500, instead of the todo's 404 (house-review DATA-21; TodoReferencesIntegrationTest).
     */
    @EntityGraph(attributePaths = "todoList")
    Optional<Todo> findWithTodoListByIdAndOwnerId(UUID id, UUID ownerId);

    /**
     * The views of a page's todos (another module's names for its rows), the view's columns only: a page's
     * lookup never loads whole rows for a name (house-review DATA-11).
     */
    @Query("""
            select new com.example.app.todo.api.TodoView(todo.id, todo.title,
                       case when todo.status = com.example.app.todo.domain.TodoStatus.OPEN then true else false end)
              from Todo todo
             where todo.id in :ids and todo.ownerId = :ownerId""")
    List<TodoView> findViewsByIdInAndOwnerId(Collection<UUID> ids, UUID ownerId);

    /**
     * Owner of a write: the owner's todo, locked {@code FOR NO KEY UPDATE} (Hibernate's PostgreSQL write
     * lock) until the transaction ends, for every write that changes its member rows (tags, attachments)
     * and for its delete. A second writer with the same version waits here, before touching anything,
     * then reads the new version and answers 409, instead of colliding on a member row; and the todo is
     * always locked before its member rows, so two such writes, or one and the todo's delete, can't
     * deadlock. Also taken by a plan's create and reschedule
     * ({@link com.example.app.todo.api.Todos#getOwnedTodoViewForUpdate}), for a rule no constraint can hold
     * because it reads another table: a plan is only for an open todo, so the todo's completion waits for the plan.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Todo> findForUpdateByIdAndOwnerId(UUID id, UUID ownerId);
}
