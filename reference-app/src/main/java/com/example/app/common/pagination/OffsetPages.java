package com.example.app.common.pagination;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.query.QueryUtils;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One offset page as a projection, plus the total with the same predicates. The count runs only
 * when the page itself can't tell (a full page), so most small lists cost one statement.
 */
@Component
public class OffsetPages {

    private final EntityManager entityManager;

    public OffsetPages(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public <T, R> Page<R> fetch(ProjectionQuery<T, R> projectionQuery, Pageable pageable) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<R> query = builder.createQuery(projectionQuery.row());
        Root<T> root = query.from(projectionQuery.entity());
        query.select(projectionQuery.projection().select(builder, root));
        query.where(projectionQuery.conditions().build(builder, root, query).toArray(new Predicate[0]));
        query.orderBy(QueryUtils.toOrders(pageable.getSort(), root, builder));
        List<R> content = entityManager.createQuery(query)
                .setFirstResult(Math.toIntExact(pageable.getOffset()))
                .setMaxResults(pageable.getPageSize())
                .getResultList();
        return PageableExecutionUtils.getPage(content, pageable, () -> count(projectionQuery));
    }

    private <T> long count(ProjectionQuery<T, ?> projectionQuery) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = builder.createQuery(Long.class);
        Root<T> root = query.from(projectionQuery.entity());
        query.select(builder.count(root)).where(projectionQuery.conditions().build(builder, root, query).toArray(new Predicate[0]));
        return entityManager.createQuery(query).getSingleResult();
    }
}
