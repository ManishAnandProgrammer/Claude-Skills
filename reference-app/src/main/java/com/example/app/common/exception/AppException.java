package com.example.app.common.exception;

import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@Getter
public class AppException extends RuntimeException {

    private static final Set<String> RESERVED_EXTENSIONS =
            Set.of("type", "title", "status", "detail", "instance", "errorCode", "errors");

    private final ErrorCode errorCode;
    private final transient Object[] args;
    private final transient List<RequestError> errors;
    private final transient Map<String, Object> extensions = new LinkedHashMap<>();

    protected AppException(ErrorCode errorCode, Object[] args, List<RequestError> errors, Throwable cause) {
        super(errorCode.name(), cause);
        this.errorCode = errorCode;
        this.args = args;
        this.errors = errors;
    }

    public static AppException of(ErrorCode errorCode, Object... args) {
        return new AppException(errorCode, args, List.of(), null);
    }

    public static AppException withCause(ErrorCode errorCode, Throwable cause, Object... args) {
        return new AppException(errorCode, args, List.of(), cause);
    }

    public static AppException withErrors(ErrorCode errorCode, List<RequestError> errors) {
        return new AppException(errorCode, new Object[0], List.copyOf(errors), null);
    }

    public static Supplier<AppException> supply(ErrorCode errorCode, Object... args) {
        return () -> of(errorCode, args);
    }

    public AppException withExtension(String name, Object value) {
        if (RESERVED_EXTENSIONS.contains(name)) {
            throw new IllegalArgumentException("'%s' is already a member of every error response".formatted(name));
        }
        extensions.put(name, value);
        return this;
    }

    public Map<String, Object> getExtensions() {
        return Collections.unmodifiableMap(extensions);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}
