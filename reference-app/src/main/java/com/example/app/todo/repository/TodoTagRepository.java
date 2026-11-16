package com.example.app.todo.repository;

import com.example.app.todo.domain.TodoTag;
import com.example.app.todo.dto.internal.TodoTagRow;
import com.example.app.todo.dto.response.TodoTagResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Read models, the todo's delete and a tag merge's move: a todo's tag rows are otherwise written through
 * {@code Todo.replaceTags}.
 */
public interface TodoTagRepository extends JpaRepository<TodoTag, UUID> {

    /**
     * Every tag row of the todo, in one statement whatever is left of them: a tag's delete may have just
     * removed some by cascade. Before the todo's own delete, whose cascade then finds none. A bulk
     * delete skips auditing and versions, which is right for rows that are going away.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from TodoTag link where link.todo.id = :todoId")
    int deleteAllOfTodo(UUID todoId);

    /**
     * A tag merge's move: the target on every todo carrying the source and not the target yet, one statement
     * whatever their number. Rows written outside Hibernate, so the statement names what it would fill: a
     * UUIDv7 id, the audit columns, version 0. The merge holds every such todo's lock, so no other writer adds
     * the target to one of them meanwhile (the unique key never trips).
     */
    // tenancy-row:begin
    // @Modifying
    // @Query(nativeQuery = true, value = """
    //         insert into todo_tags (id, tenant_id, todo_id, tag_id, owner_id, created_at, updated_at, created_by, updated_by, version)
    //         select uuidv7(), :tenantId, link.todo_id, :targetTagId, link.owner_id, :now, :now, :userId, :userId, 0
    //           from todo_tags link
    //          where link.tenant_id = :tenantId and link.tag_id = :sourceTagId and link.owner_id = :ownerId
    //            and not exists (select 1 from todo_tags carried
    //                             where carried.tenant_id = :tenantId and carried.todo_id = link.todo_id
    //                               and carried.tag_id = :targetTagId)""")
    // int addTargetWhereSourceIs(UUID tenantId, UUID sourceTagId, UUID targetTagId, UUID ownerId, Instant now, UUID userId);
    // tenancy-row:else
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into todo_tags (id, todo_id, tag_id, owner_id, created_at, updated_at, created_by, updated_by, version)
            select uuidv7(), link.todo_id, :targetTagId, link.owner_id, :now, :now, :userId, :userId, 0
              from todo_tags link
             where link.tag_id = :sourceTagId and link.owner_id = :ownerId
               and not exists (select 1 from todo_tags carried
                                where carried.todo_id = link.todo_id and carried.tag_id = :targetTagId)""")
    int addTargetWhereSourceIs(UUID sourceTagId, UUID targetTagId, UUID ownerId, Instant now, UUID userId);
    // tenancy-row:end

    /** A tag merge's last step on the todos: the source's rows go, its todos now carry the target. */
    @Modifying
    @Query("delete from TodoTag link where link.tag.id = :tagId and link.ownerId = :ownerId")
    int deleteAllOfTag(UUID tagId, UUID ownerId);

    /** In tag id order (creation order), the order of {@link #findTagRowsOfTodos}. */
    @Query("""
            select new com.example.app.todo.dto.response.TodoTagResponse(tag.id, tag.name)
              from TodoTag link join link.tag tag
             where link.todo.id = :todoId
             order by tag.id""")
    List<TodoTagResponse> findTagsOfTodo(UUID todoId);

    @Query("""
            select new com.example.app.todo.dto.internal.TodoTagRow(link.todo.id, tag.id, tag.name)
              from TodoTag link join link.tag tag
             where link.todo.id in :todoIds
             order by tag.id""")
    List<TodoTagRow> findTagRowsOfTodos(Collection<UUID> todoIds);
}
