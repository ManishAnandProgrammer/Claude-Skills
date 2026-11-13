package com.example.app.todo.repository.impl;

import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.pagination.KeyColumn;
import com.example.app.common.pagination.Keyset;
import com.example.app.common.pagination.KeysetPages;
import com.example.app.common.pagination.ProjectionQuery;
import com.example.app.common.pagination.TextSearch;
import com.example.app.todo.domain.Todo;
import com.example.app.todo.domain.TodoList_;
import com.example.app.todo.domain.Todo_;
import com.example.app.todo.dto.request.TodoFilter;
import com.example.app.todo.dto.response.TodoSummaryResponse;
import com.example.app.todo.repository.custom.CustomTodoRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
class TodoRepositoryImpl implements CustomTodoRepository {

    private static final KeyColumn<Todo, TodoSummaryResponse, UUID> ID =
            new KeyColumn<>(root -> root.get(Todo_.id), TodoSummaryResponse::id, UUID.class);
    /**
     * Sorted by the generated {@code due_sort} column (the due date, or 9999-12-31): a keyset key must
     * never be null. The row carries the real due date, so the sort key is derived the same way.
     */
    private static final Keyset<Todo, TodoSummaryResponse> BY_DUE_DATE = new Keyset<>("DUE_DATE", false, List.of(
            new KeyColumn<>(root -> root.get(Todo_.dueSort), TodoRepositoryImpl::dueSortOf, LocalDate.class),
            ID));
    private static final Keyset<Todo, TodoSummaryResponse> NEWEST = new Keyset<>("NEWEST", true, List.of(ID));
    /** The same value as the {@code due_sort} expression in changeset 0003. */
    private static final LocalDate UNDATED_SORTS_LAST = LocalDate.of(9999, 12, 31);

    private final KeysetPages keysetPages;

    TodoRepositoryImpl(KeysetPages keysetPages) {
        this.keysetPages = keysetPages;
    }

    @Override
    public CursorBatch<TodoSummaryResponse> findByFilter(TodoFilter filter, UUID ownerId, int size) {
        Keyset<Todo, TodoSummaryResponse> keyset = switch (filter.getSort()) {
            case DUE_DATE -> BY_DUE_DATE;
            case NEWEST -> NEWEST;
        };
        return keysetPages.fetch(new ProjectionQuery<>(Todo.class, TodoSummaryResponse.class, TodoRepositoryImpl::summaryRow,
                (builder, root) -> conditions(builder, root, filter, ownerId)), keyset, filter.getAfter(), size);
    }

    private static Selection<TodoSummaryResponse> summaryRow(CriteriaBuilder builder, Root<Todo> root) {
        return builder.construct(TodoSummaryResponse.class,
                root.get(Todo_.id), root.get(Todo_.todoList).get(TodoList_.id), root.get(Todo_.title),
                root.get(Todo_.status), root.get(Todo_.priority), root.get(Todo_.dueDate), root.get(Todo_.completedAt),
                root.get(Todo_.createdAt), root.get(Todo_.updatedAt), root.get(Todo_.createdBy), root.get(Todo_.updatedBy),
                root.get(Todo_.version));
    }

    private static List<Predicate> conditions(CriteriaBuilder builder, Root<Todo> root, TodoFilter filter, UUID ownerId) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(builder.equal(root.get(Todo_.ownerId), ownerId));
        if (filter.requireTodoListIdPredicate()) {
            predicates.add(builder.equal(root.get(Todo_.todoList).get(TodoList_.id), filter.getTodoListId()));
        }
        if (filter.requireStatusPredicate()) {
            predicates.add(builder.equal(root.get(Todo_.status), filter.getStatus()));
        }
        if (filter.requirePriorityPredicate()) {
            predicates.add(builder.equal(root.get(Todo_.priority), filter.getPriority()));
        }
        if (filter.requireDueOnOrBeforePredicate()) {
            // The due_sort bound adds nothing to the result (undated rows fail the due_date one); it
            // makes the filter a range of the due-date indexes instead of a walk to the end of them.
            predicates.add(builder.lessThanOrEqualTo(root.get(Todo_.dueSort), filter.getDueOnOrBefore()));
            predicates.add(builder.lessThanOrEqualTo(root.get(Todo_.dueDate), filter.getDueOnOrBefore()));
        }
        if (filter.requireQueryPredicate()) {
            predicates.add(TextSearch.contains(builder, root.get(Todo_.title), filter.getQuery()));
        }
        return predicates;
    }

    private static LocalDate dueSortOf(TodoSummaryResponse row) {
        return Objects.requireNonNullElse(row.dueDate(), UNDATED_SORTS_LAST);
    }
}
