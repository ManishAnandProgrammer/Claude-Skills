package com.example.app.todo;

import com.example.app.common.security.Permission;
import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AnnotationTemplateExpressionDefaults;
import org.springframework.security.core.annotation.SecurityAnnotationScanners;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RequiresAnyOfIntegrationTest extends IntegrationTest {

    private static final String PROBE = "/api/v1/requires-any-of-probes";

    @Test
    @DisplayName("Spring Security's template renders @Requires' permissions as hasAnyAuthority's arguments, one or several")
    void theTemplateRendersEveryPermission() throws Exception {
        assertThat(expressionOn("anyOf")).isEqualTo("hasAnyAuthority('TODO_READ_OWN,PLAN_READ_OWN'.split(','))");
        assertThat(expressionOn("one")).isEqualTo("hasAnyAuthority('TODO_READ_OWN'.split(','))");
    }

    @Test
    @DisplayName("A caller holding any one of an endpoint's permissions is let in; one holding none of them is 403 ACCESS_DENIED")
    void anyOneOfThePermissionsLetsTheCallerIn() throws Exception {
        String token = newUser().accessToken();
        Set<Permission> accepted = Set.of(Permission.TODO_READ_OWN, Permission.PLAN_READ_OWN);

        for (Permission held : accepted) {
            mvc.perform(get(PROBE).with(holding(token, Set.of(held)))).andExpect(status().isNoContent());
        }
        Set<Permission> neither = EnumSet.allOf(Permission.class);
        neither.removeAll(accepted);
        mvc.perform(get(PROBE).with(holding(token, neither)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("The OpenAPI 403 names every permission the endpoint accepts")
    void theDocsNameEveryPermission() throws Exception {
        getAs(null, "/v3/api-docs")
                .andExpect(jsonPath("$.paths['/api/v1/requires-any-of-probes'].get.responses['403'].description")
                        .value(startsWith("ACCESS_DENIED (without any of TODO_READ_OWN, PLAN_READ_OWN)")));
    }

    private static String expressionOn(String handler) throws NoSuchMethodException {
        Method method = RequiresAnyOfProbeController.class.getDeclaredMethod(handler);
        PreAuthorize resolved = SecurityAnnotationScanners
                .requireUnique(PreAuthorize.class, new AnnotationTemplateExpressionDefaults())
                .scan(method, method.getDeclaringClass());
        return resolved.value();
    }
}
