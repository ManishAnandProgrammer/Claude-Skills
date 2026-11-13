package com.example.app.todo.repository.impl;

import com.example.app.common.dropdown.DropdownCandidate;
import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.pagination.KeyColumn;
import com.example.app.common.pagination.Keyset;
import com.example.app.common.pagination.KeysetPages;
import com.example.app.common.pagination.OffsetPages;
import com.example.app.common.pagination.TextSearch;
import com.example.app.common.wrapper.CursorWrapper;
import com.example.app.todo.domain.TodoList;
import com.example.app.todo.domain.TodoList_;
import com.example.app.todo.dto.request.TodoListFilter;
import com.example.app.todo.dto.response.TodoListResponse;
import com.example.app.todo.repository.custom.CustomTodoListRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
class TodoListRepositoryImpl implements CustomTodoListRepository {

    /** The dropdown scrolls by label, then id; the cursor names the direction it was taken in. */
    private static final List<KeyColumn<TodoList, DropdownItem, ?>> DROPDOWN_KEYS = List.of(
            new KeyColumn<>(root -> root.get(TodoList_.name), DropdownItem::label, String.class),
            new KeyColumn<>(root -> root.get(TodoList_.id), item -> (UUID) item.value(), UUID.class));
    private static final Keyset<TodoList, DropdownItem> DROPDOWN_ASC = new Keyset<>("NAME_ASC", false, DROPDOWN_KEYS);
    private static final Keyset<TodoList, DropdownItem> DROPDOWN_DESC = new Keyset<>("NAME_DESC", true, DROPDOWN_KEYS);

    private final EntityManager entityManager;
    private final KeysetPages keysetPages;

    TodoListRepositoryImpl(EntityManager entityManager, KeysetPages keysetPages) {
        this.entityManager = entityManager;
        this.keysetPages = keysetPages;
    }

    @Override
    public Page<TodoListResponse> findByFilter(TodoListFilter filter, UUID ownerId, Pageable pageable) {
        return OffsetPages.fetch(entityManager, TodoList.class, TodoListResponse.class, TodoListRepositoryImpl::listRow,
                (builder, root) -> ownedAndMatching(builder, root, ownerId, filter.getQuery()), pageable);
    }

    @Override
    public CursorWrapper<DropdownItem> findDropdownItems(DropdownFilter filter, UUID ownerId, int size) {
        Keyset<TodoList, DropdownItem> keyset = filter.getSortDirection() == DropdownCandidate.SortDirection.DESC
                ? DROPDOWN_DESC
                : DROPDOWN_ASC;
        return keysetPages.fetch(entityManager, TodoList.class, DropdownItem.class,
                (builder, root) -> builder.construct(DropdownItem.class, root.get(TodoList_.id), root.get(TodoList_.name)),
                (builder, root) -> ownedAndMatching(builder, root, ownerId, filter.getQuery()),
                keyset, filter.getAfter(), size);
    }

    private static Selection<TodoListResponse> listRow(CriteriaBuilder builder, Root<TodoList> root) {
        return builder.construct(TodoListResponse.class,
                root.get(TodoList_.id), root.get(TodoList_.name), root.get(TodoList_.ownerId),
                root.get(TodoList_.createdAt), root.get(TodoList_.updatedAt),
                root.get(TodoList_.createdBy), root.get(TodoList_.updatedBy), root.get(TodoList_.version));
    }

    private static List<Predicate> ownedAndMatching(CriteriaBuilder builder, Root<TodoList> root, UUID ownerId, String query) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(builder.equal(root.get(TodoList_.ownerId), ownerId));
        if (StringUtils.hasText(query)) {
            predicates.add(TextSearch.contains(builder, root.get(TodoList_.name), query));
        }
        return predicates;
    }
}
