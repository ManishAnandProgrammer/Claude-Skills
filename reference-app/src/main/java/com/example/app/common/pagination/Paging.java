package com.example.app.common.pagination;

import com.example.app.common.limits.Limits;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@RequiredArgsConstructor
public class Paging {

    private final Limits limits;

    public PageRequest offset(PageFilter filter) {
        int size = Objects.requireNonNullElseGet(filter.getSize(), () -> limits.get("offset-page.default-size"));
        return PageRequest.of(filter.getPage(), size);
    }

    public int cursorSize(CursorFilter filter) {
        return Objects.requireNonNullElseGet(filter.getSize(), () -> limits.get("cursor-page.default-size"));
    }
}
