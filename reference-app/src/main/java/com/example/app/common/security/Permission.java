package com.example.app.common.security;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;

@Schema(enumAsRef = true, description = "A capability the caller holds; a client shows a tab or a button by it")
public enum Permission {
    // example:begin
    TODO_LIST_READ_OWN(Area.TODOS, Reach.OWN),
    TODO_LIST_CREATE_OWN(Area.TODOS, Reach.OWN, TODO_LIST_READ_OWN),
    TODO_LIST_EDIT_OWN(Area.TODOS, Reach.OWN, TODO_LIST_READ_OWN),
    TODO_LIST_DELETE_OWN(Area.TODOS, Reach.OWN, TODO_LIST_READ_OWN),
    TODO_READ_OWN(Area.TODOS, Reach.OWN),
    TODO_CREATE_OWN(Area.TODOS, Reach.OWN, TODO_READ_OWN),
    TODO_EDIT_OWN(Area.TODOS, Reach.OWN, TODO_READ_OWN),
    TODO_DELETE_OWN(Area.TODOS, Reach.OWN, TODO_READ_OWN),
    TAG_READ_OWN(Area.TODOS, Reach.OWN),
    TAG_CREATE_OWN(Area.TODOS, Reach.OWN, TAG_READ_OWN),
    TAG_EDIT_OWN(Area.TODOS, Reach.OWN, TAG_READ_OWN),
    TAG_DELETE_OWN(Area.TODOS, Reach.OWN, TAG_READ_OWN),
    PLAN_READ_OWN(Area.TODOS, Reach.OWN),
    PLAN_CREATE_OWN(Area.TODOS, Reach.OWN, PLAN_READ_OWN),
    PLAN_EDIT_OWN(Area.TODOS, Reach.OWN, PLAN_READ_OWN),
    PLAN_DELETE_OWN(Area.TODOS, Reach.OWN, PLAN_READ_OWN),
    // example:end
    // sign-in:begin
    // tenancy:begin
    // TENANT_MEMBERS_MANAGE(Area.ACCOUNTS, Reach.ALL),
    // tenancy:else
    ACCOUNT_READ(Area.ACCOUNTS, Reach.ALL),
    ACCOUNT_STATUS_CHANGE(Area.ACCOUNTS, Reach.ALL, ACCOUNT_READ),
    // tenancy:end
    // roles:begin
    // ROLES_MANAGE(Area.ACCOUNTS, Reach.ALL),
    // // tenancy:begin
    // ACCESS_GRANT(Area.ACCOUNTS, Reach.ALL, TENANT_MEMBERS_MANAGE),
    // // tenancy:else
    // ACCESS_GRANT(Area.ACCOUNTS, Reach.ALL, ACCOUNT_READ),
    // // tenancy:end
    // roles:end
    // sign-in:end
    // metadata:begin
    METADATA_MANAGE(Area.SETTINGS, Reach.ALL),
    // metadata:end
    // media:begin
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

    /** Checked, never granted with this one: every role that holds a permission holds its prerequisites too. */
    public Set<Permission> prerequisites() {
        return prerequisites;
    }
}
