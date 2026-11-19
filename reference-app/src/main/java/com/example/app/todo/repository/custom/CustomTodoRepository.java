package com.example.app.todo.repository.custom;

import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.todo.domain.Todo;
import com.example.app.todo.dto.request.TodoDropdownFilter;
import com.example.app.todo.dto.request.TodoFilter;
import com.example.app.todo.dto.response.TodoSummaryResponse;
import org.springframework.data.jpa.domain.PredicateSpecification;

import java.util.Optional;
import java.util.UUID;

public interface CustomTodoRepository {

    /** {@code scope} is the caller's reach (TodoPolicy.listScope), one more condition of the same query. */
    CursorBatch<TodoSummaryResponse> findByFilter(TodoFilter filter, PredicateSpecification<Todo> scope, int size);

    /**
     * A write's todo, if {@code scope} allows it (TodoPolicy.editScope), locked {@code FOR NO KEY UPDATE}
     * (Hibernate's PostgreSQL write lock) until the transaction ends, for every write that changes its member
     * rows (tags, attachments) and for its delete; a row outside the scope is never locked, since the condition
     * is the statement's own. A second writer with the same version waits here, before touching anything,
     * then reads the new version and answers 409, instead of colliding on a member row; and the todo is
     * always locked before its member rows, so two such writes, or one and the todo's delete, can't
     * deadlock. Also taken by a plan's create and reschedule
     * ({@link com.example.app.todo.api.Todos#getOwnedTodoViewForUpdate}), for a rule no constraint can hold
     * because it reads another table: a plan is only for an open todo, so the todo's completion waits for the plan.
     */
    Optional<Todo> findForUpdate(UUID id, PredicateSpecification<Todo> scope);

    /**
     * One batch of the todo dropdown, in one statement: {@code scope} is the reach of the action the options serve
     * (TodoPolicy.listScope for {@code OWN}), and the parent list one more condition of the same query.
     */
    CursorBatch<DropdownItem> findDropdownItems(TodoDropdownFilter filter, PredicateSpecification<Todo> scope, int size);
}
