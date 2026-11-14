package com.example.app.todo.repository;

import com.example.app.todo.domain.TodoTag;
import com.example.app.todo.dto.internal.TodoTagRow;
import com.example.app.todo.dto.response.TodoTagResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Read models only: a todo's tag rows are written through {@code Todo.replaceTags}. */
public interface TodoTagRepository extends JpaRepository<TodoTag, UUID> {

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
