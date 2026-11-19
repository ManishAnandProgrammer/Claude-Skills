package com.example.app.common.dropdown;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.exception.RequestError;
import com.example.app.common.security.Permission;
import com.example.app.common.security.Reach;

import java.util.List;
import java.util.Set;

/**
 * A dropdown's required {@code for}: the permission of the action its options serve. One dropdown per resource
 * serves every screen that picks one, so it never lists who may call it: the caller names the action, and the
 * options are the records that action's reach covers. Like an upload's purpose, the client names it and the
 * server checks it; the write the options feed checks everything again.
 */
public final class DropdownFor {

    /** The query parameter's name. */
    public static final String PARAMETER = "for";

    private DropdownFor() {
    }

    /**
     * The reach the options narrow to, read in memory (no statement): 403 {@code ACCESS_DENIED} unless the caller
     * holds {@code action}, 400 {@code DROPDOWN_REACH_UNSUPPORTED} at {@code for} unless its reach is one the
     * dropdown lists ({@code supported}, each one a condition of its own query).
     */
    public static Reach check(Permission action, Set<Permission> held, Set<Reach> supported) {
        if (!held.contains(action)) {
            throw AppException.of(ErrorCode.ACCESS_DENIED);
        }
        if (!supported.contains(action.reach())) {
            throw AppException.withErrors(ErrorCode.DROPDOWN_REACH_UNSUPPORTED,
                    List.of(RequestError.atParameter(PARAMETER, RequestError.INVALID_VALUE)));
        }
        return action.reach();
    }
}
