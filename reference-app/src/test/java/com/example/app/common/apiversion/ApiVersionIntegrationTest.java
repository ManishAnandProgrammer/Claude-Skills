package com.example.app.common.apiversion;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.app.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiVersionIntegrationTest extends IntegrationTest {

    private static final String COMPLETION_LOGGER = "com.example.app.common.logging.RequestCompletionLogFilter";
    private static final String REQUEST_ID = "X-Request-ID";
    private static final String UNSUPPORTED_MESSAGE =
            "This API version is missing, malformed or not supported. Use a version listed in the API documentation.";

    @Autowired
    private MeterRegistry meterRegistry;

    /** Keeps each line's MDC, which logback otherwise reads lazily, after the request cleared it. */
    private final ListAppender<ILoggingEvent> completionLines = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    @BeforeEach
    void captureCompletionLines() {
        completionLines.start();
        ((Logger) LoggerFactory.getLogger(COMPLETION_LOGGER)).addAppender(completionLines);
    }

    @AfterEach
    void releaseCompletionLines() {
        ((Logger) LoggerFactory.getLogger(COMPLETION_LOGGER)).detachAppender(completionLines);
    }

    @Test
    @DisplayName("A version that parses but isn't supported (v9) is 400 API_VERSION_UNSUPPORTED, with no framework text")
    void unsupportedVersionIs400() throws Exception {
        TestUser user = newUser();

        getAs(user.accessToken(), "/api/v9/users/me")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("API_VERSION_UNSUPPORTED"))
                .andExpect(jsonPath("$.type").value(problemType("API_VERSION_UNSUPPORTED")))
                .andExpect(jsonPath("$.detail").value(UNSUPPORTED_MESSAGE))
                .andExpect(jsonPath("$.instance").value("/api/v9/users/me"))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("org.springframework"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"vx", "1", "v1.0", "v01", "V1", "version1"})
    @DisplayName("A malformed version is 400 API_VERSION_UNSUPPORTED: only v<major> names a version, so v1 has one URL")
    void malformedVersionIs400(String version) throws Exception {
        TestUser user = newUser();

        getAs(user.accessToken(), "/api/%s/users/me".formatted(version))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("API_VERSION_UNSUPPORTED"))
                .andExpect(jsonPath("$.detail").value(UNSUPPORTED_MESSAGE));
    }

    @Test
    @DisplayName("A path without a version segment matches no route: 404 RESOURCE_NOT_FOUND")
    void missingVersionIs404() throws Exception {
        TestUser user = newUser();

        getAs(user.accessToken(), "/api/users/me")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("Sign-in is public in every version, so an unknown one reaches MVC and is refused 400, not 401")
    void signInUnderAnUnknownVersionIsRefusedByVersion() throws Exception {
        postAs(null, "/api/v9/auth/login", """
                {"email": "nobody@example.com", "password": "Correct-Horse-9"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("API_VERSION_UNSUPPORTED"));
    }

    @Test
    @DisplayName("No version opens a protected path: without a token every version is 401")
    void versionOpensNothing() throws Exception {
        getAs(null, "/api/v9/users/me")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        getAs(null, "/api/vx/users/me")
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Signup's Location names the version the client called")
    void signupLocationKeepsTheVersion() throws Exception {
        signup(uniqueEmail("located"), PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(result -> assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/v1/users/me"));
    }

    @Test
    @DisplayName("The completion log line carries the resolved apiVersion, and none for a request outside the API or with a refused version")
    void completionLineCarriesTheApiVersion() throws Exception {
        TestUser user = newUser();
        String versioned = UUID.randomUUID().toString();
        String outsideApi = UUID.randomUUID().toString();
        String refused = UUID.randomUUID().toString();

        mvc.perform(get("/api/v1/users/me").header(REQUEST_ID, versioned).header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs").header(REQUEST_ID, outsideApi))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v9/users/me").header(REQUEST_ID, refused).header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isBadRequest());

        assertThat(completionLine(versioned)).matches("Request done: status=200, durationMs=\\d+, apiVersion=v1");
        assertThat(completionLine(outsideApi)).doesNotContain("apiVersion");
        assertThat(completionLine(refused)).startsWith("Request done: status=400").doesNotContain("apiVersion");
    }

    @Test
    @DisplayName("http.server.requests is tagged with the resolved apiVersion, none outside the API, and never a raw path segment")
    void requestMetricIsTaggedWithTheApiVersion() throws Exception {
        TestUser user = newUser();

        getAs(user.accessToken(), "/api/v1/users/me").andExpect(status().isOk());
        getAs(null, "/v3/api-docs").andExpect(status().isOk());
        getAs(user.accessToken(), "/api/v9/users/me").andExpect(status().isBadRequest());

        assertThat(meterRegistry.find("http.server.requests")
                .tag("uri", "/api/{version}/users/me").tag("apiVersion", "v1").timer()).isNotNull();
        assertThat(meterRegistry.find("http.server.requests")
                .tag("uri", "/v3/api-docs").tag("apiVersion", "none").timer()).isNotNull();
        assertThat(meterRegistry.find("http.server.requests").tag("apiVersion", "v9").timers()).isEmpty();
    }

    @Test
    @DisplayName("The API docs show the real v1 URLs, and API_VERSION_UNSUPPORTED on every operation's 400")
    void apiDocsShowVersionedPaths() throws Exception {
        getAs(null, "/v3/api-docs")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/users/me'].get").exists())
                .andExpect(jsonPath("$.paths['/api/{version}/users/me']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/users/me'].get.responses['400'].description",
                        containsString("API_VERSION_UNSUPPORTED")))
                .andExpect(jsonPath("$.paths['/api/v1/auth/signup'].post.responses['400'].content['application/problem+json']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/signup'].post.responses['400'].description",
                        containsString("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.paths['/api/v1/auth/signup'].post.responses['400'].description",
                        containsString("API_VERSION_UNSUPPORTED")))
                // The version is in the URL, not a parameter clients fill in (the operation has no other).
                .andExpect(jsonPath("$.paths['/api/v1/users/me'].get.parameters").doesNotExist());
    }

    private String completionLine(String requestId) {
        List<String> lines = completionLines.list.stream()
                .filter(event -> requestId.equals(event.getMDCPropertyMap().get("requestId")))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(lines).hasSize(1);
        return lines.getFirst();
    }
}
