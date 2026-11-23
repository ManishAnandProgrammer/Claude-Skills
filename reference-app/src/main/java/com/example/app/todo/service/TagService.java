package com.example.app.todo.service;

import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownFor;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.pagination.Paging;
import com.example.app.common.security.CurrentUser;
import com.example.app.common.security.Permission;
import com.example.app.common.security.Reach;
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

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TagService {

    private final TagRepository repository;
    private final TagMapper mapper;
    private final CurrentUser currentUser;
    private final Paging paging;

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
                repository.findByFilter(filter, currentUser.id(), paging.offset(filter)));
    }

    @Transactional
    public ObjectWrapper<TagResponse> update(UUID id, TagUpdateRequest request) {
        Tag tag = getOwnedTag(id, currentUser.id());
        tag.checkVersion(request.version());
        mapper.update(tag, request);
        repository.flush();
        return ObjectWrapper.withObject(mapper.toResponse(tag));
    }

    @Transactional
    public void delete(UUID id) {
        repository.delete(getOwnedTag(id, currentUser.id()));
        repository.flush();
    }

    public CursorWrapper<DropdownItem> dropdown(Permission action, DropdownFilter filter) {
        DropdownFor.requireReach(action, currentUser.permissions(), EnumSet.of(Reach.OWN));
        CursorBatch<DropdownItem> batch = repository.findDropdownItems(filter, currentUser.id(), paging.cursorSize(filter));
        return CursorWrapper.of(batch.items(), batch.nextCursor());
    }

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

    /** The lower id first, whichever side it is, so two merges of one pair, either way round, queue instead of deadlocking. */
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
