package com.example.app.todo.repository.impl;

import com.example.app.common.dropdown.DropdownCandidate;
import com.example.app.common.pagination.Cursor;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.pagination.Seek;
import com.example.app.common.pagination.TextSearch;
import com.example.app.todo.domain.Tag_;
import com.example.app.todo.api.TodoView;
import com.example.app.todo.domain.Todo;
import com.example.app.todo.domain.TodoList_;
import com.example.app.todo.domain.TodoStatus;
import com.example.app.todo.domain.TodoTag;
import com.example.app.todo.domain.TodoTag_;
import com.example.app.todo.domain.Todo_;
import com.example.app.todo.dto.request.TodoDropdownFilter;
import com.example.app.todo.dto.request.TodoFilter;
import com.example.app.todo.dto.request.TodoSort;
import com.example.app.todo.dto.response.TodoSummaryResponse;
import com.example.app.todo.repository.custom.CustomTodoRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.CompoundSelection;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.PredicateSpecification;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class TodoRepositoryImpl implements CustomTodoRepository {

    /** Must equal the {@code due_sort} expression's value in changeset 0003. */
    private static final LocalDate UNDATED_SORTS_LAST = LocalDate.of(9999, 12, 31);

    private static final int CURSOR_KEYS = 2;

    private static final String TITLE_ASC = "TITLE_ASC";
    private static final String TITLE_DESC = "TITLE_DESC";

    private final EntityManager entityManager;

    /**
     * DUE_DATE sorts by {@code due_sort}, not {@code due_date}: a seek key must never be null. NEWEST is never the
     * id alone: the primary key, which leads with the id, would then compete with the owner's index, and with UUIDv7
     * ids in heap order the planner walks it backward through every newer row of every owner.
     */
    @Override
    public CursorBatch<TodoSummaryResponse> findByFilter(TodoFilter filter, PredicateSpecification<Todo> scope, int size) {
        String sort = filter.getSort().name();
        Cursor cursor = Cursor.read(filter.getAfter(), sort, CURSOR_KEYS);

        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<TodoSummaryResponse> query = builder.createQuery(TodoSummaryResponse.class);
        Root<Todo> root = query.from(Todo.class);
        query.select(summaryOf(builder, root));
        List<Predicate> predicates = filterPredicates(builder, query, root, filter);
        predicates.add(scope.toPredicate(root, builder));
        if (cursor.hasPosition()) {
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

    /** Hibernate's PostgreSQL write lock is {@code FOR NO KEY UPDATE}, as {@code @Lock(PESSIMISTIC_WRITE)} takes it. */
    @Override
    public Optional<Todo> findForUpdate(UUID id, PredicateSpecification<Todo> scope) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Todo> query = builder.createQuery(Todo.class);
        Root<Todo> root = query.from(Todo.class);
        query.where(builder.equal(root.get(Todo_.id), id), scope.toPredicate(root, builder));
        return entityManager.createQuery(query).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList().stream().findFirst();
    }

    /**
     * The list is a condition of this statement, never looked up first: a missing or another user's list answers
     * like an empty one of the caller's, so nothing tells ids apart.
     */
    @Override
    public CursorBatch<TodoView> findDropdownViews(TodoDropdownFilter filter, PredicateSpecification<Todo> scope, int size) {
        boolean descending = filter.getSortDirection() == DropdownCandidate.SortDirection.DESC;
        String sort = descending ? TITLE_DESC : TITLE_ASC;
        Cursor cursor = Cursor.read(filter.getAfter(), sort, CURSOR_KEYS);

        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<TodoView> query = builder.createQuery(TodoView.class);
        Root<Todo> root = query.from(Todo.class);
        Expression<Boolean> open = builder.<Boolean>selectCase()
                .when(builder.equal(root.get(Todo_.status), TodoStatus.OPEN), true)
                .otherwise(false);
        query.select(builder.construct(TodoView.class, root.get(Todo_.id), root.get(Todo_.title), open));
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(builder.equal(root.get(Todo_.todoList).get(TodoList_.id), filter.getTodoListId()));
        predicates.add(scope.toPredicate(root, builder));
        if (filter.requireQueryPredicate()) {
            predicates.add(TextSearch.contains(builder, root.get(Todo_.title), filter.getQuery()));
        }
        if (cursor.hasPosition()) {
            predicates.add(Seek.after(builder, descending,
                    root.get(Todo_.title), cursor.text(0),
                    root.get(Todo_.id), cursor.uuid(1)));
        }
        query.where(predicates.toArray(new Predicate[0]));
        query.orderBy(Seek.order(builder, descending, root.get(Todo_.title), root.get(Todo_.id)));

        List<TodoView> rows = entityManager.createQuery(query).setMaxResults(size + 1).getResultList();
        return CursorBatch.of(rows, size, view -> Cursor.write(sort, view.title(), view.id()));
    }

    private static Predicate seekAfter(CriteriaBuilder builder, Root<Todo> root, TodoSort sort, Cursor cursor) {
        return switch (sort) {
            case DUE_DATE -> Seek.after(builder, false,
                    root.get(Todo_.dueSort), cursor.date(0), root.get(Todo_.id), cursor.uuid(1));
            case NEWEST -> Seek.after(builder, true,
                    root.get(Todo_.createdAt), cursor.instant(0), root.get(Todo_.id), cursor.uuid(1));
        };
    }

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

    private static Predicate taggedWith(CriteriaBuilder builder, CriteriaQuery<?> query, Root<Todo> root, UUID tagId) {
        Subquery<Integer> tagged = query.subquery(Integer.class);
        Root<TodoTag> link = tagged.from(TodoTag.class);
        tagged.select(builder.literal(1)).where(
                builder.equal(link.get(TodoTag_.todo), root),
                builder.equal(link.get(TodoTag_.tag).get(Tag_.id), tagId));
        return builder.exists(tagged);
    }

    private static LocalDate dueSortOf(TodoSummaryResponse row) {
        return Objects.requireNonNullElse(row.dueDate(), UNDATED_SORTS_LAST);
    }
}
