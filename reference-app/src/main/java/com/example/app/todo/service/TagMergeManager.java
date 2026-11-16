package com.example.app.todo.service;

import com.example.app.common.security.CurrentUser;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.todo.dto.internal.TagMerge;
import com.example.app.todo.dto.request.TagMergeRequest;
import com.example.app.todo.dto.response.TagResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * A use case that writes two aggregates, the tags and the todos (house-review ARCH-15's Manager): it owns the
 * one transaction, and each aggregate's service does its own part in it ({@code MANDATORY} halves), so a
 * failure in any part rolls back every part. It uses no repository.
 *
 * <p>The lock order, the same for every path that takes these locks: both tags (TagService.lockForMerge, the
 * lower id first), then the todos carrying the source, in id order (TodoService.moveTagLinks). No path takes
 * them the other way round: a todo's own writes lock the todo and reach a tag only through a link's foreign
 * key check, which waits for the source's key lock only when it adds the source, and then the merge doesn't
 * need that todo (it doesn't carry the source).</p>
 */
@Service
@RequiredArgsConstructor
public class TagMergeManager {

    private final TagService tagService;
    private final TodoService todoService;
    private final CurrentUser currentUser;

    /**
     * Merges the request's tag into {@code targetId}: its todos carry the target instead, and it is deleted.
     * Answers with the target.
     */
    @Transactional
    public ObjectWrapper<TagResponse> merge(UUID targetId, TagMergeRequest request) {
        UUID ownerId = currentUser.id();
        TagMerge merge = tagService.lockForMerge(request.sourceTagId(), targetId, ownerId);
        todoService.moveTagLinks(merge.source().getId(), merge.target().getId(), ownerId);
        return tagService.finishMerge(merge);
    }
}
