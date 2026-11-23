package com.example.app.support;

import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.AllowedAction;
import com.example.app.common.security.Permission;
import com.example.app.common.security.Requires;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * A row names a flag's endpoint ({@code "PATCH /api/{version}/plans/{id}/status"}), never its permission: the
 * permission is read from the endpoint's {@link Requires}, so the flag can't drift from it (house-review AUTHZ-07).
 */
public final class AllowedActionContract {

    private static final String VERSION = "v1";
    private static final String HANDLER_MAPPING = "requestMappingHandlerMapping";
    private static final String ACCESS_DENIED = ErrorCode.ACCESS_DENIED.name();

    private final Class<? extends Record> record;
    private final Map<String, Endpoint> endpointByFlag = new LinkedHashMap<>();
    private final List<Read> reads = new ArrayList<>();

    private AllowedActionContract(Class<? extends Record> record) {
        this.record = record;
    }

    public static AllowedActionContract of(Class<? extends Record> record) {
        return new AllowedActionContract(record);
    }

    public AllowedActionContract flag(String name, String endpoint) {
        assertThat(endpointByFlag.put(name, Endpoint.parse(endpoint))).as("a second row for " + name).isNull();
        return this;
    }

    /**
     * {@code jsonPath} points at the record, or at each of a list's records ({@code "$.response[*].allowedActions"});
     * {@code variables} fill the endpoint's variables after {@code {version}}, in order.
     */
    public AllowedActionContract readFrom(String endpoint, String jsonPath, Object... variables) {
        reads.add(new Read(Endpoint.parse(endpoint), jsonPath, List.of(variables)));
        return this;
    }

    public void verify(MockMvc mvc, Function<Set<Permission>, RequestPostProcessor> holding) throws Exception {
        RequestMappingHandlerMapping handlers = mvc.getDispatcherServlet().getWebApplicationContext()
                .getBean(HANDLER_MAPPING, RequestMappingHandlerMapping.class);
        String name = record.getSimpleName();
        assertThat(endpointByFlag.keySet()).as("rows of %s against its AllowedAction components", name)
                .containsExactlyInAnyOrderElementsOf(flagsOf(record));
        assertThat(reads).as("reads of " + name).isNotEmpty();

        List<String> broken = new ArrayList<>();
        Map<String, Set<Permission>> acceptedByFlag = new LinkedHashMap<>();
        endpointByFlag.forEach((flag, endpoint) -> acceptedBy(handlers, endpoint, broken).ifPresent(accepted -> {
            if (accepted.isEmpty()) {
                broken.add("%s: %s requires no permission, so no caller sees it ACCESS_DENIED".formatted(flag, endpoint));
            } else {
                acceptedByFlag.put(flag, accepted);
            }
        }));
        Map<Read, Set<Permission>> readPermissions = new LinkedHashMap<>();
        for (Read read : reads) {
            acceptedBy(handlers, read.endpoint(), broken).ifPresent(accepted -> readPermissions.put(read, accepted));
        }
        assertThat(broken).as("rows and reads of %s that name no served endpoint", name).isEmpty();

        for (Map.Entry<String, Set<Permission>> row : acceptedByFlag.entrySet()) {
            for (Map.Entry<Read, Set<Permission>> read : readPermissions.entrySet()) {
                checkDenied(mvc, holding, row.getKey(), row.getValue(), read.getKey(), read.getValue(), broken);
                for (Permission permission : row.getValue()) {
                    checkGranted(mvc, holding, row.getKey(), permission, read.getKey(), read.getValue(), broken);
                }
            }
        }
        assertThat(broken).as("flags of %s whose permission isn't their endpoint's", name).isEmpty();
    }

    /**
     * A read that needs one of the flag's permissions is itself 403 to a caller without them: no one sees the flag
     * refused for its permission there, so its granted side alone tells it apart from another permission.
     */
    private static void checkDenied(MockMvc mvc, Function<Set<Permission>, RequestPostProcessor> holding, String flag,
                                    Set<Permission> accepted, Read read, Set<Permission> readAccepted, List<String> broken)
            throws Exception {
        Set<Permission> allButAccepted = EnumSet.allOf(Permission.class);
        allButAccepted.removeAll(accepted);
        String caller = "without " + accepted;
        MockHttpServletResponse answer = read.send(mvc, holding.apply(allButAccepted));
        if (!readAccepted.isEmpty() && !holdsAnyOf(allButAccepted, readAccepted)) {
            if (answer.getStatus() != 403) {
                broken.add("%s %s answered %d, not 403".formatted(read, caller, answer.getStatus()));
            }
            return;
        }
        for (Object flagged : read.flagsIn(answer, flag, caller, broken)) {
            List<String> codes = reasonCodes(flagged);
            if (!Boolean.FALSE.equals(allowedOf(flagged)) || !codes.equals(List.of(ACCESS_DENIED))) {
                broken.add("%s %s: %s is %s, not refused with ACCESS_DENIED alone".formatted(read, caller, flag, flagged));
            }
        }
    }

    /**
     * Each of an any-of endpoint's permissions lets the call in alone, so each must clear the flag alone: the caller
     * holds that one, its prerequisites and, when they don't already cover it, one the read accepts.
     */
    private static void checkGranted(MockMvc mvc, Function<Set<Permission>, RequestPostProcessor> holding, String flag,
                                     Permission permission, Read read, Set<Permission> readAccepted, List<String> broken)
            throws Exception {
        Set<Permission> held = withPrerequisites(permission);
        if (!readAccepted.isEmpty() && !holdsAnyOf(held, readAccepted)) {
            held.addAll(withPrerequisites(readAccepted.stream().sorted().findFirst().orElseThrow()));
        }
        String caller = "holding only " + held;
        for (Object flagged : read.flagsIn(read.send(mvc, holding.apply(held)), flag, caller, broken)) {
            if (reasonCodes(flagged).contains(ACCESS_DENIED)) {
                broken.add("%s %s: %s is %s, refused although %s lets the call in".formatted(read, caller, flag, flagged, permission));
            }
        }
    }

    private static Set<String> flagsOf(Class<? extends Record> record) {
        Set<String> flags = new TreeSet<>();
        for (RecordComponent component : record.getRecordComponents()) {
            if (component.getType() == AllowedAction.class) {
                flags.add(component.getName());
            }
        }
        return flags;
    }

    private static Optional<Set<Permission>> acceptedBy(RequestMappingHandlerMapping handlers, Endpoint endpoint,
                                                        List<String> broken) {
        Set<Set<Permission>> accepted = new HashSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> mapping : handlers.getHandlerMethods().entrySet()) {
            if (endpoint.isServedBy(mapping.getKey())) {
                Requires requires = mapping.getValue().getMethodAnnotation(Requires.class);
                accepted.add(Objects.isNull(requires) ? Set.of() : Set.copyOf(Arrays.asList(requires.value())));
            }
        }
        if (accepted.size() != 1) {
            broken.add(endpoint + (accepted.isEmpty() ? " is not served" : " requires " + accepted + " by version"));
            return Optional.empty();
        }
        return Optional.of(accepted.iterator().next());
    }

    private static Set<Permission> withPrerequisites(Permission permission) {
        Set<Permission> held = EnumSet.of(permission);
        for (Permission prerequisite : permission.prerequisites()) {
            held.addAll(withPrerequisites(prerequisite));
        }
        return held;
    }

    private static Object allowedOf(Object flagged) {
        return flagged instanceof Map<?, ?> action ? action.get("allowed") : null;
    }

    private static List<String> reasonCodes(Object flagged) {
        List<String> codes = new ArrayList<>();
        if (flagged instanceof Map<?, ?> action && action.get("reasons") instanceof List<?> reasons) {
            for (Object reason : reasons) {
                if (reason instanceof Map<?, ?> fields) {
                    codes.add(String.valueOf(fields.get("errorCode")));
                }
            }
        }
        return codes;
    }

    private static boolean holdsAnyOf(Set<Permission> held, Set<Permission> accepted) {
        return accepted.stream().anyMatch(held::contains);
    }

    private record Endpoint(HttpMethod method, String template) {

        static Endpoint parse(String declared) {
            String[] parts = declared.trim().split("\\s+");
            assertThat(parts).as("an endpoint as METHOD /path, not " + declared).hasSize(2);
            return new Endpoint(HttpMethod.valueOf(parts[0]), parts[1]);
        }

        String pattern() {
            int query = template.indexOf('?');
            return query < 0 ? template : template.substring(0, query);
        }

        boolean isServedBy(RequestMappingInfo mapping) {
            Set<RequestMethod> methods = mapping.getMethodsCondition().getMethods();
            return Objects.nonNull(mapping.getPathPatternsCondition())
                    && methods.contains(RequestMethod.valueOf(method.name()))
                    && mapping.getPathPatternsCondition().getPatternValues().contains(pattern());
        }

        @Override
        public String toString() {
            return method + " " + template;
        }
    }

    private record Read(Endpoint endpoint, String jsonPath, List<Object> variables) {

        MockHttpServletResponse send(MockMvc mvc, RequestPostProcessor caller) throws Exception {
            List<Object> expanded = new ArrayList<>();
            expanded.add(VERSION);
            expanded.addAll(variables);
            return mvc.perform(request(endpoint.method(), endpoint.template(), expanded.toArray()).with(caller))
                    .andReturn().getResponse();
        }

        List<Object> flagsIn(MockHttpServletResponse answer, String flag, String caller, List<String> broken)
                throws Exception {
            String body = answer.getContentAsString();
            if (answer.getStatus() != 200) {
                broken.add("%s %s answered %d %s".formatted(this, caller, answer.getStatus(), body));
                return List.of();
            }
            Object found;
            try {
                found = JsonPath.read(body, jsonPath);
            } catch (PathNotFoundException missing) {
                broken.add("%s %s: nothing at %s in %s".formatted(this, caller, jsonPath, body));
                return List.of();
            }
            List<?> records = found instanceof List<?> list ? list : List.of(found);
            if (records.isEmpty()) {
                broken.add("%s %s: no record at %s".formatted(this, caller, jsonPath));
            }
            List<Object> flags = new ArrayList<>();
            for (Object record : records) {
                if (record instanceof Map<?, ?> fields && fields.containsKey(flag)) {
                    flags.add(fields.get(flag));
                } else {
                    broken.add("%s %s: no %s in %s".formatted(this, caller, flag, record));
                }
            }
            return flags;
        }

        @Override
        public String toString() {
            return endpoint + " at " + jsonPath;
        }
    }
}
