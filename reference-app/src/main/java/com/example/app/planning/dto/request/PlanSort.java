package com.example.app.planning.dto.request;

/** The orders the plan list can be scrolled in; each has a keyset in {@code PlanRepositoryImpl}. */
public enum PlanSort {
    /** The earliest first day first: the plans in calendar order. */
    STARTS_ON,
    NEWEST
}
