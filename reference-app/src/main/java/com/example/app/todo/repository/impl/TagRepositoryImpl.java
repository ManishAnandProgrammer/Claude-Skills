package com.example.app.todo.repository.impl;

import com.example.app.common.dropdown.DropdownCandidate;
import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.dropdown.DropdownStatus;
import com.example.app.common.pagination.Cursor;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.pagination.OffsetPages;
import com.example.app.common.pagination.QueryFilter;
import com.example.app.common.pagination.Seek;
import com.example.app.common.pagination.TextSearch;
import com.example.app.todo.domain.Tag;
import com.example.app.todo.domain.Tag_;
import com.example.app.todo.dto.request.TagFilter;
import com.example.app.todo.dto.response.TagResponse;
import com.example.app.todo.repository.custom.CustomTagRepository;
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
import java.util.UUID;

@Repository
class TagRepositoryImpl implements CustomTagRepository {

    /** The cursor names the direction it was taken in, so it is refused in the other. */
    private static final String NAME_ASC = "NAME_ASC";
    private static final String NAME_DESC = "NAME_DESC";

    private final EntityManager entityManager;

    TagRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Page<TagResponse> findByFilter(TagFilter filter, UUID ownerId, Pageable pageable) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<TagResponse> query = builder.createQuery(TagResponse.class);
        Root<Tag> root = query.from(Tag.class);
        query.select(builder.construct(TagResponse.class,
                root.get(Tag_.id), root.get(Tag_.name), root.get(Tag_.color), root.get(Tag_.active),
                root.get(Tag_.ownerId), root.get(Tag_.createdAt), root.get(Tag_.updatedAt),
                root.get(Tag_.createdBy), root.get(Tag_.updatedBy), root.get(Tag_.version)));
        query.where(pageConditions(builder, root, ownerId, filter).toArray(new Predicate[0]));
        query.orderBy(QueryUtils.toOrders(pageable.getSort(), root, builder));

        CriteriaQuery<Long> countQuery = builder.createQuery(Long.class);
        Root<Tag> countRoot = countQuery.from(Tag.class);
        countQuery.select(builder.count(countRoot));
        countQuery.where(pageConditions(builder, countRoot, ownerId, filter).toArray(new Predicate[0]));

        return OffsetPages.page(entityManager.createQuery(query), entityManager.createQuery(countQuery), pageable);
    }

    /** Scrolls by name, then id. */
    @Override
    public CursorBatch<DropdownItem> findDropdownItems(DropdownFilter filter, UUID ownerId, int size) {
        boolean descending = filter.getSortDirection() == DropdownCandidate.SortDirection.DESC;
        String sort = descending ? NAME_DESC : NAME_ASC;
        Cursor cursor = Cursor.read(filter.getAfter(), sort);

        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<DropdownItem> query = builder.createQuery(DropdownItem.class);
        Root<Tag> root = query.from(Tag.class);
        query.select(builder.construct(DropdownItem.class, root.get(Tag_.id), root.get(Tag_.name)));
        List<Predicate> predicates = ownedAndMatching(builder, root, ownerId, filter);
        if (filter.getStatus() != DropdownStatus.ALL) {
            predicates.add(hasStatus(builder, root, filter.getStatus()));
        }
        if (cursor.isPresent()) {
            predicates.add(Seek.after(builder, descending,
                    root.get(Tag_.name), cursor.text(0),
                    root.get(Tag_.id), cursor.uuid(1)));
        }
        query.where(predicates.toArray(new Predicate[0]));
        query.orderBy(Seek.order(builder, descending, root.get(Tag_.name), root.get(Tag_.id)));

        List<DropdownItem> rows = entityManager.createQuery(query).setMaxResults(size + 1).getResultList();
        return CursorBatch.of(rows, size, item -> Cursor.write(sort, item.label(), item.value()));
    }

    /** Shared by the page's select and its count. */
    private static List<Predicate> pageConditions(CriteriaBuilder builder, Root<Tag> root, UUID ownerId, TagFilter filter) {
        List<Predicate> predicates = ownedAndMatching(builder, root, ownerId, filter);
        if (filter.requireStatusPredicate()) {
            predicates.add(hasStatus(builder, root, filter.getStatus()));
        }
        return predicates;
    }

    /** ACTIVE and INACTIVE select by the active flag; ALL needs no predicate. */
    private static Predicate hasStatus(CriteriaBuilder builder, Root<Tag> root, DropdownStatus status) {
        return builder.equal(root.get(Tag_.active), status == DropdownStatus.ACTIVE);
    }

    /** Shared by the page and the dropdown, so both list exactly what the caller may read. */
    private static List<Predicate> ownedAndMatching(CriteriaBuilder builder, Root<Tag> root, UUID ownerId,
                                                    QueryFilter filter) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(builder.equal(root.get(Tag_.ownerId), ownerId));
        if (filter.requireQueryPredicate()) {
            predicates.add(TextSearch.contains(builder, root.get(Tag_.name), filter.getQuery()));
        }
        return predicates;
    }
}
