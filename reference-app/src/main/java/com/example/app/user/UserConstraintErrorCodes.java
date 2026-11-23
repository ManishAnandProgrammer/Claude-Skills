package com.example.app.user;

import com.example.app.common.exception.ConstraintErrorCodes;
import com.example.app.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
class UserConstraintErrorCodes implements ConstraintErrorCodes {

    @Override
    public Map<String, ErrorCode> byConstraintName() {
        return Map.of("uq_users_email", ErrorCode.USER_EMAIL_TAKEN);
    }
}
