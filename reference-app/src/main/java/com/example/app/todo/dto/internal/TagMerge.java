package com.example.app.todo.dto.internal;

import com.example.app.todo.domain.Tag;

/**
 * The two tags of a merge, both locked by {@code TagService.lockForMerge}: the source, whose todos move to the
 * target and which is then deleted, and the target. Passed between the halves of {@code TagMergeManager} only.
 */
public record TagMerge(Tag source, Tag target) {
}
