package com.example.app.tenant.repository.impl;

import com.example.app.common.pagination.Cursor;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.pagination.Seek;
import com.example.app.tenant.domain.PlatformAuditEntry;
import com.example.app.tenant.domain.PlatformAuditEntry_;
import com.example.app.tenant.dto.request.PlatformAuditFilter;
import com.example.app.tenant.dto.response.PlatformAuditResponse;
import com.example.app.tenant.repository.custom.CustomPlatformAuditEntryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CompoundSelection;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class PlatformAuditEntryRepositoryImpl implements CustomPlatformAuditEntryRepository {

    private static final String SORT = "NEWEST";
    private static final int CURSOR_KEYS = 2;

    private final EntityManager entityManager;

    @Override
    public CursorBatch<PlatformAuditResponse> findByTenant(UUID tenantId, PlatformAuditFilter filter, int size) {
        Cursor cursor = Cursor.read(filter.getAfter(), SORT, CURSOR_KEYS);

        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<PlatformAuditResponse> query = builder.createQuery(PlatformAuditResponse.class);
        Root<PlatformAuditEntry> root = query.from(PlatformAuditEntry.class);
        query.select(rowOf(builder, root));
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(builder.equal(root.get(PlatformAuditEntry_.tenantId), tenantId));
        if (cursor.hasPosition()) {
            predicates.add(Seek.after(builder, true, root.get(PlatformAuditEntry_.createdAt), cursor.instant(0),
                    root.get(PlatformAuditEntry_.id), cursor.uuid(1)));
        }
        query.where(predicates.toArray(new Predicate[0]));
        // Never the id alone: the planner would walk the UUIDv7 primary key backward through every tenant's rows
        // instead of the tenant's index.
        query.orderBy(Seek.order(builder, true, root.get(PlatformAuditEntry_.createdAt), root.get(PlatformAuditEntry_.id)));

        List<PlatformAuditResponse> rows = entityManager.createQuery(query).setMaxResults(size + 1).getResultList();
        return CursorBatch.of(rows, size, row -> Cursor.write(SORT, row.createdAt(), row.id()));
    }

    private static CompoundSelection<PlatformAuditResponse> rowOf(CriteriaBuilder builder, Root<PlatformAuditEntry> root) {
        return builder.construct(PlatformAuditResponse.class,
                root.get(PlatformAuditEntry_.id), root.get(PlatformAuditEntry_.actorId),
                root.get(PlatformAuditEntry_.action), root.get(PlatformAuditEntry_.detail),
                root.get(PlatformAuditEntry_.createdAt), root.get(PlatformAuditEntry_.updatedAt),
                root.get(PlatformAuditEntry_.createdBy), root.get(PlatformAuditEntry_.updatedBy));
    }
}
