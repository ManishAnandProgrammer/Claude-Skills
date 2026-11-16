package com.example.app.todo.service;

import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.pagination.Paging;
import com.example.app.common.security.CurrentUser;
// tenancy-row:begin
// import com.example.app.common.tenancy.TenantContext;
// tenancy-row:end
import com.example.app.common.wrapper.CursorWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.common.wrapper.PaginationWrapper;
import com.example.app.todo.domain.Tag;
import com.example.app.todo.dto.internal.TagMerge;
import com.example.app.todo.dto.request.TagCreateRequest;
import com.example.app.todo.dto.request.TagFilter;
import com.example.app.todo.dto.request.TagUpdateRequest;
import com.example.app.todo.dto.response.TagResponse;
import com.example.app.todo.mapper.TagMapper;
import com.example.app.todo.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Every read and write is scoped to the caller: another user's tag answers 404, like a missing one. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TagService {

    private final TagRepository repository;
    private final TagMapper mapper;
    private final CurrentUser currentUser;
    private final Paging paging;

    /** A taken name is refused at the flush by {@code uq_tags_owner_id_name}: 409 {@code TAG_NAME_TAKEN}. */
    @Transactional
    public ObjectWrapper<TagResponse> create(TagCreateRequest request) {
        Tag saved = repository.saveAndFlush(mapper.toEntity(request, currentUser.id()));
        return ObjectWrapper.withObject(mapper.toResponse(saved));
    }

    public ObjectWrapper<TagResponse> findById(UUID id) {
        return ObjectWrapper.withObject(mapper.toResponse(getOwnedTag(id, currentUser.id())));
    }

    public PaginationWrapper<TagResponse> findByFilter(TagFilter filter) {
        return PaginationWrapper.fromPage(
                repository.findByFilter(filter, currentUser.id(), paging.offset(filter, filter.getSort().toSort())));
    }

    @Transactional
    public ObjectWrapper<TagResponse> update(UUID id, TagUpdateRequest request) {
        Tag tag = getOwnedTag(id, currentUser.id());
        tag.checkVersion(request.version());
        mapper.update(tag, request);
        repository.flush();
        return ObjectWrapper.withObject(mapper.toResponse(tag));
    }

    /**
     * Never blocked by the todos carrying the tag: the database removes it from them (changeset 0006),
     * and their versions don't change.
     */
    @Transactional
    public void delete(UUID id) {
        repository.delete(getOwnedTag(id, currentUser.id()));
        repository.flush();
    }

    /** Tags have an active flag, so every {@code status} applies: ACTIVE, INACTIVE or ALL. */
    public CursorWrapper<DropdownItem> dropdown(DropdownFilter filter) {
        CursorBatch<DropdownItem> batch = repository.findDropdownItems(filter, currentUser.id(), paging.cursorSize(filter));
        return CursorWrapper.of(batch.items(), batch.nextCursor());
    }

    /**
     * The owner's tags with these ids. An id that isn't one of the owner's tags is
     * {@code TAG_NOT_FOUND}, naming the lowest of them, so the answer is repeatable. Not locked: a tag
     * deleted before its link is written is refused by the link's foreign key, 404 {@code TAG_GONE}.
     */
    public List<Tag> getOwnedTags(Set<UUID> ids, UUID ownerId) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Tag> tags = repository.findByIdInAndOwnerId(ids, ownerId);
        if (tags.size() < ids.size()) {
            throw AppException.of(ErrorCode.TAG_NOT_FOUND, lowestMissing(ids, tags));
        }
        return tags;
    }

    /**
     * A merge's first half (TagMergeManager): both tags, the caller's own, locked in one order for every merge,
     * the lower id first ({@link UUID#compareTo}) whichever side it is, so two merges of one pair, either way
     * round, queue instead of deadlocking. The source takes the key lock (its links can't grow until it is
     * deleted), the target the write lock (it stays, and todos may still be tagged with it). 400
     * {@code TAG_MERGE_INTO_ITSELF} for one tag on both sides, before any lock; 404 {@code TAG_NOT_FOUND} for
     * either side, naming it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TagMerge lockForMerge(UUID sourceId, UUID targetId, UUID ownerId) {
        if (sourceId.equals(targetId)) {
            throw AppException.of(ErrorCode.TAG_MERGE_INTO_ITSELF, targetId);
        }
        if (sourceId.compareTo(targetId) < 0) {
            Tag source = lockSource(sourceId, ownerId);
            return new TagMerge(source, lockTarget(targetId, ownerId));
        }
        Tag target = lockTarget(targetId, ownerId);
        return new TagMerge(lockSource(sourceId, ownerId), target);
    }

    /**
     * A merge's last half, once the todos carry the target instead: deletes the source, which no link names
     * any more, and answers with the target, whose own row the merge leaves as it was (its version too).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ObjectWrapper<TagResponse> finishMerge(TagMerge merge) {
        repository.delete(merge.source());
        repository.flush();
        return ObjectWrapper.withObject(mapper.toResponse(merge.target()));
    }

    private Tag lockSource(UUID id, UUID ownerId) {
        // tenancy-row:begin
        // return repository.findForKeyUpdateByIdAndOwnerId(TenantContext.id(), id, ownerId)
        //         .orElseThrow(AppException.supply(ErrorCode.TAG_NOT_FOUND, id));
        // tenancy-row:else
        return repository.findForKeyUpdateByIdAndOwnerId(id, ownerId).orElseThrow(AppException.supply(ErrorCode.TAG_NOT_FOUND, id));
        // tenancy-row:end
    }

    private Tag lockTarget(UUID id, UUID ownerId) {
        return repository.findForUpdateByIdAndOwnerId(id, ownerId).orElseThrow(AppException.supply(ErrorCode.TAG_NOT_FOUND, id));
    }

    private static UUID lowestMissing(Set<UUID> ids, List<Tag> found) {
        Set<UUID> foundIds = found.stream().map(Tag::getId).collect(Collectors.toSet());
        return ids.stream().filter(id -> !foundIds.contains(id)).sorted().findFirst().orElseThrow();
    }

    private Tag getOwnedTag(UUID id, UUID ownerId) {
        return repository.findByIdAndOwnerId(id, ownerId).orElseThrow(AppException.supply(ErrorCode.TAG_NOT_FOUND, id));
    }
}
