package com.example.app.common.pagination;

import jakarta.persistence.TypedQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;

import java.util.List;

/**
 * Runs an offset page: the rows of the page, and the count only when the page itself can't tell the
 * total (a full page), so most small lists cost one statement.
 */
public final class OffsetPages {

    private OffsetPages() {
    }

    /** {@code rows} is the ordered select, {@code count} the same predicates counted. */
    public static <R> Page<R> page(TypedQuery<R> rows, TypedQuery<Long> count, Pageable pageable) {
        List<R> content = rows
                .setFirstResult(Math.toIntExact(pageable.getOffset()))
                .setMaxResults(pageable.getPageSize())
                .getResultList();
        return PageableExecutionUtils.getPage(content, pageable, count::getSingleResult);
    }
}
