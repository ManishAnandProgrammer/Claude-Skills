package com.example.app.common.exception;

import com.example.app.common.ratelimit.RateLimitExceededException;
import com.example.app.common.web.ProblemTypes;
import com.example.app.common.web.RetryAfterProperties;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    private static final String REQUEST_DESCRIPTION_URI_PREFIX = "uri=";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String INVALID_VALUE = ": invalid value";

    /**
     * Constraints whose violation has a domain meaning: unique constraints ({@code *_TAKEN}) and
     * the foreign keys that refuse a delete while other rows point at the record ({@code *_IN_USE}).
     * Inserts never trip those foreign keys: the services lock the parent for the write. Any other
     * unique violation is the generic {@code DUPLICATE_RECORD}. Names are the ones in the
     * Liquibase changesets.
     */
    private static final Map<String, ErrorCode> CONSTRAINT_CODES = Map.of(
            "uq_users_email", ErrorCode.USER_EMAIL_TAKEN,
            "uq_todo_lists_owner_id_name", ErrorCode.TODO_LIST_NAME_TAKEN,
            "fk_todos_todo_list_id_owner_id", ErrorCode.TODO_LIST_IN_USE);

    private final MessageSource messageSource;
    private final ProblemTypes problemTypes;
    private final RetryAfterProperties retryAfter;

    public GlobalExceptionHandler(MessageSource messageSource, ProblemTypes problemTypes, RetryAfterProperties retryAfter) {
        this.messageSource = messageSource;
        this.problemTypes = problemTypes;
        this.retryAfter = retryAfter;
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
            ErrorCode code = requestRefusalCode(exception).orElseGet(() -> codeForStatus(statusCode));
            if (statusCode.is5xxServerError()) {
                log.error("Server error in request handling: code={}", code, exception);
            } else {
                log.debug("Request refused: code={}, status={}", code, statusCode.value());
            }
            problem.setDetail(message(statusCode.is5xxServerError() ? ErrorCode.UNEXPECTED_ERROR : code));
            problem.setProperty(ProblemTypes.ERROR_CODE, code.name());
            identify(problem, request);
        }
        return response;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException exception, WebRequest request) {
        log.warn("Access denied");
        return clientError(ErrorCode.ACCESS_DENIED, request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException exception, WebRequest request) {
        log.warn("Authentication failed: {}", exception.getClass().getSimpleName());
        return clientError(ErrorCode.AUTHENTICATION_REQUIRED, request);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimited(RateLimitExceededException exception, WebRequest request) {
        log.warn("Rate limited: retryAfterSeconds={}", exception.getRetryAfterSeconds());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                ErrorCodeHttpStatusMapper.resolve(ErrorCode.RATE_LIMITED), message(ErrorCode.RATE_LIMITED));
        return respond(ErrorCode.RATE_LIMITED, problem, request, String.valueOf(exception.getRetryAfterSeconds()));
    }

    /**
     * A constraint the database enforced: the domain code when the constraint has one (a taken
     * name, a record still in use), else {@code DUPLICATE_RECORD} for any unique violation.
     * Anything else is a bug: 500, with the constraint logged, never returned.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException exception, WebRequest request) {
        Optional<ErrorCode> code = constraintViolation(exception).flatMap(GlobalExceptionHandler::constraintCode);
        if (code.isEmpty()) {
            log.error("Data integrity violation", exception);
            return respond(ErrorCode.DATA_INTEGRITY_ERROR,
                    serverError(HttpStatus.INTERNAL_SERVER_ERROR), request);
        }
        log.debug("Constraint refused the write: code={}", code.get());
        return clientError(code.get(), request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(ObjectOptimisticLockingFailureException exception,
                                                              WebRequest request) {
        log.debug("Optimistic lock conflict: entity={}", exception.getPersistentClassName());
        return clientError(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException exception, WebRequest request) {
        log.debug("Illegal argument: {}", exception.getMessage());
        return clientError(ErrorCode.ILLEGAL_ARGUMENT, request);
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
        return respond(ErrorCode.UNEXPECTED_ERROR,
                serverError(HttpStatus.INTERNAL_SERVER_ERROR), request);
    }

    private ResponseEntity<ProblemDetail> transientDatabaseFailure(TransientDatabaseFailure failure, Exception exception,
                                                                   WebRequest request) {
        if (failure == TransientDatabaseFailure.QUERY_TIMEOUT) {
            log.error("Database query timed out", exception);
        } else {
            log.warn("Database busy or unavailable: code={}, cause={}", failure.errorCode(), exception.toString());
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                ErrorCodeHttpStatusMapper.resolve(failure.errorCode()), message(failure.errorCode()));
        return respond(failure.errorCode(), problem, request, failure.retryAfter(retryAfter).pickSeconds());
    }

    private ResponseEntity<Object> withFailures(ErrorCode code, List<String> failed, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ErrorCodeHttpStatusMapper.resolve(code), message(code));
        problem.setProperty(ProblemTypes.FAILED, failed);
        return problemBuilder(code, problem, request).body(problem);
    }

    private ResponseEntity<ProblemDetail> clientError(ErrorCode code, WebRequest request) {
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

    private static Optional<ErrorCode> requestRefusalCode(Exception exception) {
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
            case NoResourceFoundException _, NoHandlerFoundException _ -> Optional.of(ErrorCode.RESOURCE_NOT_FOUND);
            case AsyncRequestTimeoutException _ -> Optional.of(ErrorCode.SERVER_BUSY);
            default -> Optional.empty();
        };
    }

    private static ErrorCode codeForStatus(HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return ErrorCode.UNEXPECTED_ERROR;
        }
        return status.value() == HttpStatus.NOT_FOUND.value() ? ErrorCode.RESOURCE_NOT_FOUND : ErrorCode.REQUEST_INVALID;
    }

    private static Optional<ConstraintViolationException> constraintViolation(Throwable failure) {
        for (Throwable cause = failure; Objects.nonNull(cause); cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return Optional.of(violation);
            }
        }
        return Optional.empty();
    }

    private static Optional<ErrorCode> constraintCode(ConstraintViolationException violation) {
        Optional<ErrorCode> domainCode = Optional.ofNullable(violation.getConstraintName()).map(CONSTRAINT_CODES::get);
        if (domainCode.isPresent() || !UNIQUE_VIOLATION.equals(violation.getSQLState())) {
            return domainCode;
        }
        return Optional.of(ErrorCode.DUPLICATE_RECORD);
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

    private void identify(ProblemDetail problem, WebRequest request) {
        if (Objects.nonNull(problem.getProperties()) && problem.getProperties().get(ProblemTypes.ERROR_CODE) instanceof String code) {
            problem.setType(problemTypes.typeOf(ErrorCode.valueOf(code)));
        }
        if (Objects.isNull(problem.getInstance())) {
            problem.setInstance(requestInstanceUri(request));
        }
    }

    /** {@link WebRequest#getDescription} is a debug label ({@code uri=/api/v1/todos}), not a URI. */
    private static URI requestInstanceUri(WebRequest request) {
        String description = request.getDescription(false);
        return URI.create(description.startsWith(REQUEST_DESCRIPTION_URI_PREFIX)
                ? description.substring(REQUEST_DESCRIPTION_URI_PREFIX.length())
                : description);
    }

    private ResponseEntity<ProblemDetail> respond(ErrorCode code, ProblemDetail problem, WebRequest request) {
        return problemBuilder(code, problem, request).body(problem);
    }

    /** With a {@code Retry-After}, for the refusals a client should come back from. */
    private ResponseEntity<ProblemDetail> respond(ErrorCode code, ProblemDetail problem, WebRequest request,
                                                  String retryAfterSeconds) {
        return problemBuilder(code, problem, request).header(HttpHeaders.RETRY_AFTER, retryAfterSeconds).body(problem);
    }

    private ResponseEntity.BodyBuilder problemBuilder(ErrorCode code, ProblemDetail problem, WebRequest request) {
        problem.setProperty(ProblemTypes.ERROR_CODE, code.name());
        identify(problem, request);
        return ResponseEntity.status(problem.getStatus())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    }
}
