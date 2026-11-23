package com.example.app.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
class SessionVersionOpenApiCustomizer implements OpenApiCustomizer {

    private static final String UNAUTHORIZED = "401";
    private static final String SIGNED_IN = "AUTHENTICATION_REQUIRED";
    private static final String STALE = """
            ; SESSION_VERSION_STALE: the account's access changed after this token was issued (a role \
            change, a removal, signing out everywhere): refresh the tokens, then repeat the request""";

    @Override
    public void customise(OpenAPI openApi) {
        if (Objects.isNull(openApi.getPaths())) {
            return;
        }
        for (PathItem path : openApi.getPaths().values()) {
            for (Operation operation : path.readOperations()) {
                if (Objects.nonNull(operation.getResponses())) {
                    addStale(operation.getResponses().get(UNAUTHORIZED));
                }
            }
        }
    }

    private static void addStale(ApiResponse unauthorized) {
        if (Objects.isNull(unauthorized) || Objects.isNull(unauthorized.getDescription())
                || !unauthorized.getDescription().contains(SIGNED_IN) || unauthorized.getDescription().contains(STALE)) {
            return;
        }
        unauthorized.setDescription(unauthorized.getDescription() + STALE);
    }
}
