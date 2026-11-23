package com.example.app.user.api;

import com.example.app.common.security.Permission;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * The one definition a write's check and its picker share (house-review AUTHZ-04, REST-13): the account a write names
 * must be active and hold {@code mustHold} now, and be someone other than the record's owner unless {@code owner}
 * says they may name themselves; {@code GET /users/dropdown?for=<write>} lists exactly those.
 */
public enum NamedParty {
    // example:begin
    TODO_REQUESTER(Permission.TODO_READ_OWN, Owner.NEVER_NAMED, Permission.TODO_CREATE_OWN, Permission.TODO_EDIT_OWN),
    // example:end
    ;

    /**
     * Never for a party who decides on the owner's record (an approver, a reviewer: four eyes), nor for one the owner
     * would name themselves as to no purpose (a todo one asked oneself for is one nobody asked for).
     */
    public enum Owner { MAY_BE_NAMED, NEVER_NAMED }

    private final Permission mustHold;
    private final Owner owner;
    private final Set<Permission> namedBy;

    NamedParty(Permission mustHold, Owner owner, Permission... namedBy) {
        this.mustHold = mustHold;
        this.owner = owner;
        this.namedBy = Set.of(namedBy);
    }

    public Permission mustHold() {
        return mustHold;
    }

    public boolean mayBeTheOwner() {
        return owner == Owner.MAY_BE_NAMED;
    }

    public static Optional<NamedParty> namedBy(Permission write) {
        return Arrays.stream(values()).filter(party -> party.namedBy.contains(write)).findFirst();
    }
}
