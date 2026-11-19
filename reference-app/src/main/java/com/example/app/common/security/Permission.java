package com.example.app.common.security;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;

/**
 * What a caller may do, one capability per constant: Spring's authority (a granted authority of the constant's
 * name), where a role is Spring's group of them (the sign-in piece's {@code Role}). Handlers name the one they
 * need with {@link Requires}; a record's own rules (whose it is, its state, an amount) are its module's policy,
 * never a permission.
 *
 * <p>A name is {@code <RESOURCE>_<ACTION>}, with a reach suffix only where the business tells reaches apart:
 * {@code _OWN}, {@code _ASSIGNED}, none for every record; the suffix is declared again as {@link #reach()},
 * which code reads. Each constant names its menu {@link #area()} and the permissions it needs to be usable
 * ({@link #prerequisites()}: an edit needs the read that opens the record). A prerequisite is checked, never
 * granted for it: every role that holds a permission holds its prerequisites too ({@code RoleTest}). An
 * authority that names no constant is ignored ({@link CurrentUser#permissions()}): unknown codes grant nothing.</p>
 */
@Schema(enumAsRef = true, description = "A capability the caller holds; a client shows a tab or a button by it")
public enum Permission {
    // example:begin
    TODO_LIST_READ_OWN(Area.TODOS, Reach.OWN),
    TODO_LIST_CREATE_OWN(Area.TODOS, Reach.OWN, TODO_LIST_READ_OWN),
    TODO_LIST_EDIT_OWN(Area.TODOS, Reach.OWN, TODO_LIST_READ_OWN),
    TODO_LIST_DELETE_OWN(Area.TODOS, Reach.OWN, TODO_LIST_READ_OWN),
    /** A todo the caller owns, or one they requested (TodoPolicy): the detail; the list shows their own. */
    TODO_READ_OWN(Area.TODOS, Reach.OWN),
    TODO_CREATE_OWN(Area.TODOS, Reach.OWN, TODO_READ_OWN),
    /** Its fields, status, tags and time entries. */
    TODO_EDIT_OWN(Area.TODOS, Reach.OWN, TODO_READ_OWN),
    TODO_DELETE_OWN(Area.TODOS, Reach.OWN, TODO_READ_OWN),
    TAG_READ_OWN(Area.TODOS, Reach.OWN),
    TAG_CREATE_OWN(Area.TODOS, Reach.OWN, TAG_READ_OWN),
    /** Renames, recolours and merges. */
    TAG_EDIT_OWN(Area.TODOS, Reach.OWN, TAG_READ_OWN),
    TAG_DELETE_OWN(Area.TODOS, Reach.OWN, TAG_READ_OWN),
    PLAN_READ_OWN(Area.TODOS, Reach.OWN),
    PLAN_CREATE_OWN(Area.TODOS, Reach.OWN, PLAN_READ_OWN),
    /** Its fields and its status. */
    PLAN_EDIT_OWN(Area.TODOS, Reach.OWN, PLAN_READ_OWN),
    PLAN_DELETE_OWN(Area.TODOS, Reach.OWN, PLAN_READ_OWN),
    // example:end
    // sign-in:begin
    // tenancy:begin
    // /** The tenant's members: who belongs, with which role. */
    // TENANT_MEMBERS_MANAGE(Area.ACCOUNTS, Reach.ALL),
    // tenancy:else
    /** Finds another account by its address, and suspends or reactivates it. */
    ACCOUNT_STATUS_CHANGE(Area.ACCOUNTS, Reach.ALL),
    // tenancy:end
    // sign-in:end
    // metadata:begin
    /** Reads and changes the configuration metadata. */
    METADATA_MANAGE(Area.SETTINGS, Reach.ALL),
    // metadata:end
    // media:begin
    /** Changes an upload purpose's limits (everyone signed in reads them). */
    UPLOAD_POLICY_MANAGE(Area.SETTINGS, Reach.ALL),
    // media:end
    ;

    private final Area area;
    private final Reach reach;
    private final Set<Permission> prerequisites;

    Permission(Area area, Reach reach, Permission... prerequisites) {
        this.area = area;
        this.reach = reach;
        this.prerequisites = Set.of(prerequisites);
    }

    public Area area() {
        return area;
    }

    public Reach reach() {
        return reach;
    }

    /** The permissions this one needs to be usable; a role holding it holds them too. */
    public Set<Permission> prerequisites() {
        return prerequisites;
    }
}
