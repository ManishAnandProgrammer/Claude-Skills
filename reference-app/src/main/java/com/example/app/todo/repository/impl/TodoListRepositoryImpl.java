package com.example.app.todo.repository.impl;

import com.example.app.common.dropdown.DropdownCandidate;
import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.pagination.Cursor;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.pagination.OffsetPages;
import com.example.app.common.pagination.QueryFilter;
import com.example.app.common.pagination.Seek;
import com.example.app.common.pagination.TextSearch;
import com.example.app.todo.domain.TodoList;
import com.example.app.todo.domain.TodoList_;
import com.example.app.todo.dto.request.TodoListFilter;
import com.example.app.todo.dto.response.TodoListResponse;
import com.example.app.todo.repository.custom.CustomTodoListRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.query.QueryUtils;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
class TodoListRepositoryImpl implements CustomTodoListRepository {

    /** The cursor names the direction it was taken in, so it is refused in the other. */
    private static final String NAME_ASC = "NAME_ASC";
    private static final String NAME_DESC = "NAME_DESC";

    private final EntityManager entityManager;

    TodoListRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Page<TodoListResponse> findByFilter(TodoListFilter filter, UUID ownerId, Pageable pageable) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<TodoListResponse> query = builder.createQuery(TodoListResponse.class);
        Root<TodoList> root = query.from(TodoList.class);
        query.select(builder.construct(TodoListResponse.class,
                root.get(TodoList_.id), root.get(TodoList_.name), root.get(TodoList_.ownerId),
                root.get(TodoList_.createdAt), root.get(TodoList_.updatedAt),
                root.get(TodoList_.createdBy), root.get(TodoList_.updatedBy), root.get(TodoList_.version)));
        query.where(ownedAndMatching(builder, root, ownerId, filter).toArray(new Predicate[0]));
        query.orderBy(QueryUtils.toOrders(pageable.getSort(), root, builder));

        CriteriaQuery<Long> countQuery = builder.createQuery(Long.class);
        Root<TodoList> countRoot = countQuery.from(TodoList.class);
        countQuery.select(builder.count(countRoot));
        countQuery.where(ownedAndMatching(builder, countRoot, ownerId, filter).toArray(new Predicate[0]));

        return OffsetPages.page(entityManager.createQuery(query), entityManager.createQuery(countQuery), pageable);
    }

    /** Scrolls by name, then id. */
    @Override
    public CursorBatch<DropdownItem> findDropdownItems(DropdownFilter filter, UUID ownerId, int size) {
        boolean descending = filter.getSortDirection() == DropdownCandidate.SortDirection.DESC;
        String sort = descending ? NAME_DESC : NAME_ASC;
        Cursor cursor = Cursor.read(filter.getAfter(), sort);

        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<DropdownItem> query = builder.createQuery(DropdownItem.class);
        Root<TodoList> root = query.from(TodoList.class);
        query.select(builder.construct(DropdownItem.class, root.get(TodoList_.id), root.get(TodoList_.name)));
        List<Predicate> predicates = ownedAndMatching(builder, root, ownerId, filter);
        if (cursor.isPresent()) {
            predicates.add(Seek.after(builder, descending,
                    root.get(TodoList_.name), cursor.text(0),
                    root.get(TodoList_.id), cursor.uuid(1)));
        }
        query.where(predicates.toArray(new Predicate[0]));
        query.orderBy(Seek.order(builder, descending, root.get(TodoList_.name), root.get(TodoList_.id)));

        List<DropdownItem> rows = entityManager.createQuery(query).setMaxResults(size + 1).getResultList();
        return CursorBatch.of(rows, size, item -> Cursor.write(sort, item.label(), item.value()));
    }

    /** Shared by the page and the dropdown, so both list exactly what the caller may read. */
    private static List<Predicate> ownedAndMatching(CriteriaBuilder builder, Root<TodoList> root, UUID ownerId,
                                                    QueryFilter filter) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(builder.equal(root.get(TodoList_.ownerId), ownerId));
        if (filter.requireQueryPredicate()) {
            predicates.add(TextSearch.contains(builder, root.get(TodoList_.name), filter.getQuery()));
        }
        return predicates;
    }
}
