package com.example.app.todo.repository.impl;

import com.example.app.common.dropdown.DropdownCandidate;
import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.dropdown.DropdownStatus;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.pagination.KeyColumn;
import com.example.app.common.pagination.Keyset;
import com.example.app.common.pagination.KeysetPages;
import com.example.app.common.pagination.OffsetPages;
import com.example.app.common.pagination.ProjectionQuery;
import com.example.app.common.pagination.QueryFilter;
import com.example.app.common.pagination.TextSearch;
import com.example.app.todo.domain.Tag;
import com.example.app.todo.domain.Tag_;
import com.example.app.todo.dto.request.TagFilter;
import com.example.app.todo.dto.response.TagResponse;
import com.example.app.todo.repository.custom.CustomTagRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
class TagRepositoryImpl implements CustomTagRepository {

    /** The dropdown scrolls by label, then id; the cursor names the direction it was taken in. */
    private static final List<KeyColumn<Tag, DropdownItem, ?>> DROPDOWN_KEYS = List.of(
            new KeyColumn<>(root -> root.get(Tag_.name), DropdownItem::label, String.class),
            new KeyColumn<>(root -> root.get(Tag_.id), item -> (UUID) item.value(), UUID.class));
    private static final Keyset<Tag, DropdownItem> DROPDOWN_ASC = new Keyset<>("NAME_ASC", false, DROPDOWN_KEYS);
    private static final Keyset<Tag, DropdownItem> DROPDOWN_DESC = new Keyset<>("NAME_DESC", true, DROPDOWN_KEYS);

    private final OffsetPages offsetPages;
    private final KeysetPages keysetPages;

    TagRepositoryImpl(OffsetPages offsetPages, KeysetPages keysetPages) {
        this.offsetPages = offsetPages;
        this.keysetPages = keysetPages;
    }

    @Override
    public Page<TagResponse> findByFilter(TagFilter filter, UUID ownerId, Pageable pageable) {
        return offsetPages.fetch(new ProjectionQuery<>(Tag.class, TagResponse.class, TagRepositoryImpl::listRow,
                (builder, root) -> pageConditions(builder, root, ownerId, filter)), pageable);
    }

    @Override
    public CursorBatch<DropdownItem> findDropdownItems(DropdownFilter filter, UUID ownerId, int size) {
        Keyset<Tag, DropdownItem> keyset = filter.getSortDirection() == DropdownCandidate.SortDirection.DESC
                ? DROPDOWN_DESC
                : DROPDOWN_ASC;
        return keysetPages.fetch(new ProjectionQuery<>(Tag.class, DropdownItem.class, TagRepositoryImpl::dropdownRow,
                (builder, root) -> dropdownConditions(builder, root, ownerId, filter)), keyset, filter.getAfter(), size);
    }

    private static Selection<TagResponse> listRow(CriteriaBuilder builder, Root<Tag> root) {
        return builder.construct(TagResponse.class,
                root.get(Tag_.id), root.get(Tag_.name), root.get(Tag_.color), root.get(Tag_.active),
                root.get(Tag_.ownerId), root.get(Tag_.createdAt), root.get(Tag_.updatedAt),
                root.get(Tag_.createdBy), root.get(Tag_.updatedBy), root.get(Tag_.version));
    }

    private static Selection<DropdownItem> dropdownRow(CriteriaBuilder builder, Root<Tag> root) {
        return builder.construct(DropdownItem.class, root.get(Tag_.id), root.get(Tag_.name));
    }

    private static List<Predicate> pageConditions(CriteriaBuilder builder, Root<Tag> root, UUID ownerId, TagFilter filter) {
        List<Predicate> predicates = ownedAndMatching(builder, root, ownerId, filter);
        if (filter.requireStatusPredicate()) {
            predicates.add(hasStatus(builder, root, filter.getStatus()));
        }
        return predicates;
    }

    private static List<Predicate> dropdownConditions(CriteriaBuilder builder, Root<Tag> root, UUID ownerId,
                                                      DropdownFilter filter) {
        List<Predicate> predicates = ownedAndMatching(builder, root, ownerId, filter);
        if (filter.getStatus() != DropdownStatus.ALL) {
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
