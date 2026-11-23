package com.example.app.user;

import com.example.app.common.exception.ConstraintErrorCodes;
import com.example.app.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
class RoleConstraintErrorCodes implements ConstraintErrorCodes {

    @Override
    public Map<String, ErrorCode> byConstraintName() {
        return Map.of(
                "uq_roles_code", ErrorCode.ROLE_CODE_TAKEN,
                "fk_user_roles_role_id", ErrorCode.ROLE_IN_USE);
    }

    @Override
    public Map<String, ErrorCode> byForeignKeyOnWrite() {
        return Map.of("fk_user_roles_role_id", ErrorCode.ROLE_DELETED_MEANWHILE);
    }
}
