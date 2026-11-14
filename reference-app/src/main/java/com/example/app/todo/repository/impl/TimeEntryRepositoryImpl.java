package com.example.app.todo.repository.impl;

import com.example.app.common.pagination.Cursor;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.pagination.Seek;
import com.example.app.todo.domain.TimeEntry;
import com.example.app.todo.domain.TimeEntry_;
import com.example.app.todo.domain.Todo_;
import com.example.app.todo.dto.request.TimeEntryFilter;
import com.example.app.todo.dto.response.TimeEntryResponse;
import com.example.app.todo.repository.custom.CustomTimeEntryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
class TimeEntryRepositoryImpl implements CustomTimeEntryRepository {

    private final EntityManager entityManager;

    TimeEntryRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Both orders read their index backwards: the most recent day, or the longest entry, first; ties newest first. */
    @Override
    public CursorBatch<TimeEntryResponse> findByFilter(TimeEntryFilter filter, UUID todoId, UUID ownerId, int size) {
        String sort = filter.getSort().name();
        Cursor cursor = Cursor.read(filter.getAfter(), sort);

        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<TimeEntryResponse> query = builder.createQuery(TimeEntryResponse.class);
        Root<TimeEntry> root = query.from(TimeEntry.class);
        query.select(builder.construct(TimeEntryResponse.class,
                root.get(TimeEntry_.id), root.get(TimeEntry_.todo).get(Todo_.id), root.get(TimeEntry_.ownerId),
                root.get(TimeEntry_.minutes), root.get(TimeEntry_.spentOn), root.get(TimeEntry_.note),
                root.get(TimeEntry_.billable), root.get(TimeEntry_.createdAt), root.get(TimeEntry_.updatedAt),
                root.get(TimeEntry_.createdBy), root.get(TimeEntry_.updatedBy), root.get(TimeEntry_.version)));

        List<Predicate> predicates = new ArrayList<>();
        predicates.add(builder.equal(root.get(TimeEntry_.todo).get(Todo_.id), todoId));
        predicates.add(builder.equal(root.get(TimeEntry_.ownerId), ownerId));
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
        switch (filter.getSort()) {
            case SPENT_ON -> {
                if (cursor.isPresent()) {
                    predicates.add(Seek.after(builder, true,
                            root.get(TimeEntry_.spentOn), cursor.date(0),
                            root.get(TimeEntry_.id), cursor.uuid(1)));
                }
                query.orderBy(Seek.order(builder, true, root.get(TimeEntry_.spentOn), root.get(TimeEntry_.id)));
            }
            case MINUTES -> {
                if (cursor.isPresent()) {
                    predicates.add(Seek.after(builder, true,
                            root.get(TimeEntry_.minutes), cursor.number(0),
                            root.get(TimeEntry_.id), cursor.uuid(1)));
                }
                query.orderBy(Seek.order(builder, true, root.get(TimeEntry_.minutes), root.get(TimeEntry_.id)));
            }
        }
        query.where(predicates.toArray(new Predicate[0]));

        List<TimeEntryResponse> rows = entityManager.createQuery(query).setMaxResults(size + 1).getResultList();
        return switch (filter.getSort()) {
            case SPENT_ON -> CursorBatch.of(rows, size, row -> Cursor.write(sort, row.spentOn(), row.id()));
            case MINUTES -> CursorBatch.of(rows, size, row -> Cursor.write(sort, row.minutes(), row.id()));
        };
    }
}
