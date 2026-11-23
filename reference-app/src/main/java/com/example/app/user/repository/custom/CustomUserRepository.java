package com.example.app.user.repository.custom;

import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.user.domain.Role;

import java.util.Set;
import java.util.UUID;

public interface CustomUserRepository {

    /** {@code leftOut}, an account the batch never holds; null for none. */
    CursorBatch<DropdownItem> findActiveWithRoleIn(Set<Role> roles, UUID leftOut, DropdownFilter filter, int size);
}
