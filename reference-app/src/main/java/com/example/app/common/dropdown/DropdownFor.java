package com.example.app.common.dropdown;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.exception.RequestError;
import com.example.app.common.security.Permission;
import com.example.app.common.security.Reach;

import java.util.List;
import java.util.Set;

public final class DropdownFor {

    public static final String PARAMETER = "for";

    private DropdownFor() {
    }

    public static Reach requireReach(Permission action, Set<Permission> held, Set<Reach> supported) {
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
