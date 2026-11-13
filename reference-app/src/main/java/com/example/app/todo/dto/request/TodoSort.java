package com.example.app.todo.dto.request;

/** The orders the todo list can be scrolled in; each has a keyset in {@code TodoRepositoryImpl}. */
public enum TodoSort {
    /** Soonest due first; todos without a due date last. */
    DUE_DATE,
    NEWEST
}
