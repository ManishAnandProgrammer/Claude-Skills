package com.example.app.common.web;

import com.example.app.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * What every Problem Details response shares, wherever it is written. The {@code type} is a URN
 * ({@code urn:<spring.application.name>:error:<CODE>}), not an https URL: a stable identifier for a
 * problem category, with no documentation site behind it.
 */
@Component
public class ProblemTypes {

    /** Member names of the error contract; clients parse them, so they never change. */
    public static final String ERROR_CODE = "errorCode";
    public static final String FAILED = "failed";

    private final String namespace;

    public ProblemTypes(@Value("${spring.application.name}") String applicationName) {
        this.namespace = "urn:" + applicationName + ":error:";
    }

    public URI typeOf(ErrorCode code) {
        return URI.create(namespace + code.name());
    }
}
