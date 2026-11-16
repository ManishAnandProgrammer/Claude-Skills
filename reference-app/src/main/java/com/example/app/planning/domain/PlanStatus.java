package com.example.app.planning.domain;

import java.util.Set;

/**
 * A plan's lifecycle: {@code PLANNED -> DONE} (on the day it was done) or {@code PLANNED -> CANCELLED}. Both
 * are final. Stored as text with a check constraint (changeset 0033).
 */
public enum PlanStatus {

    PLANNED,
    DONE,
    /** Called off: its days are free again. */
    CANCELLED;

    public boolean canBecome(PlanStatus target) {
        return switch (this) {
            case PLANNED -> Set.of(DONE, CANCELLED).contains(target);
            case DONE, CANCELLED -> false;
        };
    }
}
