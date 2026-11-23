package com.example.app.todo.repository;

import com.example.app.todo.api.TodoView;
import com.example.app.todo.domain.Todo;
import com.example.app.todo.repository.custom.CustomTodoRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TodoRepository extends JpaRepository<Todo, UUID>, CustomTodoRepository {

    /**
     * The list in the same statement: read lazily afterwards, a todo deleted with its list in between would surface
     * as an {@code EntityNotFoundException}, a 500, instead of the todo's 404.
     */
    @EntityGraph(attributePaths = "todoList")
    Optional<Todo> findWithTodoListById(UUID id);

    @Query("""
            select new com.example.app.todo.api.TodoView(todo.id, todo.title,
                       case when todo.status = com.example.app.todo.domain.TodoStatus.OPEN then true else false end)
              from Todo todo
             where todo.id in :ids and todo.ownerId = :ownerId""")
    List<TodoView> findViewsByIdInAndOwnerId(Collection<UUID> ids, UUID ownerId);

    /**
     * The write lock {@link CustomTodoRepository#findForUpdate} takes, in id order: two merges whose tags share
     * todos lock them in the same order and queue instead of deadlocking.
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
     * A bulk update bypasses Hibernate's versioning and auditing, so it sets them itself; the merge loaded none of
     * these todos, so no managed copy goes stale.
     */
    @Modifying
    @Query("""
            update Todo todo
               set todo.version = todo.version + 1, todo.updatedAt = :now, todo.updatedBy = :updatedBy
             where todo.ownerId = :ownerId
               and todo.id in (select link.todo.id from TodoTag link where link.tag.id = :tagId)""")
    int bumpVersionsOfAllCarryingTag(UUID tagId, UUID ownerId, Instant now, UUID updatedBy);
}
