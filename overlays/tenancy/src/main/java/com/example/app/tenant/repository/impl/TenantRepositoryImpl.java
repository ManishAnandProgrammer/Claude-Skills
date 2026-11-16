package com.example.app.tenant.repository.impl;

import com.example.app.common.pagination.OffsetPages;
import com.example.app.common.pagination.TextSearch;
import com.example.app.tenant.domain.Tenant;
import com.example.app.tenant.domain.Tenant_;
import com.example.app.tenant.dto.request.TenantFilter;
import com.example.app.tenant.dto.response.TenantResponse;
import com.example.app.tenant.repository.custom.CustomTenantRepository;
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

@Repository
class TenantRepositoryImpl implements CustomTenantRepository {

    private final EntityManager entityManager;

    TenantRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Page<TenantResponse> findByFilter(TenantFilter filter, Pageable pageable) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<TenantResponse> query = builder.createQuery(TenantResponse.class);
        Root<Tenant> root = query.from(Tenant.class);
        query.select(builder.construct(TenantResponse.class,
                root.get(Tenant_.id), root.get(Tenant_.name), root.get(Tenant_.status),
                root.get(Tenant_.createdAt), root.get(Tenant_.updatedAt),
                root.get(Tenant_.createdBy), root.get(Tenant_.updatedBy), root.get(Tenant_.version)));
        query.where(matching(builder, root, filter).toArray(new Predicate[0]));
        query.orderBy(QueryUtils.toOrders(pageable.getSort(), root, builder));

        CriteriaQuery<Long> countQuery = builder.createQuery(Long.class);
        Root<Tenant> countRoot = countQuery.from(Tenant.class);
        countQuery.select(builder.count(countRoot));
        countQuery.where(matching(builder, countRoot, filter).toArray(new Predicate[0]));

        return OffsetPages.page(entityManager.createQuery(query), entityManager.createQuery(countQuery), pageable);
    }

    /**
     * The status as a SQL literal ({@code status = 'PENDING'}), not a bound parameter: each rare value has a
     * partial index per order ({@code WHERE status = 'PENDING'}, changeset 0030), which only a literal matches
     * once pgjdbc's prepared statement switches to a generic plan. Safe to inline: an enum constant's name.
     */
    private static List<Predicate> matching(CriteriaBuilder builder, Root<Tenant> root, TenantFilter filter) {
        List<Predicate> predicates = new ArrayList<>();
        if (filter.requireStatusPredicate()) {
            predicates.add(builder.equal(root.get(Tenant_.status), builder.literal(filter.getStatus())));
        }
        if (filter.requireQueryPredicate()) {
            predicates.add(TextSearch.contains(builder, root.get(Tenant_.name), filter.getQuery()));
        }
        return predicates;
    }
}
