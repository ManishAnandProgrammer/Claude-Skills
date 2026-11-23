package com.example.app.common.exception;

import com.example.app.common.ratelimit.RateLimitExceededException;
import com.example.app.common.web.ProblemTypes;
import com.example.app.common.web.RetryAfterProperties;
import jakarta.validation.ConstraintViolation;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Every 5xx carries one generic sentence: no class names, SQL, constraint names or framework messages ever
 * reach a client.
 */
@RestControllerAdvice
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String REPORTED_ON_ATTRIBUTE = "reportedOn";

    private final MessageSource messageSource;
    private final ProblemTypes problemTypes;
    private final RetryAfterProperties retryAfter;
    private final ConstraintViolationCodes constraintCodes;

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ProblemDetail> handleAppException(AppException exception, WebRequest request) {
        ErrorCode code = exception.getErrorCode();
        HttpStatus status = ErrorCodeHttpStatusMapper.resolve(code);
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            // The cause's class only: a message may carry the request's values.
            log.warn("Unavailable: code={}, cause={}", code, causeName(exception.getCause()));
            return withOwnMessage(code, request);
        }
        if (status.is5xxServerError()) {
            log.error("Server error: code={}", code, exception);
            return respond(code, serverError(status), request);
        }
        logClientError(status, code);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, message(code, exception.getArgs()));
        exception.getExtensions().forEach(problem::setProperty);
        if (exception.hasErrors()) {
            problem.setProperty(ProblemTypes.ERRORS, exception.getErrors());
        }
        return respond(code, problem, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        boolean body = isBody(exception.getParameter());
        List<RequestError> unconvertible = new ArrayList<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            if (error.isBindingFailure()) {
                unconvertible.add(located(body, error.getField(), RequestError.INVALID_VALUE));
            }
        }
        if (!unconvertible.isEmpty()) {
            log.debug("Unconvertible request values: fields={}", unconvertible.size());
            return withErrors(ErrorCode.REQUEST_PARAMETER_INVALID, unconvertible, request);
        }
        List<RequestError> errors = describe(body, exception.getBindingResult());
        log.debug("Validation failed: violations={}", errors.size());
        return withErrors(ErrorCode.VALIDATION_FAILED, errors, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<RequestError> errors = new ArrayList<>();
        for (ParameterValidationResult result : exception.getParameterValidationResults()) {
            boolean body = isBody(result.getMethodParameter());
            if (result instanceof ParameterErrors objectErrors) {
                errors.addAll(describe(body, objectErrors));
            } else {
                for (MessageSourceResolvable error : result.getResolvableErrors()) {
                    errors.add(body
                            ? RequestError.atPointer(JsonPointers.WHOLE_BODY, error.getDefaultMessage())
                            : RequestError.atParameter(result.getMethodParameter().getParameterName(), error.getDefaultMessage()));
                }
            }
        }
        log.debug("Parameter validation failed: violations={}", errors.size());
        return withErrors(ErrorCode.VALIDATION_FAILED, errors, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception,
                                                             Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode statusCode,
                                                             WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(exception, body, headers, statusCode, request);
        if (Objects.nonNull(response) && response.getBody() instanceof ProblemDetail problem) {
            ErrorCode code = MvcExceptionCodes.codeFor(exception, statusCode);
            if (statusCode.is5xxServerError()) {
                log.error("Server error in request handling: code={}", code, exception);
            } else {
                log.debug("Request refused: code={}, status={}", code, statusCode.value());
            }
            problem.setDetail(message(statusCode.is5xxServerError() ? ErrorCode.UNEXPECTED_ERROR : code));
            problem.setProperty(ProblemTypes.ERROR_CODE, code.name());
            List<RequestError> errors = MvcRequestErrors.of(exception);
            if (!statusCode.is5xxServerError() && !errors.isEmpty()) {
                problem.setProperty(ProblemTypes.ERRORS, errors);
            }
            problemTypes.setTypeAndInstance(problem, request);
            Optional<String> retryAfterSeconds = retryAfterSeconds(code);
            if (retryAfterSeconds.isPresent()) {
                return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                        .header(HttpHeaders.RETRY_AFTER, retryAfterSeconds.get()).body(problem);
            }
        }
        return response;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException exception, WebRequest request) {
        log.warn("Access denied");
        return withOwnMessage(ErrorCode.ACCESS_DENIED, request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException exception, WebRequest request) {
        log.warn("Authentication failed: {}", exception.getClass().getSimpleName());
        return withOwnMessage(ErrorCode.AUTHENTICATION_REQUIRED, request);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimited(RateLimitExceededException exception, WebRequest request) {
        log.warn("Rate limited: retryAfterSeconds={}", exception.getRetryAfterSeconds());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                ErrorCodeHttpStatusMapper.resolve(ErrorCode.RATE_LIMITED), message(ErrorCode.RATE_LIMITED));
        return respondWithRetryAfter(ErrorCode.RATE_LIMITED, problem, request,
                String.valueOf(exception.getRetryAfterSeconds()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException exception, WebRequest request) {
        Optional<ErrorCode> code = constraintCodes.codeFor(exception);
        if (code.isEmpty()) {
            log.error("Data integrity violation", exception);
            return respond(ErrorCode.DATA_INTEGRITY_ERROR, serverError(HttpStatus.INTERNAL_SERVER_ERROR), request);
        }
        log.debug("Constraint refused the write: code={}", code.get());
        return withOwnMessage(code.get(), request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(ObjectOptimisticLockingFailureException exception,
                                                              WebRequest request) {
        log.debug("Optimistic lock conflict: entity={}", exception.getPersistentClassName());
        return withOwnMessage(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, WebRequest request) {
        Optional<TransientDatabaseFailure> transientFailure = TransientDatabaseFailure.of(exception);
        if (transientFailure.isPresent()) {
            return transientDatabaseFailure(transientFailure.get(), exception, request);
        }
        log.error("Unexpected error", exception);
        return respond(ErrorCode.UNEXPECTED_ERROR, serverError(HttpStatus.INTERNAL_SERVER_ERROR), request);
    }

    private ResponseEntity<ProblemDetail> transientDatabaseFailure(TransientDatabaseFailure failure, Exception exception,
                                                                   WebRequest request) {
        if (failure == TransientDatabaseFailure.QUERY_TIMEOUT) {
            log.error("Database query timed out", exception);
        } else {
            // The cause's class and SQL state only: a message may carry the statement's values.
            log.warn("Database busy or unavailable: code={}, cause={}, sqlState={}", failure.errorCode(),
                    exception.getClass().getSimpleName(), sqlStateOf(exception));
        }
        return withOwnMessage(failure.errorCode(), request);
    }

    private static String causeName(Throwable cause) {
        return Objects.isNull(cause) ? "none" : cause.getClass().getSimpleName();
    }

    private static String sqlStateOf(Throwable exception) {
        for (Throwable cause = exception; Objects.nonNull(cause); cause = cause.getCause()) {
            if (cause instanceof SQLException failure && Objects.nonNull(failure.getSQLState())) {
                return failure.getSQLState();
            }
        }
        return "none";
    }

    private ResponseEntity<Object> withErrors(ErrorCode code, List<RequestError> errors, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ErrorCodeHttpStatusMapper.resolve(code), message(code));
        problem.setProperty(ProblemTypes.ERRORS, errors);
        return problemBuilder(code, problem, request).body(problem);
    }

    private ResponseEntity<ProblemDetail> withOwnMessage(ErrorCode code, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ErrorCodeHttpStatusMapper.resolve(code), message(code));
        return respond(code, problem, request);
    }

    private ProblemDetail serverError(HttpStatus status) {
        return ProblemDetail.forStatusAndDetail(status, message(ErrorCode.UNEXPECTED_ERROR));
    }

    private static List<RequestError> describe(boolean body, Errors errors) {
        List<RequestError> described = new ArrayList<>();
        for (FieldError error : errors.getFieldErrors()) {
            described.add(located(body, reportedField(error), error.getDefaultMessage()));
        }
        for (ObjectError error : errors.getGlobalErrors()) {
            described.add(RequestError.atPointer(JsonPointers.WHOLE_BODY, error.getDefaultMessage()));
        }
        return described;
    }

    private static String reportedField(FieldError error) {
        if (!error.contains(ConstraintViolation.class)) {
            return error.getField();
        }
        Object reportedOn = error.unwrap(ConstraintViolation.class).getConstraintDescriptor().getAttributes().get(REPORTED_ON_ATTRIBUTE);
        if (!(reportedOn instanceof String sibling) || sibling.isEmpty()) {
            return error.getField();
        }
        String path = error.getField();
        return path.substring(0, path.lastIndexOf('.') + 1) + sibling;
    }

    private static RequestError located(boolean body, String field, String detail) {
        return body
                ? RequestError.atPointer(JsonPointers.fromPropertyPath(field), detail)
                : RequestError.atParameter(field, detail);
    }

    private static boolean isBody(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(RequestBody.class);
    }

    private static void logClientError(HttpStatus status, ErrorCode code) {
        if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
            log.warn("Refused: code={}, status={}", code, status.value());
        } else {
            log.debug("Client error: code={}, status={}", code, status.value());
        }
    }

    private String message(ErrorCode code, Object... args) {
        return messageSource.getMessage(code.name(), args, LocaleContextHolder.getLocale());
    }

    private ResponseEntity<ProblemDetail> respond(ErrorCode code, ProblemDetail problem, WebRequest request) {
        return problemBuilder(code, problem, request).body(problem);
    }

    private ResponseEntity<ProblemDetail> respondWithRetryAfter(ErrorCode code, ProblemDetail problem,
                                                                WebRequest request, String retryAfterSeconds) {
        return problemBuilder(code, problem, request).header(HttpHeaders.RETRY_AFTER, retryAfterSeconds).body(problem);
    }

    private ResponseEntity.BodyBuilder problemBuilder(ErrorCode code, ProblemDetail problem, WebRequest request) {
        problem.setProperty(ProblemTypes.ERROR_CODE, code.name());
        problemTypes.setTypeAndInstance(problem, request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(problem.getStatus())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        Optional<String> retryAfterSeconds = retryAfterSeconds(code);
        if (retryAfterSeconds.isPresent()) {
            builder.header(HttpHeaders.RETRY_AFTER, retryAfterSeconds.get());
        }
        return builder;
    }

    private Optional<String> retryAfterSeconds(ErrorCode code) {
        return retryAfter.rangeFor(code).map(RetryAfterProperties.Range::pickSeconds);
    }
}
