package com.example.app.common.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

import java.util.Objects;

import static org.springframework.http.MediaType.ALL_VALUE;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;

/**
 * Publishes every error response as {@code application/problem+json}, the type the handler writes:
 * springdoc documents an {@code @Content} without a media type as {@code *}{@code /*}, and writing the
 * type on every error annotation of every {@code *Api} is the kind of repetition that drifts.
 */
@Component
class ProblemMediaTypeOpenApiCustomizer implements OpenApiCustomizer {

    private static final int FIRST_ERROR_STATUS = 400;

    @Override
    public void customise(OpenAPI openApi) {
        if (Objects.isNull(openApi.getPaths())) {
            return;
        }
        for (PathItem path : openApi.getPaths().values()) {
            for (Operation operation : path.readOperations()) {
                if (Objects.nonNull(operation.getResponses())) {
                    operation.getResponses().forEach(ProblemMediaTypeOpenApiCustomizer::retype);
                }
            }
        }
    }

    private static void retype(String status, ApiResponse response) {
        Content content = response.getContent();
        if (!isError(status) || Objects.isNull(content) || !content.containsKey(ALL_VALUE)) {
            return;
        }
        MediaType problem = content.remove(ALL_VALUE);
        content.putIfAbsent(APPLICATION_PROBLEM_JSON_VALUE, problem);
    }

    private static boolean isError(String status) {
        return status.chars().allMatch(Character::isDigit) && Integer.parseInt(status) >= FIRST_ERROR_STATUS;
    }
}
