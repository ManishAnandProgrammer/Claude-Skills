package com.example.app.user;

import com.example.app.common.exception.ConstraintErrorCodes;
import com.example.app.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Adding someone who is already a member ends on the unique pair, also when two admins add them at once. */
@Component
class MembershipConstraintErrorCodes implements ConstraintErrorCodes {

    @Override
    public Map<String, ErrorCode> byConstraintName() {
        return Map.of("uq_tenant_memberships_user_id_tenant_id", ErrorCode.TENANT_MEMBER_EXISTS);
    }
}
