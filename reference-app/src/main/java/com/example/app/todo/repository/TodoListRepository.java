package com.example.app.todo.repository;

import com.example.app.todo.domain.TodoList;
import com.example.app.todo.dto.internal.TodoListName;
import com.example.app.todo.repository.custom.CustomTodoListRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TodoListRepository extends JpaRepository<TodoList, UUID>, CustomTodoListRepository {

    Optional<TodoList> findByIdAndOwnerId(UUID id, UUID ownerId);

    /** The names of a page's lists: the two columns, never the lists' whole rows (DATA-11). */
    @Query("""
            select new com.example.app.todo.dto.internal.TodoListName(list.id, list.name)
              from TodoList list
             where list.id in :ids and list.ownerId = :ownerId""")
    List<TodoListName> findNamesByIdInAndOwnerId(Collection<UUID> ids, UUID ownerId);
}
