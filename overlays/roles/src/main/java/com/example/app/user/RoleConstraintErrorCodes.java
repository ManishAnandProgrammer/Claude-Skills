package com.example.app.user;

import com.example.app.common.exception.ConstraintErrorCodes;
import com.example.app.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The roles piece's constraints: a taken role code, and a role's key by the side that trips it. A role's delete is
 * refused while somebody holds it (an assignment's key); an assignment written while its role was deleted answers the
 * role's gone code. The others can't trip: a role's permissions go with it through its aggregate, and a person's
 * assignments and grants are written under the lock of their row, which is never deleted.
 */
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
        return Map.of("fk_user_roles_role_id", ErrorCode.ROLE_GONE);
    }
}
