package com.example.app.todo.repository.impl;

import com.example.app.common.pagination.Cursor;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.pagination.Seek;
import com.example.app.todo.domain.TimeEntry;
import com.example.app.todo.domain.TimeEntry_;
import com.example.app.todo.domain.Todo_;
import com.example.app.todo.dto.request.TimeEntryFilter;
import com.example.app.todo.dto.request.TimeEntrySort;
import com.example.app.todo.dto.response.TimeEntryResponse;
import com.example.app.todo.repository.custom.CustomTimeEntryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CompoundSelection;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class TimeEntryRepositoryImpl implements CustomTimeEntryRepository {

    private static final int CURSOR_KEYS = 2;

    private final EntityManager entityManager;

    @Override
    public CursorBatch<TimeEntryResponse> findByFilter(TimeEntryFilter filter, UUID todoId, UUID ownerId, int size) {
        String sort = filter.getSort().name();
        Cursor cursor = Cursor.read(filter.getAfter(), sort, CURSOR_KEYS);

        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<TimeEntryResponse> query = builder.createQuery(TimeEntryResponse.class);
        Root<TimeEntry> root = query.from(TimeEntry.class);
        query.select(rowOf(builder, root));
        List<Predicate> predicates = filterPredicates(builder, root, filter);
        predicates.add(builder.equal(root.get(TimeEntry_.todo).get(Todo_.id), todoId));
        predicates.add(builder.equal(root.get(TimeEntry_.ownerId), ownerId));
        if (cursor.hasPosition()) {
            predicates.add(seekAfter(builder, root, filter.getSort(), cursor));
        }
        query.where(predicates.toArray(new Predicate[0]));
        query.orderBy(orderOf(builder, root, filter.getSort()));

        List<TimeEntryResponse> rows = entityManager.createQuery(query).setMaxResults(size + 1).getResultList();
        return switch (filter.getSort()) {
            case SPENT_ON -> CursorBatch.of(rows, size, row -> Cursor.write(sort, row.spentOn(), row.id()));
            case MINUTES -> CursorBatch.of(rows, size, row -> Cursor.write(sort, row.minutes(), row.id()));
        };
    }

    private static Predicate seekAfter(CriteriaBuilder builder, Root<TimeEntry> root, TimeEntrySort sort, Cursor cursor) {
        return switch (sort) {
            case SPENT_ON -> Seek.after(builder, true,
                    root.get(TimeEntry_.spentOn), cursor.date(0), root.get(TimeEntry_.id), cursor.uuid(1));
            case MINUTES -> Seek.after(builder, true,
                    root.get(TimeEntry_.minutes), cursor.number(0), root.get(TimeEntry_.id), cursor.uuid(1));
        };
    }

    private static List<Order> orderOf(CriteriaBuilder builder, Root<TimeEntry> root, TimeEntrySort sort) {
        return switch (sort) {
            case SPENT_ON -> Seek.order(builder, true, root.get(TimeEntry_.spentOn), root.get(TimeEntry_.id));
            case MINUTES -> Seek.order(builder, true, root.get(TimeEntry_.minutes), root.get(TimeEntry_.id));
        };
    }

    private static CompoundSelection<TimeEntryResponse> rowOf(CriteriaBuilder builder, Root<TimeEntry> root) {
        return builder.construct(TimeEntryResponse.class,
                root.get(TimeEntry_.id), root.get(TimeEntry_.todo).get(Todo_.id), root.get(TimeEntry_.ownerId),
                root.get(TimeEntry_.minutes), root.get(TimeEntry_.spentOn), root.get(TimeEntry_.note),
                root.get(TimeEntry_.billable), root.get(TimeEntry_.createdAt), root.get(TimeEntry_.updatedAt),
                root.get(TimeEntry_.createdBy), root.get(TimeEntry_.updatedBy), root.get(TimeEntry_.version));
    }

    private static List<Predicate> filterPredicates(CriteriaBuilder builder, Root<TimeEntry> root, TimeEntryFilter filter) {
        List<Predicate> predicates = new ArrayList<>();
        if (filter.requireIdsPredicate()) {
            predicates.add(root.get(TimeEntry_.id).in(filter.getIds()));
        }
        if (filter.requireSpentOnFromPredicate()) {
            predicates.add(builder.greaterThanOrEqualTo(root.get(TimeEntry_.spentOn), filter.getSpentOnFrom()));
        }
        if (filter.requireSpentOnToPredicate()) {
            predicates.add(builder.lessThanOrEqualTo(root.get(TimeEntry_.spentOn), filter.getSpentOnTo()));
        }
        if (filter.requireMinMinutesPredicate()) {
            predicates.add(builder.greaterThanOrEqualTo(root.get(TimeEntry_.minutes), filter.getMinMinutes()));
        }
        if (filter.requireMaxMinutesPredicate()) {
            predicates.add(builder.lessThanOrEqualTo(root.get(TimeEntry_.minutes), filter.getMaxMinutes()));
        }
        if (filter.requireBillablePredicate()) {
            predicates.add(builder.equal(root.get(TimeEntry_.billable), filter.getBillable()));
        }
        return predicates;
    }
}
