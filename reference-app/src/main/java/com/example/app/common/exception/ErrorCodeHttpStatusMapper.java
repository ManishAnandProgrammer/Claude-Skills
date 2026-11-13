package com.example.app.common.exception;

import org.springframework.http.HttpStatus;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * The single place where {@link ErrorCode} meets {@link HttpStatus}. Another protocol would get its
 * own mapper without touching this one.
 *
 * <p>An {@code ErrorCode} without an entry makes {@link #resolve} throw, and
 * {@code ErrorCodeContractTest} fails the build before that can reach production.</p>
 */
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
        MAPPING.put(ErrorCode.REQUEST_INVALID, HttpStatus.BAD_REQUEST);
        MAPPING.put(ErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND);
        MAPPING.put(ErrorCode.ILLEGAL_ARGUMENT, HttpStatus.BAD_REQUEST);

        MAPPING.put(ErrorCode.AUTHENTICATION_REQUIRED, HttpStatus.UNAUTHORIZED);
        MAPPING.put(ErrorCode.ACCESS_DENIED, HttpStatus.FORBIDDEN);
        MAPPING.put(ErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS);

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
        MAPPING.put(ErrorCode.AUTH_INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED);
        MAPPING.put(ErrorCode.AUTH_INVALID_REFRESH_TOKEN, HttpStatus.UNAUTHORIZED);
        // example:begin
        MAPPING.put(ErrorCode.TODO_LIST_NOT_FOUND, HttpStatus.NOT_FOUND);
        MAPPING.put(ErrorCode.TODO_LIST_NAME_TAKEN, HttpStatus.CONFLICT);
        MAPPING.put(ErrorCode.TODO_LIST_IN_USE, HttpStatus.CONFLICT);
        MAPPING.put(ErrorCode.TODO_NOT_FOUND, HttpStatus.NOT_FOUND);
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
