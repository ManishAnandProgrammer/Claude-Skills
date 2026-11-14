package com.example.app.todo.repository.impl;

import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.pagination.KeyColumn;
import com.example.app.common.pagination.Keyset;
import com.example.app.common.pagination.KeysetPages;
import com.example.app.common.pagination.ProjectionQuery;
import com.example.app.todo.domain.TimeEntry;
import com.example.app.todo.domain.TimeEntry_;
import com.example.app.todo.domain.Todo_;
import com.example.app.todo.dto.request.TimeEntryFilter;
import com.example.app.todo.dto.response.TimeEntryResponse;
import com.example.app.todo.repository.custom.CustomTimeEntryRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
class TimeEntryRepositoryImpl implements CustomTimeEntryRepository {

    private static final KeyColumn<TimeEntry, TimeEntryResponse, UUID> ID =
            new KeyColumn<>(root -> root.get(TimeEntry_.id), TimeEntryResponse::id, UUID.class);
    /** Both orders read their index backwards: the most recent day, or the longest entry, first. */
    private static final Keyset<TimeEntry, TimeEntryResponse> BY_SPENT_ON = new Keyset<>("SPENT_ON", true, List.of(
            new KeyColumn<>(root -> root.get(TimeEntry_.spentOn), TimeEntryResponse::spentOn, LocalDate.class),
            ID));
    private static final Keyset<TimeEntry, TimeEntryResponse> BY_MINUTES = new Keyset<>("MINUTES", true, List.of(
            new KeyColumn<>(root -> root.get(TimeEntry_.minutes), TimeEntryResponse::minutes, Integer.class),
            ID));

    private final KeysetPages keysetPages;

    TimeEntryRepositoryImpl(KeysetPages keysetPages) {
        this.keysetPages = keysetPages;
    }

    @Override
    public CursorBatch<TimeEntryResponse> findByFilter(TimeEntryFilter filter, UUID todoId, UUID ownerId, int size) {
        Keyset<TimeEntry, TimeEntryResponse> keyset = switch (filter.getSort()) {
            case SPENT_ON -> BY_SPENT_ON;
            case MINUTES -> BY_MINUTES;
        };
        return keysetPages.fetch(new ProjectionQuery<>(TimeEntry.class, TimeEntryResponse.class, TimeEntryRepositoryImpl::row,
                (builder, root, query) -> conditions(builder, root, filter, todoId, ownerId)), keyset, filter.getAfter(), size);
    }

    private static Selection<TimeEntryResponse> row(CriteriaBuilder builder, Root<TimeEntry> root) {
        return builder.construct(TimeEntryResponse.class,
                root.get(TimeEntry_.id), root.get(TimeEntry_.todo).get(Todo_.id), root.get(TimeEntry_.ownerId),
                root.get(TimeEntry_.minutes), root.get(TimeEntry_.spentOn), root.get(TimeEntry_.note),
                root.get(TimeEntry_.billable), root.get(TimeEntry_.createdAt), root.get(TimeEntry_.updatedAt),
                root.get(TimeEntry_.createdBy), root.get(TimeEntry_.updatedBy), root.get(TimeEntry_.version));
    }

    private static List<Predicate> conditions(CriteriaBuilder builder, Root<TimeEntry> root, TimeEntryFilter filter,
                                              UUID todoId, UUID ownerId) {
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
        return predicates;
    }
}
