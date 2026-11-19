package com.example.app.user.dto.request;

import org.springframework.data.domain.Sort;

public enum AccessRoleSort {

    NAME(Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"))),
    CODE(Sort.by(Sort.Order.asc("code"), Sort.Order.asc("id")));

    private final Sort sort;

    AccessRoleSort(Sort sort) {
        this.sort = sort;
    }

    public Sort toSort() {
        return sort;
    }
}
