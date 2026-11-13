package com.example.app.common.pagination;

import com.example.app.common.limits.Limits;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Turns validated filters into page requests, filling in the configured default size. */
@Component
public class Paging {

    private final Limits limits;

    public Paging(Limits limits) {
        this.limits = limits;
    }

    public PageRequest offset(PageFilter filter, Sort sort) {
        int size = Objects.requireNonNullElseGet(filter.getSize(), () -> limits.get("offset-page.default-size"));
        return PageRequest.of(filter.getPage(), size, sort);
    }

    public int cursorSize(CursorFilter filter) {
        return Objects.requireNonNullElseGet(filter.getSize(), () -> limits.get("cursor-page.default-size"));
    }
}
