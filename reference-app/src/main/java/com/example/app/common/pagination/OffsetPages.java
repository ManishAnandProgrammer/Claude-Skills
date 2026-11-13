package com.example.app.common.pagination;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.query.QueryUtils;
import org.springframework.data.support.PageableExecutionUtils;

import java.util.List;

/**
 * One offset page as a projection: only the selected columns into a row record, never entities,
 * plus the total with the same predicates. The count runs only when the page itself can't tell
 * (a full page), so most small lists cost one statement.
 */
public final class OffsetPages {

    private OffsetPages() {
    }

    @FunctionalInterface
    public interface Conditions<T> {
        List<Predicate> build(CriteriaBuilder builder, Root<T> root);
    }

    @FunctionalInterface
    public interface Projection<T, R> {
        Selection<R> select(CriteriaBuilder builder, Root<T> root);
    }

    public static <T, R> Page<R> fetch(EntityManager entityManager, Class<T> entity, Class<R> row,
                                       Projection<T, R> projection, Conditions<T> conditions, Pageable pageable) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<R> query = builder.createQuery(row);
        Root<T> root = query.from(entity);
        query.select(projection.select(builder, root));
        query.where(conditions.build(builder, root).toArray(new Predicate[0]));
        query.orderBy(QueryUtils.toOrders(pageable.getSort(), root, builder));
        List<R> content = entityManager.createQuery(query)
                .setFirstResult(Math.toIntExact(pageable.getOffset()))
                .setMaxResults(pageable.getPageSize())
                .getResultList();
        return PageableExecutionUtils.getPage(content, pageable, () -> count(entityManager, entity, conditions));
    }

    private static <T> long count(EntityManager entityManager, Class<T> entity, Conditions<T> conditions) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = builder.createQuery(Long.class);
        Root<T> root = query.from(entity);
        query.select(builder.count(root)).where(conditions.build(builder, root).toArray(new Predicate[0]));
        return entityManager.createQuery(query).getSingleResult();
    }
}
