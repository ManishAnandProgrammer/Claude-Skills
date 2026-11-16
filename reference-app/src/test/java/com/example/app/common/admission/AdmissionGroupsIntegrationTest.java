package com.example.app.common.admission;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.AntPathMatcher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every operation that takes a password (bcrypt work: a verify, a hash) takes its permit from the
 * {@code sign-in} group, so a storm of them queues there and never uses up the permits the refreshes of
 * signed-in users need (limits.yml). Read from the published API, so a new password endpoint is checked
 * without an edit here.
 */
class AdmissionGroupsIntegrationTest extends IntegrationTest {

    @Autowired
    private AdmissionGroups groups;

    @Test
    @DisplayName("Every operation with a password in its body is in the sign-in admission group")
    void everyPasswordEndpointIsInTheSignInGroup() throws Exception {
        JsonNode docs = JsonMapper.builder().build().readTree(getAs(null, "/v3/api-docs")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<String> signIn = groups.paths().get("sign-in");
        AntPathMatcher matcher = new AntPathMatcher();
        List<String> withPassword = new ArrayList<>();
        List<String> outside = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : docs.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                JsonNode body = operation.getValue().path("requestBody").path("content").path("application/json").path("schema");
                if (takesAPassword(docs, body)) {
                    withPassword.add(path.getKey());
                    if (signIn.stream().noneMatch(pattern -> matcher.match(pattern, path.getKey()))) {
                        outside.add(operation.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey());
                    }
                }
            }
        }

        assertThat(withPassword).as("operations taking a password").isNotEmpty();
        assertThat(outside).as("password operations outside the sign-in group").isEmpty();
    }

    /** A property whose name holds "password", in the body's schema itself (password, currentPassword, newPassword). */
    private static boolean takesAPassword(JsonNode docs, JsonNode schema) {
        JsonNode resolved = schema;
        if (schema.has("$ref")) {
            String name = schema.path("$ref").asString().substring("#/components/schemas/".length());
            resolved = docs.path("components").path("schemas").path(name);
        }
        return resolved.path("properties").propertyNames().stream()
                .anyMatch(property -> property.toLowerCase(Locale.ROOT).contains("password"));
    }
}
