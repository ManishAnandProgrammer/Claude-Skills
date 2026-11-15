package com.example.app.user.dto.request;

import org.springframework.data.domain.Sort;

public enum TenantMemberSort {

    /** Membership ids are UUIDv7, so id order is the order people joined in (a re-added member joins anew). */
    NEWEST(Sort.by(Sort.Order.desc("id"))),
    OLDEST(Sort.by(Sort.Order.asc("id")));

    private final Sort sort;

    TenantMemberSort(Sort sort) {
        this.sort = sort;
    }

    public Sort toSort() {
        return sort;
    }
}
