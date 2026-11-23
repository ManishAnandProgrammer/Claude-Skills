package com.example.app.planning.domain;

import java.util.Set;

public enum PlanStatus {

    PLANNED,
    DONE,
    CANCELLED;

    public boolean canBecome(PlanStatus target) {
        return switch (this) {
            case PLANNED -> Set.of(DONE, CANCELLED).contains(target);
            case DONE, CANCELLED -> false;
        };
    }
}
