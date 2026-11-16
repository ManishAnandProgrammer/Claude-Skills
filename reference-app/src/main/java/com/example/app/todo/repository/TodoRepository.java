package com.example.app.todo.repository;

import com.example.app.todo.api.TodoView;
import com.example.app.todo.domain.Todo;
import com.example.app.todo.repository.custom.CustomTodoRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
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

    /**
     * A tag merge's lock on every todo carrying the tag, the write lock {@link #findForUpdateByIdAndOwnerId}
     * takes, in id order: two merges whose tags share todos lock them in the same order and queue instead of
     * deadlocking, and each todo's own writes wait for the merge. One statement whatever their number; the
     * rows stay in the database (only their count comes back).
     */
    // tenancy-row:begin
    // @Query(nativeQuery = true, value = """
    //         select count(*) from (
    //             select todo.id from todos todo
    //              where todo.tenant_id = :tenantId and todo.owner_id = :ownerId
    //                and todo.id in (select link.todo_id from todo_tags link
    //                                 where link.tenant_id = :tenantId and link.tag_id = :tagId and link.owner_id = :ownerId)
    //              order by todo.id
    //                for no key update) locked""")
    // long lockAllCarryingTag(UUID tenantId, UUID tagId, UUID ownerId);
    // tenancy-row:else
    @Query(nativeQuery = true, value = """
            select count(*) from (
                select todo.id from todos todo
                 where todo.owner_id = :ownerId
                   and todo.id in (select link.todo_id from todo_tags link
                                    where link.tag_id = :tagId and link.owner_id = :ownerId)
                 order by todo.id
                   for no key update) locked""")
    long lockAllCarryingTag(UUID tagId, UUID ownerId);
    // tenancy-row:end

    /**
     * A tag merge changes which tags these todos carry, so their versions move, as a tag replace moves one: a
     * client holding an older copy is refused 409. A bulk update, so it sets {@code version} and the audit
     * columns itself; the merge loaded none of these todos, so no managed copy goes stale.
     */
    @Modifying
    @Query("""
            update Todo todo
               set todo.version = todo.version + 1, todo.updatedAt = :now, todo.updatedBy = :updatedBy
             where todo.ownerId = :ownerId
               and todo.id in (select link.todo.id from TodoTag link where link.tag.id = :tagId)""")
    int bumpVersionsOfAllCarryingTag(UUID tagId, UUID ownerId, Instant now, UUID updatedBy);
}
