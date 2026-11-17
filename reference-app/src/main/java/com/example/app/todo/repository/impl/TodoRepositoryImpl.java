package com.example.app.todo.repository.impl;

import com.example.app.common.pagination.Cursor;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.pagination.Seek;
import com.example.app.common.pagination.TextSearch;
import com.example.app.todo.domain.Tag_;
import com.example.app.todo.domain.Todo;
import com.example.app.todo.domain.TodoList_;
import com.example.app.todo.domain.TodoTag;
import com.example.app.todo.domain.TodoTag_;
import com.example.app.todo.domain.Todo_;
import com.example.app.todo.dto.request.TodoFilter;
import com.example.app.todo.dto.request.TodoSort;
import com.example.app.todo.dto.response.TodoSummaryResponse;
import com.example.app.todo.repository.custom.CustomTodoRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CompoundSelection;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class TodoRepositoryImpl implements CustomTodoRepository {

    /** The same value as the {@code due_sort} expression in changeset 0003. */
    private static final LocalDate UNDATED_SORTS_LAST = LocalDate.of(9999, 12, 31);

    /** Every order's keyset: its sort key, then the id. */
    private static final int CURSOR_KEYS = 2;

    private final EntityManager entityManager;

    /**
     * DUE_DATE sorts by the generated {@code due_sort} column (the due date, or 9999-12-31): a seek
     * key must never be null. NEWEST sorts by the creation time, newest first, the id last: never the id
     * alone, or the primary key (which leads with the id) competes with the owner's index, and with UUIDv7
     * ids in heap order the planner walks it backward through every newer row of every owner (DATA-10).
     */
    @Override
    public CursorBatch<TodoSummaryResponse> findByFilter(TodoFilter filter, UUID ownerId, int size) {
        String sort = filter.getSort().name();
        Cursor cursor = Cursor.read(filter.getAfter(), sort, CURSOR_KEYS);

        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<TodoSummaryResponse> query = builder.createQuery(TodoSummaryResponse.class);
        Root<Todo> root = query.from(Todo.class);
        query.select(summaryOf(builder, root));
        List<Predicate> predicates = filterPredicates(builder, query, root, filter);
        predicates.add(builder.equal(root.get(Todo_.ownerId), ownerId));
        if (cursor.isPresent()) {
            predicates.add(seekAfter(builder, root, filter.getSort(), cursor));
        }
        query.where(predicates.toArray(new Predicate[0]));
        query.orderBy(orderOf(builder, root, filter.getSort()));

        List<TodoSummaryResponse> rows = entityManager.createQuery(query).setMaxResults(size + 1).getResultList();
        return switch (filter.getSort()) {
            case DUE_DATE -> CursorBatch.of(rows, size, row -> Cursor.write(sort, dueSortOf(row), row.id()));
            case NEWEST -> CursorBatch.of(rows, size, row -> Cursor.write(sort, row.createdAt(), row.id()));
        };
    }

    /** The rows after the cursor's, in the sort's keyset (id last). */
    private static Predicate seekAfter(CriteriaBuilder builder, Root<Todo> root, TodoSort sort, Cursor cursor) {
        return switch (sort) {
            case DUE_DATE -> Seek.after(builder, false,
                    root.get(Todo_.dueSort), cursor.date(0), root.get(Todo_.id), cursor.uuid(1));
            case NEWEST -> Seek.after(builder, true,
                    root.get(Todo_.createdAt), cursor.instant(0), root.get(Todo_.id), cursor.uuid(1));
        };
    }

    /** On the same columns as {@link #seekAfter}, in the same order. */
    private static List<Order> orderOf(CriteriaBuilder builder, Root<Todo> root, TodoSort sort) {
        return switch (sort) {
            case DUE_DATE -> Seek.order(builder, false, root.get(Todo_.dueSort), root.get(Todo_.id));
            case NEWEST -> Seek.order(builder, true, root.get(Todo_.createdAt), root.get(Todo_.id));
        };
    }

    private static CompoundSelection<TodoSummaryResponse> summaryOf(CriteriaBuilder builder, Root<Todo> root) {
        return builder.construct(TodoSummaryResponse.class,
                root.get(Todo_.id), root.get(Todo_.todoList).get(TodoList_.id),
                // metadata:begin
                root.get(Todo_.number),
                // metadata:end
                root.get(Todo_.requesterId), root.get(Todo_.title),
                root.get(Todo_.status), root.get(Todo_.priority), root.get(Todo_.dueDate), root.get(Todo_.completedAt),
                root.get(Todo_.createdAt), root.get(Todo_.updatedAt), root.get(Todo_.createdBy), root.get(Todo_.updatedBy),
                root.get(Todo_.version));
    }

    /** The predicates of the filters the request sets, in a list the caller adds the owner scope and the seek to. */
    private static List<Predicate> filterPredicates(CriteriaBuilder builder, CriteriaQuery<?> query, Root<Todo> root,
                                                    TodoFilter filter) {
        List<Predicate> predicates = new ArrayList<>();
        if (filter.requireIdsPredicate()) {
            predicates.add(root.get(Todo_.id).in(filter.getIds()));
        }
        if (filter.requireTodoListIdPredicate()) {
            predicates.add(builder.equal(root.get(Todo_.todoList).get(TodoList_.id), filter.getTodoListId()));
        }
        if (filter.requireTagIdPredicate()) {
            predicates.add(taggedWith(builder, query, root, filter.getTagId()));
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

    /** An exists on the join table alone: a join would repeat rows, and the tag's own row isn't needed. */
    private static Predicate taggedWith(CriteriaBuilder builder, CriteriaQuery<?> query, Root<Todo> root, UUID tagId) {
        Subquery<Integer> tagged = query.subquery(Integer.class);
        Root<TodoTag> link = tagged.from(TodoTag.class);
        tagged.select(builder.literal(1)).where(
                builder.equal(link.get(TodoTag_.todo), root),
                builder.equal(link.get(TodoTag_.tag).get(Tag_.id), tagId));
        return builder.exists(tagged);
    }

    /** The row carries the real due date, so its sort key is derived the way {@code due_sort} is. */
    private static LocalDate dueSortOf(TodoSummaryResponse row) {
        return Objects.requireNonNullElse(row.dueDate(), UNDATED_SORTS_LAST);
    }
}
