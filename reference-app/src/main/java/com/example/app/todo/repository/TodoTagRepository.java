package com.example.app.todo.repository;

import com.example.app.todo.domain.TodoTag;
import com.example.app.todo.dto.internal.TodoTagRow;
import com.example.app.todo.dto.response.TodoTagResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Read models, and the todo's delete: a todo's tag rows are otherwise written through
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
