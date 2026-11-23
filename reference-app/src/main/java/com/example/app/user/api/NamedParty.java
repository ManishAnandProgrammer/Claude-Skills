package com.example.app.user.api;

import com.example.app.common.security.Permission;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * The one definition a write's check and its picker share (house-review AUTHZ-04, REST-13): the account a write names
 * must be active and hold {@code mustHold} now, and {@code GET /users/dropdown?for=<write>} lists exactly those.
 */
public enum NamedParty {
    // example:begin
    TODO_REQUESTER(Permission.TODO_READ_OWN, Permission.TODO_CREATE_OWN, Permission.TODO_EDIT_OWN),
    // example:end
    ;

    private final Permission mustHold;
    private final Set<Permission> namedBy;

    NamedParty(Permission mustHold, Permission... namedBy) {
        this.mustHold = mustHold;
        this.namedBy = Set.of(namedBy);
    }

    public Permission mustHold() {
        return mustHold;
    }

    public static Optional<NamedParty> namedBy(Permission write) {
        return Arrays.stream(values()).filter(party -> party.namedBy.contains(write)).findFirst();
    }
}
