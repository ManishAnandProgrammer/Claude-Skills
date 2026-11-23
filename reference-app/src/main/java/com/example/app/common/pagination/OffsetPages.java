package com.example.app.common.pagination;

import jakarta.persistence.TypedQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;

import java.util.List;

/**
 * The count runs only when the page itself can't tell the total (a full page), so a partial page costs one
 * statement.
 */
public final class OffsetPages {

    private OffsetPages() {
    }

    public static <R> Page<R> page(TypedQuery<R> rows, TypedQuery<Long> count, Pageable pageable) {
        List<R> content = rows
                .setFirstResult(Math.toIntExact(pageable.getOffset()))
                .setMaxResults(pageable.getPageSize())
                .getResultList();
        return PageableExecutionUtils.getPage(content, pageable, count::getSingleResult);
    }
}
