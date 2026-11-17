package com.example.app.common.web;

import com.example.app.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

import java.net.URI;
import java.util.Objects;

/**
 * What every Problem Details response shares, wherever it is written. The {@code type} is a URN
 * ({@code urn:<spring.application.name>:error:<CODE>}), not an https URL: a stable identifier for a
 * problem category, with no documentation site behind it.
 */
@Component
public class ProblemTypes {

    /** Member names of the error contract; clients parse them, so they never change. */
    public static final String ERROR_CODE = "errorCode";
    public static final String ERRORS = "errors";
    private static final String REQUEST_DESCRIPTION_URI_PREFIX = "uri=";

    private final String namespace;

    // Hand-written (JAVA-26): @Value goes on the parameter; on a Lombok field it is field injection, which
    // ConventionArchTest refuses.
    public ProblemTypes(@Value("${spring.application.name}") String applicationName) {
        this.namespace = "urn:" + applicationName + ":error:";
    }

    public URI typeOf(ErrorCode code) {
        return URI.create(namespace + code.name());
    }

    /** Sets {@code type} from the problem's {@code errorCode}, and {@code instance} to the request path unless already set. */
    public void identify(ProblemDetail problem, WebRequest request) {
        if (Objects.nonNull(problem.getProperties()) && problem.getProperties().get(ERROR_CODE) instanceof String code) {
            problem.setType(typeOf(ErrorCode.valueOf(code)));
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
}
