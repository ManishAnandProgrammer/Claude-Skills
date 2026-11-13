package com.example.app.todo.dto.request;

/** The orders a todo's time entries can be scrolled in; each has a keyset in {@code TimeEntryRepositoryImpl}. */
public enum TimeEntrySort {
    /** Most recent day first. */
    SPENT_ON,
    /** Longest entry first. */
    MINUTES
}
