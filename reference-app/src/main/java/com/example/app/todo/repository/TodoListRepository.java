package com.example.app.todo.repository;

import com.example.app.todo.domain.TodoList;
import com.example.app.todo.repository.custom.CustomTodoListRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface TodoListRepository extends JpaRepository<TodoList, UUID>, CustomTodoListRepository {

    Optional<TodoList> findByIdAndOwnerId(UUID id, UUID ownerId);

    /**
     * Delete guard: the owner's list, locked {@code FOR KEY SHARE} until the transaction ends, so a todo
     * written into it can't race the list's delete (the delete waits, then answers
     * {@code TODO_LIST_IN_USE}), while a rename, which changes no key, doesn't wait. Native, because
     * JPA's lock modes stop at {@code FOR SHARE}, which would block the rename too.
     */
    @Query(value = """
            select * from todo_lists
             where id = :id and owner_id = :ownerId
               for key share""",
            nativeQuery = true)
    Optional<TodoList> findForKeyShareByIdAndOwnerId(UUID id, UUID ownerId);
}
