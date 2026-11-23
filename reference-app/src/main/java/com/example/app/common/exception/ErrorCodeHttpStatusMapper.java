package com.example.app.common.exception;

import org.springframework.http.HttpStatus;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public final class ErrorCodeHttpStatusMapper {

    private static final Map<ErrorCode, HttpStatus> MAPPING = new EnumMap<>(ErrorCode.class);

    static {
        MAPPING.put(ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST);
        MAPPING.put(ErrorCode.REQUEST_UNREADABLE, HttpStatus.BAD_REQUEST);
        MAPPING.put(ErrorCode.REQUEST_PARAMETER_MISSING, HttpStatus.BAD_REQUEST);
        MAPPING.put(ErrorCode.REQUEST_PARAMETER_INVALID, HttpStatus.BAD_REQUEST);
        MAPPING.put(ErrorCode.REQUEST_METHOD_NOT_ALLOWED, HttpStatus.METHOD_NOT_ALLOWED);
        MAPPING.put(ErrorCode.REQUEST_MEDIA_TYPE_UNSUPPORTED, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        MAPPING.put(ErrorCode.REQUEST_NOT_ACCEPTABLE, HttpStatus.NOT_ACCEPTABLE);
        MAPPING.put(ErrorCode.API_VERSION_UNSUPPORTED, HttpStatus.BAD_REQUEST);
        MAPPING.put(ErrorCode.REQUEST_INVALID, HttpStatus.BAD_REQUEST);
        MAPPING.put(ErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND);

        MAPPING.put(ErrorCode.AUTHENTICATION_REQUIRED, HttpStatus.UNAUTHORIZED);
        MAPPING.put(ErrorCode.ACCESS_DENIED, HttpStatus.FORBIDDEN);
        MAPPING.put(ErrorCode.DROPDOWN_REACH_UNSUPPORTED, HttpStatus.BAD_REQUEST);
        MAPPING.put(ErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS);
        MAPPING.put(ErrorCode.CORS_REQUEST_REFUSED, HttpStatus.FORBIDDEN);

        MAPPING.put(ErrorCode.DUPLICATE_RECORD, HttpStatus.CONFLICT);
        MAPPING.put(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, HttpStatus.CONFLICT);
        MAPPING.put(ErrorCode.DATA_INTEGRITY_ERROR, HttpStatus.INTERNAL_SERVER_ERROR);

        MAPPING.put(ErrorCode.DATABASE_CONTENTION, HttpStatus.SERVICE_UNAVAILABLE);
        MAPPING.put(ErrorCode.QUERY_TIMEOUT, HttpStatus.SERVICE_UNAVAILABLE);
        MAPPING.put(ErrorCode.DATABASE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE);
        MAPPING.put(ErrorCode.SERVER_BUSY, HttpStatus.SERVICE_UNAVAILABLE);
        MAPPING.put(ErrorCode.UNEXPECTED_ERROR, HttpStatus.INTERNAL_SERVER_ERROR);

        MAPPING.put(ErrorCode.USER_NOT_FOUND, HttpStatus.NOT_FOUND);
        MAPPING.put(ErrorCode.USER_EMAIL_TAKEN, HttpStatus.CONFLICT);
        MAPPING.put(ErrorCode.USER_PASSWORD_BREACHED, HttpStatus.BAD_REQUEST);
        MAPPING.put(ErrorCode.USER_PASSWORD_INCORRECT, HttpStatus.BAD_REQUEST);
        // tenancy:begin
        // tenancy:else
        MAPPING.put(ErrorCode.USER_OWN_STATUS_CHANGE, HttpStatus.FORBIDDEN);
        // tenancy:end
        MAPPING.put(ErrorCode.AUTH_INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED);
        MAPPING.put(ErrorCode.AUTH_INVALID_REFRESH_TOKEN, HttpStatus.UNAUTHORIZED);
        MAPPING.put(ErrorCode.AUTH_ACCOUNT_SUSPENDED, HttpStatus.FORBIDDEN);
        // session-version:begin
        MAPPING.put(ErrorCode.SESSION_VERSION_STALE, HttpStatus.UNAUTHORIZED);
        // session-version:end
        // roles:begin
        // MAPPING.put(ErrorCode.ROLE_NOT_FOUND, HttpStatus.NOT_FOUND);
        // MAPPING.put(ErrorCode.ROLE_DELETED_MEANWHILE, HttpStatus.NOT_FOUND);
        // MAPPING.put(ErrorCode.ROLE_CODE_TAKEN, HttpStatus.CONFLICT);
        // MAPPING.put(ErrorCode.ROLE_IN_USE, HttpStatus.CONFLICT);
        // MAPPING.put(ErrorCode.ROLE_BUILT_IN, HttpStatus.CONFLICT);
        // MAPPING.put(ErrorCode.ROLE_PREREQUISITE_MISSING, HttpStatus.BAD_REQUEST);
        // MAPPING.put(ErrorCode.ACCESS_NOT_HELD, HttpStatus.FORBIDDEN);
        // MAPPING.put(ErrorCode.ACCESS_OWN_CHANGE, HttpStatus.FORBIDDEN);
        // MAPPING.put(ErrorCode.ACCESS_TARGET_HOLDS_MORE, HttpStatus.FORBIDDEN);
        // MAPPING.put(ErrorCode.ACCESS_LAST_MANAGER, HttpStatus.CONFLICT);
        // roles:end
        // tenancy:begin
        // MAPPING.put(ErrorCode.TENANT_NOT_ACTIVE, HttpStatus.FORBIDDEN);
        // MAPPING.put(ErrorCode.TENANT_MEMBERSHIP_REQUIRED, HttpStatus.FORBIDDEN);
        // MAPPING.put(ErrorCode.MEMBERSHIP_NOT_FOUND, HttpStatus.NOT_FOUND);
        // MAPPING.put(ErrorCode.TENANT_NOT_FOUND, HttpStatus.NOT_FOUND);
        // MAPPING.put(ErrorCode.TENANT_OWNER_NOT_FOUND, HttpStatus.NOT_FOUND);
        // MAPPING.put(ErrorCode.TENANT_STATUS_CHANGE_INVALID, HttpStatus.CONFLICT);
        // MAPPING.put(ErrorCode.SUPPORT_ACCESS_DENIED, HttpStatus.FORBIDDEN);
        // MAPPING.put(ErrorCode.TENANT_MEMBER_NOT_FOUND, HttpStatus.NOT_FOUND);
        // MAPPING.put(ErrorCode.TENANT_MEMBER_ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND);
        // MAPPING.put(ErrorCode.TENANT_MEMBER_EXISTS, HttpStatus.CONFLICT);
        // roles:begin
        // roles:else
        // MAPPING.put(ErrorCode.TENANT_MEMBER_LAST_ADMIN, HttpStatus.CONFLICT);
        // roles:end
        // MAPPING.put(ErrorCode.TENANT_MEMBER_OWN_CHANGE, HttpStatus.FORBIDDEN);
        // MAPPING.put(ErrorCode.PLATFORM_STAFF_NOT_FOUND, HttpStatus.NOT_FOUND);
        // MAPPING.put(ErrorCode.PLATFORM_STAFF_OWN_DEACTIVATION, HttpStatus.FORBIDDEN);
        // tenancy:end
        // metadata:begin
        MAPPING.put(ErrorCode.METADATA_KEY_UNKNOWN, HttpStatus.NOT_FOUND);
        MAPPING.put(ErrorCode.METADATA_VALUE_MISSING, HttpStatus.NOT_FOUND);
        MAPPING.put(ErrorCode.METADATA_KEY_TAKEN, HttpStatus.CONFLICT);
        // metadata:end
        // media:begin
        MAPPING.put(ErrorCode.UPLOAD_POLICY_NOT_SEEDED, HttpStatus.NOT_FOUND);
        MAPPING.put(ErrorCode.UPLOAD_SIZE_OUT_OF_RANGE, HttpStatus.BAD_REQUEST);
        MAPPING.put(ErrorCode.UPLOAD_TYPE_NOT_ALLOWED, HttpStatus.BAD_REQUEST);
        MAPPING.put(ErrorCode.UPLOAD_FILE_COUNT_OUT_OF_RANGE, HttpStatus.BAD_REQUEST);
        MAPPING.put(ErrorCode.UPLOAD_NOT_AVAILABLE, HttpStatus.CONFLICT);
        MAPPING.put(ErrorCode.MEDIA_STORAGE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE);
        // media:end
        // example:begin
        MAPPING.put(ErrorCode.TODO_LIST_NOT_FOUND, HttpStatus.NOT_FOUND);
        MAPPING.put(ErrorCode.TODO_LIST_NAME_TAKEN, HttpStatus.CONFLICT);
        MAPPING.put(ErrorCode.TODO_LIST_IN_USE, HttpStatus.CONFLICT);
        MAPPING.put(ErrorCode.TODO_LIST_DELETED_MEANWHILE, HttpStatus.NOT_FOUND);
        MAPPING.put(ErrorCode.TODO_NOT_FOUND, HttpStatus.NOT_FOUND);
        MAPPING.put(ErrorCode.TODO_NOT_YOURS, HttpStatus.FORBIDDEN);
        MAPPING.put(ErrorCode.TODO_IN_USE, HttpStatus.CONFLICT);
        MAPPING.put(ErrorCode.TODO_DELETED_MEANWHILE, HttpStatus.NOT_FOUND);
        MAPPING.put(ErrorCode.TIME_ENTRY_NOT_FOUND, HttpStatus.NOT_FOUND);
        MAPPING.put(ErrorCode.TAG_NOT_FOUND, HttpStatus.NOT_FOUND);
        MAPPING.put(ErrorCode.TAG_NAME_TAKEN, HttpStatus.CONFLICT);
        MAPPING.put(ErrorCode.TAG_DELETED_MEANWHILE, HttpStatus.NOT_FOUND);
        MAPPING.put(ErrorCode.TAG_MERGE_INTO_ITSELF, HttpStatus.BAD_REQUEST);
        // media:begin
        MAPPING.put(ErrorCode.TODO_ATTACHMENT_NOT_FOUND, HttpStatus.NOT_FOUND);
        // media:end
        MAPPING.put(ErrorCode.PLAN_NOT_FOUND, HttpStatus.NOT_FOUND);
        MAPPING.put(ErrorCode.PLAN_OVERLAPS, HttpStatus.CONFLICT);
        MAPPING.put(ErrorCode.PLAN_TODO_DONE, HttpStatus.CONFLICT);
        MAPPING.put(ErrorCode.PLAN_STATUS_CHANGE_INVALID, HttpStatus.CONFLICT);
        MAPPING.put(ErrorCode.PLAN_DONE_ON_BEFORE_STARTS_ON, HttpStatus.BAD_REQUEST);
        MAPPING.put(ErrorCode.PLAN_DONE, HttpStatus.CONFLICT);
        MAPPING.put(ErrorCode.PLAN_CANCELLED, HttpStatus.CONFLICT);
        // example:end
    }

    private ErrorCodeHttpStatusMapper() {
    }

    public static HttpStatus resolve(ErrorCode errorCode) {
        HttpStatus status = MAPPING.get(errorCode);
        if (Objects.isNull(status)) {
            throw new IllegalStateException("ErrorCode %s has no HTTP status mapping".formatted(errorCode));
        }
        return status;
    }
}
