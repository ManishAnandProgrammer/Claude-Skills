package com.example.app.common;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The error contract every client relies on, through the real stack. */
class ApiContractIntegrationTest extends IntegrationTest {

    @Autowired
    @Qualifier("applicationTaskExecutor")
    private AsyncTaskExecutor applicationTaskExecutor;

    @Test
    @DisplayName("Every response carries a request id, and a safe one from the caller is echoed")
    void requestIdIsEchoed() throws Exception {
        mvc.perform(get("/api/v1/users/me").header("X-Request-ID", "trace-me-123"))
                .andExpect(header().string("X-Request-ID", "trace-me-123"));
        mvc.perform(get("/api/v1/users/me"))
                .andExpect(header().exists("X-Request-ID"));
    }

    @Test
    @DisplayName("An unknown path is a Problem Details 404 RESOURCE_NOT_FOUND with type and instance")
    void unknownPathIsProblemDetails() throws Exception {
        TestUser user = newUser();

        getAs(user.accessToken(), "/api/v1/no-such-thing")
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.type").value(problemType("RESOURCE_NOT_FOUND")))
                .andExpect(jsonPath("$.instance").value("/api/v1/no-such-thing"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("A method the path doesn't take is 405 REQUEST_METHOD_NOT_ALLOWED and keeps Allow")
    void wrongMethodIs405() throws Exception {
        TestUser user = newUser();

        mvc.perform(post("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_METHOD_NOT_ALLOWED"))
                .andExpect(header().exists(HttpHeaders.ALLOW));
    }

    @Test
    @DisplayName("A body that isn't JSON is 400 REQUEST_UNREADABLE")
    void malformedBodyIs400() throws Exception {
        postAs(null, "/api/v1/auth/login", "{not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    @DisplayName("An unknown body field is 400 REQUEST_UNREADABLE whose errors point to it (RFC 9457), never Jackson's message")
    void unknownFieldIsNamedByItsPointer() throws Exception {
        postAs(null, "/api/v1/auth/login", """
                {"email": "someone@example.com", "password": "irrelevant", "rememberMe": true}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"))
                .andExpect(jsonPath("$.errors[0].pointer").value("#/rememberMe"))
                .andExpect(jsonPath("$.errors[0].detail").value("unknown field"));
    }

    @Test
    @DisplayName("The OpenAPI document describes errorCode and the errors list of every Problem Details response")
    void problemSchemaDocumentsTheErrorsList() throws Exception {
        getAs(null, "/v3/api-docs")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.ProblemDetail.properties.errorCode.type").value("string"))
                .andExpect(jsonPath("$.components.schemas.ProblemDetail.properties.errors.items.$ref")
                        .value("#/components/schemas/RequestError"))
                .andExpect(jsonPath("$.components.schemas.RequestError.properties.pointer").exists())
                .andExpect(jsonPath("$.components.schemas.RequestError.properties.parameter").exists())
                .andExpect(jsonPath("$.components.schemas.RequestError.properties.detail").exists());
    }

    @Test
    @DisplayName("A body sent as text/plain is 415 REQUEST_MEDIA_TYPE_UNSUPPORTED")
    void wrongContentTypeIs415() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_MEDIA_TYPE_UNSUPPORTED"));
    }

    @Test
    @DisplayName("Health and the readiness probe answer without a token")
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("The prometheus scrape answers without a token (the management port is the boundary), with the mitigation counters at zero or more")
    void prometheusScrapeNeedsNoToken() throws Exception {
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("application=\"%s\"".formatted(applicationName()))))
                .andExpect(content().string(containsString("breached_password_check_unavailable_total")))
                .andExpect(content().string(containsString("rate_limit_unchecked_total")));
    }

    @Test
    @DisplayName("Metrics need a token")
    void metricsNeedAToken() throws Exception {
        mvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A language without its own messages gets English Problem Details, not a broken error")
    void unsupportedLanguageFallsBackToEnglish() throws Exception {
        mvc.perform(get("/api/v1/users/me").header(HttpHeaders.ACCEPT_LANGUAGE, "de-DE,pa;q=0.8"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.detail").value("Authentication is required. Please sign in."));
    }

    @Test
    @DisplayName("An X-Request-ID with unsafe characters is replaced, not echoed")
    void unsafeRequestIdIsReplaced() throws Exception {
        String echoed = mvc.perform(get("/api/v1/users/me").header("X-Request-ID", "abc\u001b[31m forged"))
                .andReturn().getResponse().getHeader("X-Request-ID");

        assertThat(echoed).isNotEqualTo("abc\u001b[31m forged").matches("[0-9a-f-]{36}");
    }

    @Test
    @DisplayName("Asking for a response type other than JSON is 406 REQUEST_NOT_ACCEPTABLE")
    void notAcceptableIs406() throws Exception {
        mvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + newUser().accessToken())
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable());
    }

    @Test
    @DisplayName("Async work keeps the submitting thread's log context")
    void asyncExecutorCarriesTheLogContext() throws Exception {
        MDC.put("requestId", "carried-over");
        try {
            String seen = applicationTaskExecutor.submit(() -> MDC.get("requestId")).get();
            assertThat(seen).isEqualTo("carried-over");
        } finally {
            MDC.remove("requestId");
        }
    }

    @Test
    @DisplayName("""
            Every write that takes a version has a read on its resource that answers the version: without one a \
            client can send it only after reading the database""")
    void everyVersionedWriteHasAReadOfTheVersion() throws Exception {
        JsonNode docs = JsonMapper.builder().build().readTree(getAs(null, "/v3/api-docs")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<String> unreadable = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : docs.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                if (!operation.getKey().equals("get") && takesAVersion(docs, operation.getValue())
                        && !aReadAnswersTheVersion(docs, path.getKey())) {
                    unreadable.add(operation.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey());
                }
            }
        }
        assertThat(unreadable).as("versioned writes no read of their resource answers the version of").isEmpty();
    }

    /** A {@code version} in the JSON body or as a query parameter ({@code {version}} in the path is the API's). */
    private static boolean takesAVersion(JsonNode docs, JsonNode operation) {
        for (JsonNode parameter : operation.path("parameters").values()) {
            if (parameter.path("in").asString().equals("query") && parameter.path("name").asString().equals("version")) {
                return true;
            }
        }
        JsonNode body = operation.path("requestBody").path("content").path("application/json").path("schema");
        return hasVersion(docs, body, 0);
    }

    /**
     * A GET on the write's own path, on the resource it acts on (the path up to its last id), or on that
     * resource's collection, whose answer holds a {@code version}.
     */
    private static boolean aReadAnswersTheVersion(JsonNode docs, String writePath) {
        List<String> candidates = new ArrayList<>();
        candidates.add(writePath);
        int lastId = writePath.lastIndexOf("/{");
        if (lastId > 0 && !writePath.substring(lastId).startsWith("/{version}")) {
            String resource = writePath.substring(0, writePath.indexOf('}', lastId) + 1);
            candidates.add(resource);
            candidates.add(resource.substring(0, lastId));
        }
        for (String candidate : candidates) {
            JsonNode answer = docs.path("paths").path(candidate).path("get").path("responses").path("200")
                    .path("content").path("*/*").path("schema");
            JsonNode json = docs.path("paths").path(candidate).path("get").path("responses").path("200")
                    .path("content").path("application/json").path("schema");
            if (hasVersion(docs, answer, 0) || hasVersion(docs, json, 0)) {
                return true;
            }
        }
        return false;
    }

    /** The schema, its references, properties and items followed a few levels deep (a wrapper around a record). */
    private static boolean hasVersion(JsonNode docs, JsonNode schema, int depth) {
        if (schema.isMissingNode() || depth > 4) {
            return false;
        }
        if (schema.has("$ref")) {
            String name = schema.path("$ref").asString().substring("#/components/schemas/".length());
            return hasVersion(docs, docs.path("components").path("schemas").path(name), depth + 1);
        }
        if (schema.path("properties").has("version")) {
            return true;
        }
        for (JsonNode property : schema.path("properties").values()) {
            if (hasVersion(docs, property, depth + 1)) {
                return true;
            }
        }
        return hasVersion(docs, schema.path("items"), depth + 1);
    }
}
