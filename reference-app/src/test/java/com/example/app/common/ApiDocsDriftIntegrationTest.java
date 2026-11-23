package com.example.app.common;

import com.example.app.common.exception.ErrorCode;
import com.example.app.support.IntegrationTest;
import com.example.app.support.ObservedOutcomes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiDocsDriftIntegrationTest extends IntegrationTest {

    private static final Pattern STATED_BOUND = Pattern.compile("""
            (?i)\\b(?:at most|up to|no more than|at least|no fewer than|between)\\s+\\d++(?!\\s*bytes)|\\b\\d+\\s+(?:characters|items)\\b""");
    private static final List<String> BOUNDS = List.of("maxLength", "minLength", "maximum", "minimum", "maxItems", "minItems");

    @Test
    @DisplayName("A response whose code its operation's docs don't name fails the test that made the request")
    void anUndocumentedOutcomeFailsItsTest() throws Exception {
        TestUser user = newUser();

        assertThatThrownBy(() -> getAs(user.accessToken(), "/api/v1/hostile-input-probes/cursor"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("GET /api/v1/hostile-input-probes/cursor answered 400 REQUEST_PARAMETER_MISSING");
    }

    @Test
    @DisplayName("Every error code a description names is an ErrorCode: a renamed or removed code fails")
    void everyErrorCodeADescriptionNamesExists() throws Exception {
        JsonNode docs = apiDocs();
        Set<String> known = new HashSet<>(publishedEnumValues(docs));
        Arrays.stream(ErrorCode.values()).map(ErrorCode::name).forEach(known::add);
        List<String> unknown = new ArrayList<>();

        eachDescription(docs, (location, node) -> {
            for (String code : ObservedOutcomes.errorCodesIn(node.path("description").asString())) {
                if (!known.contains(code)) {
                    unknown.add(location + ": " + code);
                }
            }
        });

        assertThat(unknown).as("error codes the docs name that ErrorCode doesn't have").isEmpty();
    }

    @Test
    @DisplayName("No description states a length, size or range its schema already publishes as a constraint")
    void noDescriptionRepeatsAPublishedConstraint() throws Exception {
        List<String> repeated = new ArrayList<>();

        eachDescription(apiDocs(), (location, node) -> {
            if (publishesABound(node) && STATED_BOUND.matcher(node.path("description").asString()).find()) {
                repeated.add(location + ": " + node.path("description").asString());
            }
        });

        assertThat(repeated).as("descriptions repeating a constraint the schema publishes").isEmpty();
    }

    @Test
    @DisplayName("No schema publishes Integer.MAX_VALUE as a bound: a constraint without its own end publishes none")
    void noBoundIsIntegerMaxValue() throws Exception {
        List<String> unbounded = new ArrayList<>();

        eachNode(apiDocs(), "", (location, node) -> {
            for (String bound : BOUNDS) {
                if (node.path(bound).isNumber() && node.path(bound).asLong() == Integer.MAX_VALUE) {
                    unbounded.add(location + " " + bound);
                }
            }
        });

        assertThat(unbounded).as("bounds published as Integer.MAX_VALUE").isEmpty();
    }

    private JsonNode apiDocs() throws Exception {
        return JsonMapper.builder().build().readTree(getAs(null, "/v3/api-docs")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static boolean publishesABound(JsonNode node) {
        List<JsonNode> schemas = List.of(node, node.path("schema"), node.path("items"), node.path("schema").path("items"));
        return schemas.stream().anyMatch(schema -> BOUNDS.stream().anyMatch(schema::has));
    }

    private static Set<String> publishedEnumValues(JsonNode docs) {
        Set<String> values = new HashSet<>();
        eachNode(docs, "", (location, node) -> {
            for (JsonNode value : node.path("enum").values()) {
                values.add(value.asString());
            }
        });
        return values;
    }

    private static void eachDescription(JsonNode docs, BiConsumer<String, JsonNode> check) {
        eachNode(docs, "", (location, node) -> {
            if (node.path("description").isString()) {
                check.accept(location, node);
            }
        });
    }

    private static void eachNode(JsonNode node, String location, BiConsumer<String, JsonNode> visit) {
        if (node.isObject()) {
            visit.accept(location, node);
            for (Map.Entry<String, JsonNode> child : node.properties()) {
                eachNode(child.getValue(), location + " " + child.getKey(), visit);
            }
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                eachNode(node.get(index), location + " [" + index + "]", visit);
            }
        }
    }
}
