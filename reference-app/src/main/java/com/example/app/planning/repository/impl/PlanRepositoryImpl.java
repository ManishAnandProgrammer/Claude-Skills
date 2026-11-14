package com.example.app.planning.repository.impl;

import com.example.app.common.pagination.Cursor;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.pagination.Seek;
import com.example.app.planning.domain.Plan;
import com.example.app.planning.domain.Plan_;
import com.example.app.planning.dto.internal.PlanRow;
import com.example.app.planning.dto.request.PlanFilter;
import com.example.app.planning.dto.request.PlanSort;
import com.example.app.planning.repository.custom.CustomPlanRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CompoundSelection;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
class PlanRepositoryImpl implements CustomPlanRepository {

    private final EntityManager entityManager;

    PlanRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * STARTS_ON in calendar order, NEWEST by the id alone. One owner's plans never share a day, so
     * {@code starts_on} alone would order them; the id stays last so the keyset holds for every scope.
     */
    @Override
    public CursorBatch<PlanRow> findByFilter(PlanFilter filter, UUID ownerId, int size) {
        String sort = filter.getSort().name();
        Cursor cursor = Cursor.read(filter.getAfter(), sort);

        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<PlanRow> query = builder.createQuery(PlanRow.class);
        Root<Plan> root = query.from(Plan.class);
        query.select(rowOf(builder, root));
        List<Predicate> predicates = filterPredicates(builder, root, filter);
        predicates.add(builder.equal(root.get(Plan_.ownerId), ownerId));
        if (cursor.isPresent()) {
            predicates.add(seekAfter(builder, root, filter.getSort(), cursor));
        }
        query.where(predicates.toArray(new Predicate[0]));
        query.orderBy(orderOf(builder, root, filter.getSort()));

        List<PlanRow> rows = entityManager.createQuery(query).setMaxResults(size + 1).getResultList();
        return switch (filter.getSort()) {
            case STARTS_ON -> CursorBatch.of(rows, size, row -> Cursor.write(sort, row.startsOn(), row.id()));
            case NEWEST -> CursorBatch.of(rows, size, row -> Cursor.write(sort, row.id()));
        };
    }

    private static CompoundSelection<PlanRow> rowOf(CriteriaBuilder builder, Root<Plan> root) {
        return builder.construct(PlanRow.class,
                root.get(Plan_.id), root.get(Plan_.todoId), root.get(Plan_.ownerId),
                root.get(Plan_.startsOn), root.get(Plan_.endsOn),
                root.get(Plan_.createdAt), root.get(Plan_.updatedAt), root.get(Plan_.createdBy), root.get(Plan_.updatedBy),
                root.get(Plan_.version));
    }

    /** The predicates of the filters the request sets; the caller adds the owner scope and the seek. */
    private static List<Predicate> filterPredicates(CriteriaBuilder builder, Root<Plan> root, PlanFilter filter) {
        List<Predicate> predicates = new ArrayList<>();
        if (filter.requireIdsPredicate()) {
            predicates.add(root.get(Plan_.id).in(filter.getIds()));
        }
        if (filter.requireTodoIdPredicate()) {
            predicates.add(builder.equal(root.get(Plan_.todoId), filter.getTodoId()));
        }
        return predicates;
    }

    /** The rows after the cursor's, in the sort's keyset (id last). */
    private static Predicate seekAfter(CriteriaBuilder builder, Root<Plan> root, PlanSort sort, Cursor cursor) {
        return switch (sort) {
            case STARTS_ON -> Seek.after(builder, false,
                    root.get(Plan_.startsOn), cursor.date(0), root.get(Plan_.id), cursor.uuid(1));
            case NEWEST -> builder.lessThan(root.get(Plan_.id), cursor.uuid(0));
        };
    }

    /** On the same columns as {@link #seekAfter}, in the same order. */
    private static List<Order> orderOf(CriteriaBuilder builder, Root<Plan> root, PlanSort sort) {
        return switch (sort) {
            case STARTS_ON -> Seek.order(builder, false, root.get(Plan_.startsOn), root.get(Plan_.id));
            case NEWEST -> List.of(builder.desc(root.get(Plan_.id)));
        };
    }
}
