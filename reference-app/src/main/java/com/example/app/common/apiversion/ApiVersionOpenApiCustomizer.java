package com.example.app.common.apiversion;

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

import java.util.Map;
import java.util.Objects;

import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;

@Component
class ApiVersionOpenApiCustomizer implements OpenApiCustomizer {

    private static final String API_PATHS = "/api/";
    private static final String BAD_REQUEST = "400";
    private static final String DESCRIPTION = ErrorCode.API_VERSION_UNSUPPORTED.name()
            + " (the version in the path is malformed or not supported)";
    private static final String PROBLEM_SCHEMA = "#/components/schemas/" + ProblemDetail.class.getSimpleName();

    @Override
    public void customise(OpenAPI openApi) {
        if (Objects.isNull(openApi.getPaths())) {
            return;
        }
        for (Map.Entry<String, PathItem> path : openApi.getPaths().entrySet()) {
            if (path.getKey().startsWith(API_PATHS)) {
                for (Operation operation : path.getValue().readOperations()) {
                    documentVersionRefusal(operation);
                }
            }
        }
    }

    private static void documentVersionRefusal(Operation operation) {
        if (Objects.isNull(operation.getResponses())) {
            operation.setResponses(new ApiResponses());
        }
        ApiResponse badRequest = operation.getResponses().get(BAD_REQUEST);
        if (Objects.isNull(badRequest)) {
            operation.getResponses().addApiResponse(BAD_REQUEST, new ApiResponse()
                    .description(DESCRIPTION)
                    .content(new Content().addMediaType(APPLICATION_PROBLEM_JSON_VALUE,
                            new MediaType().schema(new Schema<>().$ref(PROBLEM_SCHEMA)))));
        } else {
            badRequest.setDescription(badRequest.getDescription() + "; " + DESCRIPTION);
        }
    }
}
