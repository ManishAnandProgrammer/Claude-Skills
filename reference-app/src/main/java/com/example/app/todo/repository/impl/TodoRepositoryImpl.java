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
import com.example.app.todo.dto.response.TodoSummaryResponse;
import com.example.app.todo.repository.custom.CustomTodoRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
class TodoRepositoryImpl implements CustomTodoRepository {

    /** The same value as the {@code due_sort} expression in changeset 0003. */
    private static final LocalDate UNDATED_SORTS_LAST = LocalDate.of(9999, 12, 31);

    private final EntityManager entityManager;

    TodoRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * DUE_DATE sorts by the generated {@code due_sort} column (the due date, or 9999-12-31): a seek
     * key must never be null. NEWEST sorts by the id alone, newest first.
     */
    @Override
    public CursorBatch<TodoSummaryResponse> findByFilter(TodoFilter filter, UUID ownerId, int size) {
        String sort = filter.getSort().name();
        Cursor cursor = Cursor.read(filter.getAfter(), sort);

        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<TodoSummaryResponse> query = builder.createQuery(TodoSummaryResponse.class);
        Root<Todo> root = query.from(Todo.class);
        query.select(builder.construct(TodoSummaryResponse.class,
                root.get(Todo_.id), root.get(Todo_.todoList).get(TodoList_.id), root.get(Todo_.title),
                root.get(Todo_.status), root.get(Todo_.priority), root.get(Todo_.dueDate), root.get(Todo_.completedAt),
                root.get(Todo_.createdAt), root.get(Todo_.updatedAt), root.get(Todo_.createdBy), root.get(Todo_.updatedBy),
                root.get(Todo_.version)));

        List<Predicate> predicates = new ArrayList<>();
        predicates.add(builder.equal(root.get(Todo_.ownerId), ownerId));
        if (filter.requireTodoListIdPredicate()) {
            predicates.add(builder.equal(root.get(Todo_.todoList).get(TodoList_.id), filter.getTodoListId()));
        }
        if (filter.requireTagIdPredicate()) {
            // An exists on the join table alone: a join would repeat rows, and the tag's own row isn't needed.
            Subquery<Integer> tagged = query.subquery(Integer.class);
            Root<TodoTag> link = tagged.from(TodoTag.class);
            tagged.select(builder.literal(1)).where(
                    builder.equal(link.get(TodoTag_.todo), root),
                    builder.equal(link.get(TodoTag_.tag).get(Tag_.id), filter.getTagId()));
            predicates.add(builder.exists(tagged));
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
        switch (filter.getSort()) {
            case DUE_DATE -> {
                if (cursor.isPresent()) {
                    predicates.add(Seek.after(builder, false,
                            root.get(Todo_.dueSort), cursor.date(0),
                            root.get(Todo_.id), cursor.uuid(1)));
                }
                query.orderBy(Seek.order(builder, false, root.get(Todo_.dueSort), root.get(Todo_.id)));
            }
            case NEWEST -> {
                if (cursor.isPresent()) {
                    predicates.add(builder.lessThan(root.get(Todo_.id), cursor.uuid(0)));
                }
                query.orderBy(builder.desc(root.get(Todo_.id)));
            }
        }
        query.where(predicates.toArray(new Predicate[0]));

        List<TodoSummaryResponse> rows = entityManager.createQuery(query).setMaxResults(size + 1).getResultList();
        return switch (filter.getSort()) {
            case DUE_DATE -> CursorBatch.of(rows, size, row -> Cursor.write(sort, dueSortOf(row), row.id()));
            case NEWEST -> CursorBatch.of(rows, size, row -> Cursor.write(sort, row.id()));
        };
    }

    /** The row carries the real due date, so its sort key is derived the way {@code due_sort} is. */
    private static LocalDate dueSortOf(TodoSummaryResponse row) {
        return Objects.requireNonNullElse(row.dueDate(), UNDATED_SORTS_LAST);
    }
}
