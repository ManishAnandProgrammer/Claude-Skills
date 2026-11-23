package com.example.app.user.repository.custom;

import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.user.domain.Role;

import java.util.Set;

public interface CustomUserRepository {

    CursorBatch<DropdownItem> findActiveWithRoleIn(Set<Role> roles, DropdownFilter filter, int size);
}
