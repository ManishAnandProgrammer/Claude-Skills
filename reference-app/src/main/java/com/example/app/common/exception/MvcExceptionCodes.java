package com.example.app.common.exception;

import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.accept.InvalidApiVersionException;
import org.springframework.web.accept.MissingApiVersionException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Optional;

/**
 * The {@link ErrorCode} of an exception Spring MVC answers itself (a malformed body, a missing
 * parameter, a wrong method, an unsupported API version, an async timeout): a specific code where one exists, else one by status
 * class.
 */
final class MvcExceptionCodes {

    private MvcExceptionCodes() {
    }

    static ErrorCode codeFor(Exception exception, HttpStatusCode status) {
        return specificCode(exception).orElseGet(() -> byStatus(status));
    }

    private static Optional<ErrorCode> specificCode(Exception exception) {
        return switch (exception) {
            case HttpMessageNotReadableException _ -> Optional.of(ErrorCode.REQUEST_UNREADABLE);
            case MissingServletRequestParameterException _ -> Optional.of(ErrorCode.REQUEST_PARAMETER_MISSING);
            case ServletRequestBindingException _ -> Optional.of(ErrorCode.REQUEST_PARAMETER_MISSING);
            // A 500 (no converter at all), so it must be matched before its parent TypeMismatchException.
            case ConversionNotSupportedException _ -> Optional.of(ErrorCode.UNEXPECTED_ERROR);
            case TypeMismatchException _ -> Optional.of(ErrorCode.REQUEST_PARAMETER_INVALID);
            case HttpRequestMethodNotSupportedException _ -> Optional.of(ErrorCode.REQUEST_METHOD_NOT_ALLOWED);
            case HttpMediaTypeNotSupportedException _ -> Optional.of(ErrorCode.REQUEST_MEDIA_TYPE_UNSUPPORTED);
            case HttpMediaTypeNotAcceptableException _ -> Optional.of(ErrorCode.REQUEST_NOT_ACCEPTABLE);
            // NotAcceptableApiVersionException (a fixed-version mapping asked for another) is an InvalidApiVersionException.
            case MissingApiVersionException _, InvalidApiVersionException _ -> Optional.of(ErrorCode.API_VERSION_UNSUPPORTED);
            case NoResourceFoundException _, NoHandlerFoundException _ -> Optional.of(ErrorCode.RESOURCE_NOT_FOUND);
            case AsyncRequestTimeoutException _ -> Optional.of(ErrorCode.SERVER_BUSY);
            default -> Optional.empty();
        };
    }

    private static ErrorCode byStatus(HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return ErrorCode.UNEXPECTED_ERROR;
        }
        return status.value() == HttpStatus.NOT_FOUND.value() ? ErrorCode.RESOURCE_NOT_FOUND : ErrorCode.REQUEST_INVALID;
    }
}
