package com.example.app.user.repository.impl;

import com.example.app.common.pagination.OffsetPages;
import com.example.app.common.pagination.TextSearch;
import com.example.app.user.domain.AccessRole;
import com.example.app.user.domain.AccessRole_;
import com.example.app.user.dto.request.AccessRoleFilter;
import com.example.app.user.dto.response.AccessRoleSummaryResponse;
import com.example.app.user.repository.custom.CustomAccessRoleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.query.QueryUtils;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
class AccessRoleRepositoryImpl implements CustomAccessRoleRepository {

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
