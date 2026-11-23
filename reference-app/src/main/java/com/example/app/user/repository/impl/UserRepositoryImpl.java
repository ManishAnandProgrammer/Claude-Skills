package com.example.app.user.repository.impl;

import com.example.app.common.dropdown.DropdownCandidate;
import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.pagination.Cursor;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.pagination.Seek;
import com.example.app.common.pagination.TextSearch;
import com.example.app.user.api.PermissionHolders;
import com.example.app.user.domain.Role;
import com.example.app.user.domain.User;
import com.example.app.user.domain.UserStatus;
import com.example.app.user.domain.User_;
import com.example.app.user.repository.custom.CustomUserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Repository
@RequiredArgsConstructor
class UserRepositoryImpl implements CustomUserRepository {

    private final EntityManager entityManager;

    @Override
    public CursorBatch<DropdownItem> findActiveWithRoleIn(Set<Role> roles, DropdownFilter filter, int size) {
        boolean descending = filter.getSortDirection() == DropdownCandidate.SortDirection.DESC;
        String sort = PermissionHolders.sortOf(filter);
        Cursor cursor = Cursor.read(filter.getAfter(), sort, PermissionHolders.CURSOR_KEYS);
        if (roles.isEmpty()) {
            return new CursorBatch<>(List.of(), null);
        }

        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<DropdownItem> query = builder.createQuery(DropdownItem.class);
        Root<User> root = query.from(User.class);
        query.select(builder.construct(DropdownItem.class, root.get(User_.id), root.get(User_.fullName)));
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(builder.equal(root.get(User_.status), UserStatus.ACTIVE));
        predicates.add(root.get(User_.role).in(roles));
        if (filter.requireQueryPredicate()) {
            predicates.add(TextSearch.contains(builder, root.get(User_.fullName), filter.getQuery()));
        }
        if (cursor.hasPosition()) {
            predicates.add(Seek.after(builder, descending,
                    root.get(User_.fullName), cursor.text(0),
                    root.get(User_.id), cursor.uuid(1)));
        }
        query.where(predicates.toArray(new Predicate[0]));
        query.orderBy(Seek.order(builder, descending, root.get(User_.fullName), root.get(User_.id)));

        List<DropdownItem> rows = entityManager.createQuery(query).setMaxResults(size + 1).getResultList();
        return CursorBatch.of(rows, size, item -> Cursor.write(sort, item.label(), item.value()));
    }
}
