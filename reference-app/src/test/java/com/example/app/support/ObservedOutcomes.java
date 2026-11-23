package com.example.app.support;

import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.ConfigurableMockMvcBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Found by component scanning, as any bean under the root package: every MockMvc context checks its responses
 * against its own {@code /v3/api-docs}, whichever test base builds it.
 */
@Component
public class ObservedOutcomes implements MockMvcBuilderCustomizer {

    private static final Pattern ERROR_CODE = Pattern.compile("\\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\\b");
    private static final String API_DOCS = "/v3/api-docs";
    private static final String ACTUATOR = "/actuator";
    private static final String VERSION_VARIABLE = "version";
    private static final String HANDLER_MAPPING = "requestMappingHandlerMapping";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ApplicationContext context;
    private JsonNode apiDocs;

    ObservedOutcomes(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public void customize(ConfigurableMockMvcBuilder<?> builder) {
        if (context instanceof WebApplicationContext webContext) {
            builder.alwaysDo(result -> assertDocumented(webContext, result));
        }
    }

    private void assertDocumented(WebApplicationContext webContext, MvcResult result) throws Exception {
        Optional<HandledPath> handled = handledPath(webContext, result.getRequest());
        if (handled.isEmpty() || isUndocumentedByDesign(handled.get().pattern())) {
            return;
        }
        String method = result.getRequest().getMethod().toLowerCase(Locale.ROOT);
        JsonNode operation = apiDocs(webContext).path("paths").path(handled.get().documentedPath()).path(method);
        if (operation.isMissingNode()) {
            return;
        }
        MockHttpServletResponse response = result.getResponse();
        String status = String.valueOf(response.getStatus());
        Optional<String> errorCode = errorCodeOf(response);
        String outcome = "%s %s answered %s%s".formatted(method.toUpperCase(Locale.ROOT), handled.get().documentedPath(),
                status, errorCode.map(code -> " " + code).orElse(""));
        JsonNode documented = operation.path("responses").path(status);
        if (documented.isMissingNode()) {
            throw new AssertionError(outcome + ", a status its operation doesn't document");
        }
        if (errorCode.isPresent() && !errorCodesIn(documented.path("description").asString("")).contains(errorCode.get())) {
            throw new AssertionError(outcome + ", a code its " + status + " description doesn't name: "
                    + documented.path("description").asString(""));
        }
    }

    private static boolean isUndocumentedByDesign(String pattern) {
        return pattern.startsWith(ACTUATOR) || pattern.startsWith(API_DOCS);
    }

    private record HandledPath(String pattern, Map<String, String> variables) {

        String documentedPath() {
            String version = variables.get(VERSION_VARIABLE);
            return Objects.isNull(version) ? pattern : pattern.replace("{" + VERSION_VARIABLE + "}", version);
        }
    }

    /**
     * A response written by a filter (a 401 from the security chain, a 503 from admission) never reached the
     * dispatcher: its handler is looked up again on a copy of the request.
     */
    private static Optional<HandledPath> handledPath(WebApplicationContext webContext, MockHttpServletRequest request) {
        Optional<HandledPath> dispatched = handledPathOf(request);
        if (dispatched.isPresent()) {
            return dispatched;
        }
        MockHttpServletRequest copy = copyOf(request);
        try {
            ServletRequestPathUtils.parseAndCache(copy);
            if (Objects.isNull(webContext.getBean(HANDLER_MAPPING, RequestMappingHandlerMapping.class).getHandler(copy))) {
                return Optional.empty();
            }
        } catch (Exception noHandlerTakesTheRequest) {
            return Optional.empty();
        }
        return handledPathOf(copy);
    }

    @SuppressWarnings("unchecked")
    private static Optional<HandledPath> handledPathOf(MockHttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (Objects.isNull(pattern)) {
            return Optional.empty();
        }
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return Optional.of(new HandledPath(pattern.toString(),
                Objects.isNull(variables) ? Map.of() : (Map<String, String>) variables));
    }

    private static MockHttpServletRequest copyOf(MockHttpServletRequest request) {
        MockHttpServletRequest copy = new MockHttpServletRequest(request.getServletContext(), request.getMethod(),
                request.getRequestURI());
        copy.setQueryString(request.getQueryString());
        copy.setParameters(request.getParameterMap());
        for (String name : Collections.list(request.getHeaderNames())) {
            for (String value : Collections.list(request.getHeaders(name))) {
                copy.addHeader(name, value);
            }
        }
        return copy;
    }

    private static Optional<String> errorCodeOf(MockHttpServletResponse response) throws Exception {
        if (Objects.isNull(response.getContentType())
                || !MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(MediaType.parseMediaType(response.getContentType()))) {
            return Optional.empty();
        }
        JsonNode errorCode = JSON.readTree(response.getContentAsString()).path("errorCode");
        return errorCode.isString() ? Optional.of(errorCode.asString()) : Optional.empty();
    }

    public static Set<String> errorCodesIn(String text) {
        Set<String> codes = new HashSet<>();
        Matcher matcher = ERROR_CODE.matcher(text);
        while (matcher.find()) {
            codes.add(matcher.group());
        }
        return codes;
    }

    private synchronized JsonNode apiDocs(WebApplicationContext webContext) throws Exception {
        if (Objects.isNull(apiDocs)) {
            MockHttpServletResponse docs = MockMvcBuilders.webAppContextSetup(webContext).build()
                    .perform(get(API_DOCS)).andReturn().getResponse();
            if (docs.getStatus() != HttpStatus.OK.value()) {
                throw new AssertionError(API_DOCS + " answered " + docs.getStatus() + ": no outcome can be checked");
            }
            apiDocs = JSON.readTree(docs.getContentAsString());
        }
        return apiDocs;
    }
}
