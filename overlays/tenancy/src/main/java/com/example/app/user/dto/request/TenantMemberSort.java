package com.example.app.user.dto.request;

import org.springframework.data.domain.Sort;

public enum TenantMemberSort {

    /**
     * The order people joined in (a re-added member joins anew): the membership's creation time, then the id.
     * Never the id alone: the primary key, which leads with the id, would compete with the tenant's index,
     * and with UUIDv7 ids in heap order the planner walks it through every other tenant's newer rows (DATA-10).
     */
    NEWEST(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))),
    OLDEST(Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")));

    private final Sort sort;

    TenantMemberSort(Sort sort) {
        this.sort = sort;
    }

    public Sort toSort() {
        return sort;
    }
}
