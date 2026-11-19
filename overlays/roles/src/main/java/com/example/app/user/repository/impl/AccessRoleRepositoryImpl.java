package com.example.app.user.repository.impl;

import com.example.app.common.dropdown.DropdownCandidate;
import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.pagination.Cursor;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.pagination.OffsetPages;
import com.example.app.common.pagination.Seek;
import com.example.app.common.pagination.TextSearch;
import com.example.app.user.domain.AccessRole;
import com.example.app.user.domain.AccessRole_;
import com.example.app.user.domain.RolePermission;
import com.example.app.user.domain.RolePermission_;
import com.example.app.user.dto.request.AccessRoleFilter;
import com.example.app.user.dto.response.AccessRoleSummaryResponse;
import com.example.app.user.repository.custom.CustomAccessRoleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.query.QueryUtils;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class AccessRoleRepositoryImpl implements CustomAccessRoleRepository {

    /** The cursor names the direction it was taken in, so it is refused in the other. */
    private static final String NAME_ASC = "NAME_ASC";
    private static final String NAME_DESC = "NAME_DESC";

    /** The dropdown's keyset: the name, then the id. */
    private static final int CURSOR_KEYS = 2;

    private final EntityManager entityManager;

    @Override
    public Page<AccessRoleSummaryResponse> findByFilter(AccessRoleFilter filter, Pageable pageable) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<AccessRoleSummaryResponse> query = builder.createQuery(AccessRoleSummaryResponse.class);
        Root<AccessRole> root = query.from(AccessRole.class);
        query.select(builder.construct(AccessRoleSummaryResponse.class,
                root.get(AccessRole_.id), root.get(AccessRole_.code), root.get(AccessRole_.name), root.get(AccessRole_.builtIn),
                root.get(AccessRole_.createdAt), root.get(AccessRole_.updatedAt),
                root.get(AccessRole_.createdBy), root.get(AccessRole_.updatedBy), root.get(AccessRole_.version)));
        query.where(pageConditions(builder, root, filter).toArray(new Predicate[0]));
        query.orderBy(QueryUtils.toOrders(pageable.getSort(), root, builder));

        CriteriaQuery<Long> countQuery = builder.createQuery(Long.class);
        Root<AccessRole> countRoot = countQuery.from(AccessRole.class);
        countQuery.select(builder.count(countRoot));
        countQuery.where(pageConditions(builder, countRoot, filter).toArray(new Predicate[0]));

        return OffsetPages.page(entityManager.createQuery(query), entityManager.createQuery(countQuery), pageable);
    }

    /** Scrolls by name, then id (idx_roles_name_id); the role's permissions are a NOT EXISTS on their key. */
    @Override
    public CursorBatch<DropdownItem> findDropdownItems(DropdownFilter filter, Collection<String> held, int size) {
        boolean descending = filter.getSortDirection() == DropdownCandidate.SortDirection.DESC;
        String sort = descending ? NAME_DESC : NAME_ASC;
        Cursor cursor = Cursor.read(filter.getAfter(), sort, CURSOR_KEYS);

        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<DropdownItem> query = builder.createQuery(DropdownItem.class);
        Root<AccessRole> root = query.from(AccessRole.class);
        query.select(builder.construct(DropdownItem.class, root.get(AccessRole_.id), root.get(AccessRole_.name)));
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(givableWith(builder, query, root, held));
        if (filter.requireQueryPredicate()) {
            predicates.add(TextSearch.contains(builder, root.get(AccessRole_.name), filter.getQuery()));
        }
        if (cursor.isPresent()) {
            predicates.add(Seek.after(builder, descending,
                    root.get(AccessRole_.name), cursor.text(0),
                    root.get(AccessRole_.id), cursor.uuid(1)));
        }
        query.where(predicates.toArray(new Predicate[0]));
        query.orderBy(Seek.order(builder, descending, root.get(AccessRole_.name), root.get(AccessRole_.id)));

        List<DropdownItem> rows = entityManager.createQuery(query).setMaxResults(size + 1).getResultList();
        return CursorBatch.of(rows, size, item -> Cursor.write(sort, item.label(), item.value()));
    }

    /** Safety rule 1 as a condition: no permission row of the role outside {@code held} (none at all when it is empty). */
    private static Predicate givableWith(CriteriaBuilder builder, CriteriaQuery<?> query, Root<AccessRole> root,
                                         Collection<String> held) {
        Subquery<UUID> beyond = query.subquery(UUID.class);
        Root<RolePermission> link = beyond.from(RolePermission.class);
        List<Predicate> conditions = new ArrayList<>();
        conditions.add(builder.equal(link.get(RolePermission_.role), root));
        if (!held.isEmpty()) {
            conditions.add(builder.not(link.get(RolePermission_.permission).in(held)));
        }
        beyond.select(link.get(RolePermission_.id)).where(conditions.toArray(new Predicate[0]));
        return builder.not(builder.exists(beyond));
    }

    /** Shared by the page's select and its count. */
    private static List<Predicate> pageConditions(CriteriaBuilder builder, Root<AccessRole> root, AccessRoleFilter filter) {
        List<Predicate> predicates = new ArrayList<>();
        if (filter.requireIdsPredicate()) {
            predicates.add(root.get(AccessRole_.id).in(filter.getIds()));
        }
        if (filter.requireQueryPredicate()) {
            predicates.add(builder.or(
                    TextSearch.contains(builder, root.get(AccessRole_.name), filter.getQuery()),
                    TextSearch.contains(builder, root.get(AccessRole_.code), filter.getQuery())));
        }
        return predicates;
    }
}
