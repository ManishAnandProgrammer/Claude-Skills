package com.example.app.tenant.dto.request;

import org.springframework.data.domain.Sort;

public enum TenantSort {

    NAME(Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"))),
    /** Creation time, then the id: one convention for every list's NEWEST (DATA-10), served by its own index. */
    NEWEST(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

    private final Sort sort;

    TenantSort(Sort sort) {
        this.sort = sort;
    }

    public Sort toSort() {
        return sort;
    }
}
