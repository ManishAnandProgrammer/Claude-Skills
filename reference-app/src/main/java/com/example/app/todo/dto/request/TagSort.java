package com.example.app.todo.dto.request;

import org.springframework.data.domain.Sort;

public enum TagSort {

    NAME(Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"))),
    /** Ids are UUIDv7, so creation order is id order. */
    NEWEST(Sort.by(Sort.Order.desc("id")));

    private final Sort sort;

    TagSort(Sort sort) {
        this.sort = sort;
    }

    public Sort toSort() {
        return sort;
    }
}
