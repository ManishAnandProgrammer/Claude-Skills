package com.example.app.common.exception;

import com.example.app.common.ratelimit.RateLimitExceededException;
import com.example.app.common.web.ProblemTypes;
import com.example.app.common.web.RetryAfterProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
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
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Turns every exception into RFC 9457 Problem Details with the same members: {@code type}
 * ({@code urn:<application>:error:<CODE>}), {@code title}, {@code status}, {@code detail}, {@code instance}
 * (the request path) and {@code errorCode}; {@code failed} for validation errors. The request id
 * travels in the {@code X-Request-ID} header and the log context. Every status comes from
 * {@link ErrorCodeHttpStatusMapper}.
 *
 * <p>Client errors carry their message from the MessageSource. Every 5xx carries one generic
 * sentence and is logged at ERROR with its stack: no class names, SQL, constraint names or
 * framework messages ever reach a client.</p>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String INVALID_VALUE = ": invalid value";

    private final MessageSource messageSource;
    private final ProblemTypes problemTypes;
    private final RetryAfterProperties retryAfter;
    private final ConstraintViolationCodes constraintCodes;

    GlobalExceptionHandler(MessageSource messageSource, ProblemTypes problemTypes, RetryAfterProperties retryAfter,
                           ConstraintViolationCodes constraintCodes) {
        this.messageSource = messageSource;
        this.problemTypes = problemTypes;
        this.retryAfter = retryAfter;
        this.constraintCodes = constraintCodes;
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ProblemDetail> handleAppException(AppException exception, WebRequest request) {
        ErrorCode code = exception.getErrorCode();
        HttpStatus status = ErrorCodeHttpStatusMapper.resolve(code);
        if (status.is5xxServerError()) {
            log.error("Server error: code={}", code, exception);
            return respond(code, serverError(status), request);
        }
        logClientError(status, code);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, message(code, exception.getArgs()));
        exception.getExtensions().forEach(problem::setProperty);
        if (exception.hasFailedValidations()) {
            problem.setProperty(ProblemTypes.FAILED, exception.getFailedValidations());
        }
        return respond(code, problem, request);
    }

    /**
     * A body or query object that failed. A value that couldn't even be converted (an unknown enum
     * constant, a malformed date or UUID) is {@code REQUEST_PARAMETER_INVALID}, naming only the field:
     * the conversion message names Java classes. Constraint failures are {@code VALIDATION_FAILED}
     * with their messages.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<String> unconvertible = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            if (error.isBindingFailure()) {
                unconvertible.add(error.getField() + INVALID_VALUE);
            } else {
                failed.add(error.getField() + ": " + error.getDefaultMessage());
            }
        }
        for (ObjectError error : exception.getBindingResult().getGlobalErrors()) {
            failed.add(error.getObjectName() + ": " + error.getDefaultMessage());
        }
        if (!unconvertible.isEmpty()) {
            log.debug("Unconvertible request values: fields={}", unconvertible.size());
            return withFailures(ErrorCode.REQUEST_PARAMETER_INVALID, unconvertible, request);
        }
        log.debug("Validation failed: violations={}", failed.size());
        return withFailures(ErrorCode.VALIDATION_FAILED, failed, request);
    }

    /** A constraint on a {@code @RequestParam} or {@code @PathVariable}: the same answer as a body field. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<String> failed = exception.getParameterValidationResults().stream()
                .flatMap(GlobalExceptionHandler::describeFailures)
                .toList();
        log.debug("Parameter validation failed: violations={}", failed.size());
        return withFailures(ErrorCode.VALIDATION_FAILED, failed, request);
    }

    /**
     * Every other exception Spring MVC handles arrives here already as Problem Details. Each gets an
     * {@code errorCode} and a message of ours (a specific one where it exists, else by status), and
     * keeps Spring's headers ({@code Allow} on a 405). A 5xx among them is a bug: logged with its
     * stack, answered with the generic text.
     */
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
            problemTypes.identify(problem, request);
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
        return respond(ErrorCode.RATE_LIMITED, problem, request, String.valueOf(exception.getRetryAfterSeconds()));
    }

    /** A constraint without a meaning for clients is a bug: 500, with the constraint logged, never returned. */
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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException exception, WebRequest request) {
        log.debug("Illegal argument: {}", exception.getMessage());
        return withOwnMessage(ErrorCode.ILLEGAL_ARGUMENT, request);
    }

    /**
     * The catch-all, after the transient database failures: a lock wait, a statement timeout or no
     * connection is 503 with a jittered {@code Retry-After}; anything else is the generic 500.
     */
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
            log.warn("Database busy or unavailable: code={}, cause={}", failure.errorCode(), exception.toString());
        }
        return withOwnMessage(failure.errorCode(), request);
    }

    private ResponseEntity<Object> withFailures(ErrorCode code, List<String> failed, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ErrorCodeHttpStatusMapper.resolve(code), message(code));
        problem.setProperty(ProblemTypes.FAILED, failed);
        return problemBuilder(code, problem, request).body(problem);
    }

    /** The code's own message: client errors, and the 503s a client should come back from. */
    private ResponseEntity<ProblemDetail> withOwnMessage(ErrorCode code, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ErrorCodeHttpStatusMapper.resolve(code), message(code));
        return respond(code, problem, request);
    }

    private ProblemDetail serverError(HttpStatus status) {
        return ProblemDetail.forStatusAndDetail(status, message(ErrorCode.UNEXPECTED_ERROR));
    }

    private static Stream<String> describeFailures(ParameterValidationResult result) {
        String parameter = result.getMethodParameter().getParameterName();
        return result.getResolvableErrors().stream().map(error -> parameter + ": " + error.getDefaultMessage());
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

    /** With the wait the limiter computed, which no configured range can know. */
    private ResponseEntity<ProblemDetail> respond(ErrorCode code, ProblemDetail problem, WebRequest request,
                                                  String retryAfterSeconds) {
        return problemBuilder(code, problem, request).header(HttpHeaders.RETRY_AFTER, retryAfterSeconds).body(problem);
    }

    /** Every 503 carries a jittered {@code Retry-After} from its code's range. */
    private ResponseEntity.BodyBuilder problemBuilder(ErrorCode code, ProblemDetail problem, WebRequest request) {
        problem.setProperty(ProblemTypes.ERROR_CODE, code.name());
        problemTypes.identify(problem, request);
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
