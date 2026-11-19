package com.example.app.user.dto.request;

import org.springframework.data.domain.Sort;

public enum AccessRoleSort {

    NAME(Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"))),
    /** The code alone: it is unique (uq_roles_code), so it needs no tie-breaker. */
    CODE(Sort.by(Sort.Order.asc("code")));

    private final Sort sort;

    AccessRoleSort(Sort sort) {
        this.sort = sort;
    }

    public Sort toSort() {
        return sort;
    }
}
