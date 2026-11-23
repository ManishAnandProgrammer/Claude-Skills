package com.example.app.common.web;

import com.example.app.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

@Component
public class ProblemTypes {

    /** Clients parse these member names, so they never change. */
    public static final String ERROR_CODE = "errorCode";
    public static final String ERRORS = "errors";
    private static final String REQUEST_DESCRIPTION_URI_PREFIX = "uri=";

    private final String namespace;

    // Hand-written: @Value goes on the parameter; on a Lombok field it would be field injection.
    public ProblemTypes(@Value("${spring.application.name}") String applicationName) {
        this.namespace = "urn:" + applicationName + ":error:";
    }

    public URI typeOf(ErrorCode code) {
        return URI.create(namespace + code.name());
    }

    public void setTypeAndInstance(ProblemDetail problem, WebRequest request) {
        Map<String, Object> properties = problem.getProperties();
        if (Objects.nonNull(properties) && properties.get(ERROR_CODE) instanceof String code) {
            problem.setType(typeOf(ErrorCode.valueOf(code)));
        }
        if (Objects.isNull(problem.getInstance())) {
            problem.setInstance(requestInstanceUri(request));
        }
    }

    /** {@link WebRequest#getDescription} is a debug label ({@code uri=...}), not a URI. */
    private static URI requestInstanceUri(WebRequest request) {
        String description = request.getDescription(false);
        return URI.create(description.startsWith(REQUEST_DESCRIPTION_URI_PREFIX)
                ? description.substring(REQUEST_DESCRIPTION_URI_PREFIX.length())
                : description);
    }
}
