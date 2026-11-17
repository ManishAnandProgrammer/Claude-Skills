package com.example.app.config;

import com.example.app.common.exception.ErrorCode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import java.util.Objects;

import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;

/**
 * Adds {@code CORS_REQUEST_REFUSED} to the 403 of every operation: the CORS filter ({@link ProblemCorsProcessor})
 * can answer it on every path, so it is written here once instead of on each {@code *Api} interface.
 */
@Component
class CorsOpenApiCustomizer implements OpenApiCustomizer {

    private static final String FORBIDDEN = "403";
    private static final String DESCRIPTION = ErrorCode.CORS_REQUEST_REFUSED.name()
            + " (a cross-origin request from an origin, or a preflight with a method or headers, the CORS settings don't allow)";
    private static final String PROBLEM_SCHEMA = "#/components/schemas/" + ProblemDetail.class.getSimpleName();

    @Override
    public void customise(OpenAPI openApi) {
        if (Objects.isNull(openApi.getPaths())) {
            return;
        }
        for (PathItem path : openApi.getPaths().values()) {
            for (Operation operation : path.readOperations()) {
                documentCorsRefusal(operation);
            }
        }
    }

    private static void documentCorsRefusal(Operation operation) {
        if (Objects.isNull(operation.getResponses())) {
            operation.setResponses(new ApiResponses());
        }
        ApiResponse forbidden = operation.getResponses().get(FORBIDDEN);
        if (Objects.isNull(forbidden)) {
            operation.getResponses().addApiResponse(FORBIDDEN, new ApiResponse()
                    .description(DESCRIPTION)
                    .content(new Content().addMediaType(APPLICATION_PROBLEM_JSON_VALUE,
                            new MediaType().schema(new Schema<>().$ref(PROBLEM_SCHEMA)))));
        } else if (Objects.isNull(forbidden.getDescription())) {
            forbidden.setDescription(DESCRIPTION);
        } else if (!forbidden.getDescription().contains(DESCRIPTION)) {
            forbidden.setDescription(forbidden.getDescription() + "; " + DESCRIPTION);
        }
    }
}
