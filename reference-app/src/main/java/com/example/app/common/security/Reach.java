package com.example.app.common.security;

/**
 * Which records a {@link Permission} reaches: the suffix of its name, declared on the constant so no code
 * parses names. What "own" or "assigned" means is the owning module's policy ({@code TodoPolicy}), never
 * this enum's.
 */
public enum Reach {
    /** The caller's own records ({@code _OWN}). */
    OWN,
    /** The records the owning module says are the caller's to work ({@code _ASSIGNED}). */
    ASSIGNED,
    /** Every record (no suffix). */
    ALL
}
