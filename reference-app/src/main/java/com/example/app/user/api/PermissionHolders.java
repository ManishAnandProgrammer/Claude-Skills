package com.example.app.user.api;

import com.example.app.common.dropdown.DropdownCandidate;
import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.security.Permission;

import java.util.UUID;

/** Whom a write may name: an active account holding the permission now, whatever the caller's token says. */
public interface PermissionHolders {

    int CURSOR_KEYS = 2;

    static String sortOf(DropdownFilter filter) {
        return filter.getSortDirection() == DropdownCandidate.SortDirection.DESC ? "FULL_NAME_DESC" : "FULL_NAME_ASC";
    }

    boolean holds(UUID userId, Permission permission);

    CursorBatch<DropdownItem> findHolders(Permission permission, DropdownFilter filter, int size);

    CursorBatch<DropdownItem> findHoldersOtherThan(Permission permission, UUID leftOut, DropdownFilter filter, int size);
}
