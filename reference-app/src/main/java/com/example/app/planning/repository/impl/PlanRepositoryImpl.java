package com.example.app.planning.repository.impl;

import com.example.app.common.limits.Limits;
import com.example.app.common.pagination.Cursor;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.pagination.Seek;
import com.example.app.planning.domain.Plan;
import com.example.app.planning.domain.PlanStatus;
import com.example.app.planning.domain.Plan_;
import com.example.app.planning.dto.internal.PlanDays;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class PlanRepositoryImpl implements CustomPlanRepository {

    private static final int CURSOR_KEYS = 2;

    private final EntityManager entityManager;
    private final Limits limits;

    /**
     * NEWEST is never the id alone: the primary key, which leads with the id, would then compete with the owner's
     * index, and with UUIDv7 ids in heap order the planner walks it backward through every newer row of every owner.
     */
    @Override
    public CursorBatch<PlanRow> findByFilter(PlanFilter filter, UUID ownerId, int size) {
        String sort = filter.getSort().name();
        Cursor cursor = Cursor.read(filter.getAfter(), sort, CURSOR_KEYS);

        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<PlanRow> query = builder.createQuery(PlanRow.class);
        Root<Plan> root = query.from(Plan.class);
        query.select(rowOf(builder, root));
        List<Predicate> predicates = filterPredicates(builder, root, filter, limits.get("plan.max-days"));
        predicates.add(builder.equal(root.get(Plan_.ownerId), ownerId));
        if (cursor.hasPosition()) {
            predicates.add(seekAfter(builder, root, filter.getSort(), cursor));
        }
        query.where(predicates.toArray(new Predicate[0]));
        query.orderBy(orderOf(builder, root, filter.getSort()));

        List<PlanRow> rows = entityManager.createQuery(query).setMaxResults(size + 1).getResultList();
        return switch (filter.getSort()) {
            case STARTS_ON -> CursorBatch.of(rows, size, row -> Cursor.write(sort, row.startsOn(), row.id()));
            case NEWEST -> CursorBatch.of(rows, size, row -> Cursor.write(sort, row.createdAt(), row.id()));
        };
    }

    @Override
    public List<PlanDays> findLiveDays(UUID ownerId, LocalDate from, LocalDate to) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<PlanDays> query = builder.createQuery(PlanDays.class);
        Root<Plan> root = query.from(Plan.class);
        query.select(builder.construct(PlanDays.class, root.get(Plan_.startsOn), root.get(Plan_.endsOn)));
        query.where(
                builder.equal(root.get(Plan_.ownerId), ownerId),
                builder.notEqual(root.get(Plan_.status), PlanStatus.CANCELLED),
                builder.greaterThanOrEqualTo(root.get(Plan_.startsOn), earliestStartOfAPlanEndingOnOrAfter(from, limits.get("plan.max-days"))),
                builder.lessThanOrEqualTo(root.get(Plan_.startsOn), to),
                builder.greaterThanOrEqualTo(root.get(Plan_.endsOn), from));
        query.orderBy(builder.asc(root.get(Plan_.startsOn)));
        return entityManager.createQuery(query).getResultList();
    }

    private static CompoundSelection<PlanRow> rowOf(CriteriaBuilder builder, Root<Plan> root) {
        return builder.construct(PlanRow.class,
                root.get(Plan_.id), root.get(Plan_.todoId), root.get(Plan_.ownerId),
                root.get(Plan_.startsOn), root.get(Plan_.endsOn), root.get(Plan_.status), root.get(Plan_.doneOn),
                root.get(Plan_.createdAt), root.get(Plan_.updatedAt), root.get(Plan_.createdBy), root.get(Plan_.updatedBy),
                root.get(Plan_.version));
    }

    private static List<Predicate> filterPredicates(CriteriaBuilder builder, Root<Plan> root, PlanFilter filter, int maxDays) {
        List<Predicate> predicates = new ArrayList<>();
        if (filter.requireIdsPredicate()) {
            predicates.add(root.get(Plan_.id).in(filter.getIds()));
        }
        if (filter.requireTodoIdPredicate()) {
            predicates.add(builder.equal(root.get(Plan_.todoId), filter.getTodoId()));
        }
        if (filter.requirePlannedFromPredicate()) {
            predicates.add(builder.greaterThanOrEqualTo(root.get(Plan_.endsOn), filter.getPlannedFrom()));
            predicates.add(builder.greaterThanOrEqualTo(root.get(Plan_.startsOn), earliestStartOfAPlanEndingOnOrAfter(filter.getPlannedFrom(), maxDays)));
        }
        if (filter.requirePlannedToPredicate()) {
            predicates.add(builder.lessThanOrEqualTo(root.get(Plan_.startsOn), filter.getPlannedTo()));
        }
        return predicates;
    }

    /**
     * An {@code ends_on} bound alone has no index (every index orders by {@code starts_on}), so it would read the
     * owner's whole history; a plan spans at most {@code maxDays} days, and this bound gives the index its lower end.
     */
    private static LocalDate earliestStartOfAPlanEndingOnOrAfter(LocalDate day, int maxDays) {
        return day.minusDays(maxDays - 1L);
    }

    private static Predicate seekAfter(CriteriaBuilder builder, Root<Plan> root, PlanSort sort, Cursor cursor) {
        return switch (sort) {
            case STARTS_ON -> Seek.after(builder, false,
                    root.get(Plan_.startsOn), cursor.date(0), root.get(Plan_.id), cursor.uuid(1));
            case NEWEST -> Seek.after(builder, true,
                    root.get(Plan_.createdAt), cursor.instant(0), root.get(Plan_.id), cursor.uuid(1));
        };
    }

    private static List<Order> orderOf(CriteriaBuilder builder, Root<Plan> root, PlanSort sort) {
        return switch (sort) {
            case STARTS_ON -> Seek.order(builder, false, root.get(Plan_.startsOn), root.get(Plan_.id));
            case NEWEST -> Seek.order(builder, true, root.get(Plan_.createdAt), root.get(Plan_.id));
        };
    }
}
