package com.example.app.tenant.repository.impl;

import com.example.app.common.pagination.OffsetPages;
import com.example.app.common.pagination.TextSearch;
import com.example.app.tenant.domain.Tenant;
import com.example.app.tenant.domain.Tenant_;
import com.example.app.tenant.dto.request.TenantFilter;
import com.example.app.tenant.dto.request.TenantSort;
import com.example.app.tenant.dto.response.TenantResponse;
import com.example.app.tenant.repository.custom.CustomTenantRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
class TenantRepositoryImpl implements CustomTenantRepository {

    private final EntityManager entityManager;

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
        query.orderBy(orderOf(builder, root, filter.getSort()));

        CriteriaQuery<Long> countQuery = builder.createQuery(Long.class);
        Root<Tenant> countRoot = countQuery.from(Tenant.class);
        countQuery.select(builder.count(countRoot));
        countQuery.where(matching(builder, countRoot, filter).toArray(new Predicate[0]));

        return OffsetPages.page(entityManager.createQuery(query), entityManager.createQuery(countQuery), pageable);
    }

    // NEWEST is never the id alone: the planner would walk the UUIDv7 primary key backward through every
    // scope's rows instead of the scope's index.
    private static List<Order> orderOf(CriteriaBuilder builder, Root<Tenant> root, TenantSort sort) {
        return switch (sort) {
            case NAME -> List.of(builder.asc(root.get(Tenant_.name)), builder.asc(root.get(Tenant_.id)));
            case NEWEST -> List.of(builder.desc(root.get(Tenant_.createdAt)), builder.desc(root.get(Tenant_.id)));
        };
    }

    private static List<Predicate> matching(CriteriaBuilder builder, Root<Tenant> root, TenantFilter filter) {
        List<Predicate> predicates = new ArrayList<>();
        if (filter.requireStatusPredicate()) {
            predicates.add(builder.equal(root.get(Tenant_.status), filter.getStatus()));
        }
        if (filter.requireQueryPredicate()) {
            predicates.add(TextSearch.contains(builder, root.get(Tenant_.name), filter.getQuery()));
        }
        return predicates;
    }
}
