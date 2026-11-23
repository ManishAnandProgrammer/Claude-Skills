package com.example.app.user.service;

import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownFor;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.dropdown.DropdownStatus;
import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.exception.RequestError;
import com.example.app.common.pagination.Cursor;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.pagination.Paging;
import com.example.app.common.security.CurrentUser;
import com.example.app.common.security.Permission;
import com.example.app.common.security.Reach;
import com.example.app.common.wrapper.CursorWrapper;
import com.example.app.user.api.NamedParty;
import com.example.app.user.api.PermissionHolders;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;

@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Transactional(readOnly = true)
public class NamedPartyService {

    private final PermissionHolders holders;
    private final CurrentUser currentUser;
    private final Paging paging;

    public CursorWrapper<DropdownItem> dropdown(Permission write, DropdownFilter filter) {
        DropdownFor.requireReach(write, currentUser.permissions(), EnumSet.allOf(Reach.class));
        if (filter.getStatus() != DropdownStatus.ACTIVE) {
            throw invalid("status");
        }
        // The request's own parameters before the lookup, as every list: a tampered cursor is refused whatever for names.
        Cursor.read(filter.getAfter(), PermissionHolders.sortOf(filter), PermissionHolders.CURSOR_KEYS);
        NamedParty party = NamedParty.namedBy(write).orElseThrow(() -> invalid(DropdownFor.PARAMETER));
        CursorBatch<DropdownItem> batch = holders.findHolders(party.mustHold(), filter, paging.cursorSize(filter));
        return CursorWrapper.of(batch.items(), batch.nextCursor());
    }

    private static AppException invalid(String parameter) {
        return AppException.withErrors(ErrorCode.REQUEST_PARAMETER_INVALID,
                List.of(RequestError.atParameter(parameter, RequestError.INVALID_VALUE)));
    }
}
