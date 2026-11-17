package com.example.app.user.repository.impl;

import com.example.app.common.pagination.OffsetPages;
import com.example.app.common.pagination.TextSearch;
import com.example.app.user.domain.TenantMembership;
import com.example.app.user.domain.TenantMembership_;
import com.example.app.user.domain.User;
import com.example.app.user.domain.User_;
import com.example.app.user.dto.request.TenantMemberFilter;
import com.example.app.user.dto.response.TenantMemberResponse;
import com.example.app.user.repository.custom.CustomTenantMembershipRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.query.QueryUtils;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class TenantMembershipRepositoryImpl implements CustomTenantMembershipRepository {

    private final EntityManager entityManager;

    /**
     * One tenant's members: the membership holds the role and the version, the account its email and
     * name. A platform table without a policy, so the tenant is a predicate of every statement.
     */
    @Override
    public Page<TenantMemberResponse> findByFilter(TenantMemberFilter filter, UUID tenantId, Pageable pageable) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<TenantMemberResponse> query = builder.createQuery(TenantMemberResponse.class);
        Root<TenantMembership> root = query.from(TenantMembership.class);
        Join<TenantMembership, User> user = root.join(TenantMembership_.user);
        query.select(builder.construct(TenantMemberResponse.class,
                user.get(User_.id), user.get(User_.email), user.get(User_.fullName),
                root.get(TenantMembership_.role), root.get(TenantMembership_.version),
                root.get(TenantMembership_.createdAt), root.get(TenantMembership_.updatedAt),
                root.get(TenantMembership_.createdBy), root.get(TenantMembership_.updatedBy)));
        query.where(matching(builder, root, user, tenantId, filter).toArray(new Predicate[0]));
        query.orderBy(QueryUtils.toOrders(pageable.getSort(), root, builder));

        CriteriaQuery<Long> countQuery = builder.createQuery(Long.class);
        Root<TenantMembership> countRoot = countQuery.from(TenantMembership.class);
        Join<TenantMembership, User> countUser = countRoot.join(TenantMembership_.user);
        countQuery.select(builder.count(countRoot));
        countQuery.where(matching(builder, countRoot, countUser, tenantId, filter).toArray(new Predicate[0]));

        return OffsetPages.page(entityManager.createQuery(query), entityManager.createQuery(countQuery), pageable);
    }

    private static List<Predicate> matching(CriteriaBuilder builder, Root<TenantMembership> root,
                                            Join<TenantMembership, User> user, UUID tenantId, TenantMemberFilter filter) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(builder.equal(root.get(TenantMembership_.tenantId), tenantId));
        if (filter.requireIdsPredicate()) {
            // The membership's own user_id column, which uq_tenant_memberships_user_id_tenant_id leads with.
            predicates.add(root.get(TenantMembership_.user).get(User_.id).in(filter.getIds()));
        }
        if (filter.requireRolePredicate()) {
            predicates.add(builder.equal(root.get(TenantMembership_.role), filter.getRole()));
        }
        if (filter.requireQueryPredicate()) {
            predicates.add(TextSearch.contains(builder, user.get(User_.email), filter.getQuery()));
        }
        return predicates;
    }
}
