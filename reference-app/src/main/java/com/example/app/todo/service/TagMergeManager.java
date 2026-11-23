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
 * Lock order: both tags, then the todos carrying the source. No path takes them the other way round: a todo's own
 * write locks the todo and reaches a tag only through a link's foreign key check, which waits for the source's key
 * lock only when it adds the source, to a todo the merge doesn't lock (it doesn't carry the source).
 */
@Service
@RequiredArgsConstructor
public class TagMergeManager {

    private final TagService tagService;
    private final TodoService todoService;
    private final CurrentUser currentUser;

    @Transactional
    public ObjectWrapper<TagResponse> merge(UUID targetId, TagMergeRequest request) {
        UUID ownerId = currentUser.id();
        TagMerge merge = tagService.lockForMerge(request.sourceTagId(), targetId, ownerId);
        todoService.moveTagLinks(merge.source().getId(), merge.target().getId(), ownerId);
        return tagService.finishMerge(merge);
    }
}
