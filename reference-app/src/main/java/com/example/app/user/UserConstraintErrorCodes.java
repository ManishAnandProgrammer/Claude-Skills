package com.example.app.user;

import com.example.app.common.exception.ConstraintErrorCodes;
import com.example.app.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Signup races past the service's existence check end on the unique email: answered as a taken email. */
@Component
class UserConstraintErrorCodes implements ConstraintErrorCodes {

    @Override
    public Map<String, ErrorCode> byConstraintName() {
        return Map.of("uq_users_email", ErrorCode.USER_EMAIL_TAKEN);
    }
}
