package com.example.app.todo.dto.request;

import org.springframework.data.domain.Sort;

public enum TagSort {

    NAME(Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"))),
    /** Creation time, then the id: never the id alone, whose primary key would compete with the owner's index (DATA-10). */
    NEWEST(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

    private final Sort sort;

    TagSort(Sort sort) {
        this.sort = sort;
    }

    public Sort toSort() {
        return sort;
    }
}
