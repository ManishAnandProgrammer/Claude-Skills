package com.example.app.common.exception;

import org.springframework.beans.TypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

import java.util.List;
import java.util.Objects;

/**
 * The {@code errors} of a 400 Spring MVC answers itself, when it knows the value: the parameter that is
 * missing or couldn't be converted, the body value Jackson couldn't read. Empty when it names none (a
 * body that isn't JSON at all, a missing header). Never the exception's message: it names Java classes.
 */
final class MvcRequestErrors {

    private static final String MISSING = "must be present";

    private MvcRequestErrors() {
    }

    static List<RequestError> of(Exception exception) {
        return switch (exception) {
            case HttpMessageNotReadableException unreadable -> unreadableValue(unreadable);
            case MissingServletRequestParameterException missing ->
                    List.of(RequestError.atParameter(missing.getParameterName(), MISSING));
            case TypeMismatchException mismatch when Objects.nonNull(mismatch.getPropertyName()) ->
                    List.of(RequestError.atParameter(mismatch.getPropertyName(), RequestError.INVALID_VALUE));
            default -> List.of();
        };
    }

    /** Jackson's path to the value it stopped at: free, so the client learns which one; none for broken syntax. */
    private static List<RequestError> unreadableValue(HttpMessageNotReadableException exception) {
        if (!(exception.getCause() instanceof JacksonException jackson) || jackson.getPath().isEmpty()) {
            return List.of();
        }
        String detail = jackson instanceof UnrecognizedPropertyException ? RequestError.UNKNOWN_FIELD : RequestError.INVALID_VALUE;
        return List.of(RequestError.atPointer(JsonPointers.of(JsonPointers.tokensOf(jackson)), detail));
    }
}
