package com.example.app.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.accept.InvalidApiVersionException;
import org.springframework.web.accept.MissingApiVersionException;
import org.springframework.web.accept.NotAcceptableApiVersionException;

import static org.assertj.core.api.Assertions.assertThat;

/** Spring's API version refusals, whatever the transport: one code, so clients handle them one way. */
class MvcExceptionCodesTest {

    @Test
    @DisplayName("A missing version (a header or query transport; the path has no route without one) is API_VERSION_UNSUPPORTED")
    void missingVersion() {
        assertThat(MvcExceptionCodes.codeFor(new MissingApiVersionException(), HttpStatus.BAD_REQUEST))
                .isEqualTo(ErrorCode.API_VERSION_UNSUPPORTED);
    }

    @Test
    @DisplayName("A malformed or unsupported version is API_VERSION_UNSUPPORTED")
    void invalidVersion() {
        assertThat(MvcExceptionCodes.codeFor(new InvalidApiVersionException("v9"), HttpStatus.BAD_REQUEST))
                .isEqualTo(ErrorCode.API_VERSION_UNSUPPORTED);
    }

    @Test
    @DisplayName("A version a fixed-version mapping doesn't serve is API_VERSION_UNSUPPORTED")
    void notAcceptableVersion() {
        assertThat(MvcExceptionCodes.codeFor(new NotAcceptableApiVersionException("v2"), HttpStatus.BAD_REQUEST))
                .isEqualTo(ErrorCode.API_VERSION_UNSUPPORTED);
    }
}
