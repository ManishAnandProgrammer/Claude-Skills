package com.example.app.common;

import com.example.app.common.limits.Limits;
import com.example.app.support.CapturedLog;
import com.example.app.support.IntegrationTest;
// sign-in:begin
import com.example.app.user.domain.Role;
// sign-in:end
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Promises every endpoint keeps, checked on each endpoint the application serves: the list comes from
 * Spring's handler mappings at run time, so a project's generated endpoints are covered the day they exist,
 * with no test to write per entity. Each check is one loop over that list and collects the endpoints that
 * break it, so a failure names all of them at once.
 *
 * <p>What a check needs to know about an endpoint (its path variables, whether it is a cursor or an offset
 * list, a valid body) comes from the published OpenAPI document, which every endpoint must be in. A valid
 * body is built from each required field's {@code @Schema(example)}.</p>
 */
class EndpointContractIntegrationTest extends IntegrationTest {

    /**
     * The endpoints anyone may call, by path prefix: sign-in's (SecurityConfig's public paths). A public
     * endpoint a project adds is listed here, with its reason.
     */
    // tenancy:begin
    // private static final List<String> PUBLIC_PREFIXES = List.of(
    //         "/api/{version}/auth/",
    //         // The platform's own sign-in (PlatformSecurityConfig).
    //         "/api/{version}/platform/auth/login");
    // tenancy:else
    private static final List<String> PUBLIC_PREFIXES = List.of("/api/{version}/auth/");
    // tenancy:end
    // tenancy:begin
    // /** Served by the platform's chain: its staff tokens, never a tenant member's. */
    // private static final String PLATFORM_PREFIX = "/api/{version}/platform/";
    // tenancy:end
    private static final String VERSION = "v1";
    private static final String MALFORMED_ID = "not-a-uuid";
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}]+)}");
    private static final Pattern ROLE_NAME = Pattern.compile("'([A-Z][A-Z_]*)'");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private Limits limits;

    @Autowired
    private Environment environment;

    private JsonNode docs;

    /** One served operation: an HTTP method on a path pattern, its handler, and its OpenAPI operation. */
    private record Endpoint(String method, String pattern, HandlerMethod handler, JsonNode operation) {

        String name() {
            return method + " " + pattern;
        }

        boolean isPublic() {
            return PUBLIC_PREFIXES.stream().anyMatch(pattern::startsWith);
        }

        boolean takesABody() {
            return operation.has("requestBody");
        }

        boolean hasQueryParameter(String name) {
            for (JsonNode parameter : operation.path("parameters").values()) {
                if (parameter.path("in").asString().equals("query") && parameter.path("name").asString().equals(name)) {
                    return true;
                }
            }
            return false;
        }

        List<String> pathVariables() {
            List<String> names = new ArrayList<>();
            Matcher variable = PATH_VARIABLE.matcher(pattern);
            while (variable.find()) {
                names.add(variable.group(1));
            }
            return names;
        }

        List<String> uuidPathVariables() {
            List<String> names = new ArrayList<>();
            for (JsonNode parameter : operation.path("parameters").values()) {
                if (parameter.path("in").asString().equals("path") && parameter.path("schema").path("format").asString().equals("uuid")) {
                    names.add(parameter.path("name").asString());
                }
            }
            return names;
        }

        /** The roles its {@code @PreAuthorize} names (on the method or its controller); empty when it names none. */
        List<String> roles() {
            PreAuthorize check = handler.getMethodAnnotation(PreAuthorize.class);
            if (Objects.isNull(check)) {
                check = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
            }
            List<String> roles = new ArrayList<>();
            if (Objects.nonNull(check)) {
                Matcher role = ROLE_NAME.matcher(check.value());
                while (role.find()) {
                    roles.add(role.group(1));
                }
            }
            return roles;
        }

        /**
         * The path with every variable filled (an id with a fresh UUID, any other with its documented value), and
         * each required query parameter with its example.
         */
        String path(String malformedVariable) {
            Matcher variable = PATH_VARIABLE.matcher(pattern);
            StringBuilder path = new StringBuilder();
            while (variable.find()) {
                variable.appendReplacement(path, Matcher.quoteReplacement(valueOf(variable.group(1), malformedVariable)));
            }
            variable.appendTail(path);
            String separator = "?";
            for (JsonNode parameter : operation.path("parameters").values()) {
                if (parameter.path("in").asString().equals("query") && parameter.path("required").asBoolean()) {
                    path.append(separator).append(parameter.path("name").asString()).append('=')
                            .append(URLEncoder.encode(parameter.path("schema").path("example").asString("x"), StandardCharsets.UTF_8));
                    separator = "&";
                }
            }
            return path.toString();
        }

        /** {@link #path} with one more query parameter. */
        String pathWith(String query) {
            String path = path(null);
            return path + (path.contains("?") ? "&" : "?") + query;
        }

        private String valueOf(String variable, String malformedVariable) {
            if (variable.equals("version")) {
                return VERSION;
            }
            if (variable.equals(malformedVariable)) {
                return MALFORMED_ID;
            }
            for (JsonNode parameter : operation.path("parameters").values()) {
                if (parameter.path("in").asString().equals("path") && parameter.path("name").asString().equals(variable)) {
                    JsonNode schema = parameter.path("schema");
                    if (schema.has("enum")) {
                        return schema.path("enum").get(0).asString();
                    }
                    if (schema.path("format").asString().equals("uuid")) {
                        return UUID.randomUUID().toString();
                    }
                }
            }
            return "x";
        }
    }

    @BeforeEach
    void readTheDocs() throws Exception {
        docs = JSON.readTree(getAs(null, "/v3/api-docs").andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("Every served endpoint is in the published OpenAPI document")
    void everyEndpointIsDocumented() {
        List<String> undocumented = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> mapping : handlerMapping.getHandlerMethods().entrySet()) {
            for (String pattern : apiPatterns(mapping.getKey())) {
                for (RequestMethod method : mapping.getKey().getMethodsCondition().getMethods()) {
                    if (operationOf(method.name(), pattern).isMissingNode()) {
                        undocumented.add(method.name() + " " + pattern);
                    }
                }
            }
        }
        assertThat(endpoints()).isNotEmpty();
        assertThat(undocumented).as("served but not in /v3/api-docs").isEmpty();
    }

    @Test
    @DisplayName("Without a token every endpoint but the declared public ones is 401 AUTHENTICATION_REQUIRED, as Problem Details")
    void everyEndpointNeedsAToken() throws Exception {
        List<String> open = new ArrayList<>();
        for (Endpoint endpoint : endpoints()) {
            if (!endpoint.isPublic()) {
                MockHttpServletResponse answer = send(endpoint, endpoint.path(null), null, validBody(endpoint));
                if (!isProblem(answer, 401, "AUTHENTICATION_REQUIRED")) {
                    open.add(endpoint.name() + " answered " + answer.getStatus());
                }
            }
        }
        assertThat(open).as("endpoints that answer without a token").isEmpty();
    }

    // sign-in:begin
    @Test
    @DisplayName("A role an endpoint's @PreAuthorize doesn't name is 403 ACCESS_DENIED, with a valid body")
    void aRoleTheEndpointDoesntNameIs403() throws Exception {
        List<String> allowed = new ArrayList<>();
        int checked = 0;
        for (Endpoint endpoint : endpoints()) {
            List<String> roles = endpoint.roles();
            if (roles.isEmpty()) {
                continue;
            }
            for (Role role : Role.values()) {
                if (!roles.contains(role.name())) {
                    checked++;
                    String token = newUserWithRole(role.name()).accessToken();
                    MockHttpServletResponse answer = send(endpoint, endpoint.path(null), token, validBody(endpoint));
                    // An enum path variable without constants yet (a piece's purposes before the project declares one):
                    // no request reaches the endpoint, so there is no role check to see.
                    if (isProblem(answer, 400, "REQUEST_PARAMETER_INVALID") && endpoint.pathVariables().contains(parameterOf(answer))) {
                        continue;
                    }
                    if (!isProblem(answer, 403, "ACCESS_DENIED")) {
                        allowed.add("%s as %s answered %d %s".formatted(endpoint.name(), role, answer.getStatus(), answer.getContentAsString()));
                    }
                }
            }
        }
        assertThat(checked).as("role-checked endpoints").isPositive();
        assertThat(allowed).as("endpoints a role they don't name gets past").isEmpty();
    }
    // sign-in:end

    @Test
    @DisplayName("A malformed id in any path is 400 REQUEST_PARAMETER_INVALID naming the path variable")
    void aMalformedPathIdIs400() throws Exception {
        List<String> accepted = new ArrayList<>();
        String token = newUser().accessToken();
        for (Endpoint endpoint : endpoints()) {
            for (String variable : endpoint.uuidPathVariables()) {
                MockHttpServletResponse answer = send(endpoint, endpoint.path(variable), tokenFor(endpoint, token), validBody(endpoint));
                if (!isProblem(answer, 400, "REQUEST_PARAMETER_INVALID") || !parameterOf(answer).equals(variable)) {
                    accepted.add("%s with %s=%s answered %d %s".formatted(endpoint.name(), variable, MALFORMED_ID,
                            answer.getStatus(), answer.getContentAsString()));
                }
            }
        }
        assertThat(accepted).as("endpoints that don't refuse a malformed id").isEmpty();
    }

    @Test
    @DisplayName("A tampered cursor on every cursor list is 400 REQUEST_PARAMETER_INVALID at after")
    void aTamperedCursorIs400() throws Exception {
        List<String> accepted = new ArrayList<>();
        String token = newUser().accessToken();
        for (Endpoint endpoint : endpoints()) {
            // A list under a parent (an id in its path) finds the parent first: an unknown one is its 404, so
            // its own class tests the cursor (crud-gen tests.md row 17).
            if (endpoint.method().equals("GET") && endpoint.hasQueryParameter("after") && endpoint.uuidPathVariables().isEmpty()) {
                MockHttpServletResponse answer = send(endpoint, endpoint.pathWith("after=bm90LWEtY3Vyc29y"), tokenFor(endpoint, token), null);
                if (!isProblem(answer, 400, "REQUEST_PARAMETER_INVALID") || !parameterOf(answer).equals("after")) {
                    accepted.add(endpoint.name() + " answered " + answer.getStatus() + " " + answer.getContentAsString());
                }
            }
        }
        assertThat(accepted).as("cursor lists that read a tampered cursor").isEmpty();
    }

    @Test
    @DisplayName("A page or size out of range on every paged list is 400 VALIDATION_FAILED naming the parameter, never clamped")
    void pagingOutOfRangeIs400() throws Exception {
        List<String> accepted = new ArrayList<>();
        String token = newUser().accessToken();
        for (Endpoint endpoint : endpoints()) {
            if (!endpoint.method().equals("GET")) {
                continue;
            }
            List<String> outOfRange = new ArrayList<>();
            if (endpoint.hasQueryParameter("page")) {
                outOfRange.add("page=-1");
                outOfRange.add("page=" + (limits.getLong("offset-page.max-page") + 1));
                outOfRange.add("size=0");
                outOfRange.add("size=" + (limits.getLong("offset-page.max-size") + 1));
            } else if (endpoint.hasQueryParameter("after")) {
                outOfRange.add("size=0");
                outOfRange.add("size=" + (limits.getLong("cursor-page.max-size") + 1));
            }
            for (String query : outOfRange) {
                MockHttpServletResponse answer = send(endpoint, endpoint.pathWith(query), tokenFor(endpoint, token), null);
                String parameter = query.substring(0, query.indexOf('='));
                if (!isProblem(answer, 400, "VALIDATION_FAILED") || !parameterOf(answer).equals(parameter)) {
                    accepted.add(endpoint.name() + "?" + query + " answered " + answer.getStatus() + " " + answer.getContentAsString());
                }
            }
        }
        assertThat(accepted).as("lists that take a page or size out of range").isEmpty();
    }

    @Test
    @DisplayName("The OpenAPI document lists the 400, 401 and 403 these checks see, per endpoint")
    void apiDocsListTheStatusesTheEndpointsAnswer() {
        List<String> missing = new ArrayList<>();
        for (Endpoint endpoint : endpoints()) {
            JsonNode responses = endpoint.operation().path("responses");
            if (!endpoint.isPublic() && !responses.has("401")) {
                missing.add(endpoint.name() + " 401");
            }
            if (!endpoint.roles().isEmpty() && !responses.has("403")) {
                missing.add(endpoint.name() + " 403");
            }
            boolean refusesInput = endpoint.takesABody() || !endpoint.uuidPathVariables().isEmpty()
                    || endpoint.hasQueryParameter("after") || endpoint.hasQueryParameter("page");
            if (refusesInput && !responses.has("400")) {
                missing.add(endpoint.name() + " 400");
            }
        }
        assertThat(missing).as("statuses an endpoint answers that its documentation leaves out").isEmpty();
    }

    @Test
    @DisplayName("A parameter with a default is never published as required: a client may leave it out")
    void aDefaultedParameterIsNotRequired() {
        List<String> required = new ArrayList<>();
        for (Endpoint endpoint : endpoints()) {
            for (JsonNode parameter : endpoint.operation().path("parameters").values()) {
                if (parameter.path("required").asBoolean() && parameter.path("schema").has("default")) {
                    required.add(endpoint.name() + " " + parameter.path("name").asString());
                }
            }
        }
        assertThat(required).as("parameters published as required although they have a default").isEmpty();
    }

    @Test
    @DisplayName("No response body holds a password hash or a configured secret, and no log line a password, a token or a secret")
    void noBodyOrLogLineHoldsASecret() throws Exception {
        CapturedLog log = new CapturedLog();
        log.open();
        try {
            assertNoSecretInBodiesOrLog(log);
        } finally {
            log.close();
        }
    }

    private void assertNoSecretInBodiesOrLog(CapturedLog log) throws Exception {
        String email = uniqueEmail("secrets");
        String password = "Secret-Probe-" + UUID.randomUUID();
        List<String> bodies = new ArrayList<>();
        bodies.add(signup(email, password).andReturn().getResponse().getContentAsString());
        String login = login(email, password).andReturn().getResponse().getContentAsString();
        String accessToken = JSON.readTree(login).path("response").path("accessToken").asString();
        String refreshToken = JSON.readTree(login).path("response").path("refreshToken").asString();
        bodies.add(postAs(null, "/api/v1/auth/refresh-token", """
                {"refreshToken": "%s"}""".formatted(refreshToken)).andReturn().getResponse().getContentAsString());
        for (Endpoint endpoint : endpoints()) {
            if (endpoint.method().equals("GET")) {
                bodies.add(send(endpoint, endpoint.path(null), tokenFor(endpoint, accessToken), null).getContentAsString());
            }
        }
        String passwordHash = jdbc.queryForObject("select password_hash from users where email = ?", String.class, email);
        List<String> secrets = configuredSecrets();

        for (String body : bodies) {
            assertThat(body).doesNotContain(passwordHash).doesNotContain(password);
            for (String secret : secrets) {
                assertThat(body).doesNotContain(secret);
            }
        }
        assertThat(log.text()).doesNotContain(passwordHash, password, accessToken, refreshToken);
        for (String secret : secrets) {
            assertThat(log.text()).doesNotContain(secret);
        }
    }

    /** The configured secrets: the JWT key, the personal-data pepper, the database password. */
    private List<String> configuredSecrets() {
        List<String> secrets = new ArrayList<>();
        for (String key : List.of("app.jwt.secret", "app.security.personal-data-pepper", "spring.datasource.password")) {
            String secret = environment.getProperty(key);
            assertThat(secret).as(key).isNotBlank();
            secrets.add(secret);
        }
        return secrets;
    }

    /** Every served API endpoint with its OpenAPI operation; the docs and actuator endpoints aren't API. */
    private List<Endpoint> endpoints() {
        List<Endpoint> endpoints = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> mapping : handlerMapping.getHandlerMethods().entrySet()) {
            for (String pattern : apiPatterns(mapping.getKey())) {
                for (RequestMethod method : mapping.getKey().getMethodsCondition().getMethods()) {
                    JsonNode operation = operationOf(method.name(), pattern);
                    if (!operation.isMissingNode()) {
                        endpoints.add(new Endpoint(method.name(), pattern, mapping.getValue(), operation));
                    }
                }
            }
        }
        return endpoints;
    }

    private static List<String> apiPatterns(RequestMappingInfo mapping) {
        return mapping.getPathPatternsCondition().getPatternValues().stream()
                .filter(pattern -> pattern.startsWith("/api/"))
                .toList();
    }

    private JsonNode operationOf(String method, String pattern) {
        return docs.path("paths").path(pattern.replace("{version}", VERSION)).path(method.toLowerCase());
    }

    // tenancy:begin
    // /** A platform endpoint takes a staff token; every other the member's. */
    // private String tokenFor(Endpoint endpoint, String memberToken) throws Exception {
    //     return endpoint.pattern().startsWith(PLATFORM_PREFIX) ? platformToken() : memberToken;
    // }
    // tenancy:else
    private String tokenFor(Endpoint endpoint, String token) {
        return token;
    }
    // tenancy:end

    private MockHttpServletResponse send(Endpoint endpoint, String path, String token, String body) throws Exception {
        MockHttpServletRequestBuilder request = request(HttpMethod.valueOf(endpoint.method()), path);
        if (Objects.nonNull(token)) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        if (Objects.nonNull(body)) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    private static boolean isProblem(MockHttpServletResponse answer, int status, String errorCode) throws Exception {
        return answer.getStatus() == status
                && MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(MediaType.parseMediaType(Objects.requireNonNullElse(answer.getContentType(), "*/*")))
                && JSON.readTree(answer.getContentAsString()).path("errorCode").asString().equals(errorCode);
    }

    private static String parameterOf(MockHttpServletResponse answer) throws Exception {
        return JSON.readTree(answer.getContentAsString()).path("errors").path(0).path("parameter").asString();
    }

    /** A body bean validation accepts, from the request schema's examples; null for an endpoint without one. */
    private String validBody(Endpoint endpoint) {
        JsonNode schema = endpoint.operation().path("requestBody").path("content").path("application/json").path("schema");
        if (schema.isMissingNode()) {
            return null;
        }
        return JSON.writeValueAsString(sample(schema));
    }

    /** Each required field's example; without one, its enum's first value or a plain value of its type. */
    private JsonNode sample(JsonNode schema) {
        if (schema.has("$ref")) {
            String name = schema.path("$ref").asString().substring("#/components/schemas/".length());
            return sample(docs.path("components").path("schemas").path(name));
        }
        if (schema.has("example")) {
            return schema.path("example");
        }
        if (schema.has("enum")) {
            return schema.path("enum").get(0);
        }
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        switch (schema.path("type").asString()) {
            case "object" -> {
                ObjectNode object = nodes.objectNode();
                for (JsonNode required : schema.path("required").values()) {
                    object.set(required.asString(), sample(schema.path("properties").path(required.asString())));
                }
                return object;
            }
            case "array" -> {
                ArrayNode array = nodes.arrayNode();
                for (int element = 0; element < schema.path("minItems").asInt(0); element++) {
                    array.add(sample(schema.path("items")));
                }
                return array;
            }
            case "integer", "number" -> {
                return nodes.numberNode(schema.path("minimum").asLong(0));
            }
            case "boolean" -> {
                return nodes.booleanNode(true);
            }
            default -> {
                return switch (schema.path("format").asString()) {
                    case "uuid" -> nodes.stringNode(UUID.randomUUID().toString());
                    case "date" -> nodes.stringNode(LocalDate.now(ZoneOffset.UTC).toString());
                    case "date-time" -> nodes.stringNode(Instant.now().toString());
                    default -> nodes.stringNode("x");
                };
            }
        }
    }
}
