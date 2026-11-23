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

/** Refusals that come before any handler, so no operation's own documentation names them. */
@Component
class FrameworkRefusalsOpenApiCustomizer implements OpenApiCustomizer {

    private static final String PROBLEM_SCHEMA = "#/components/schemas/" + ProblemDetail.class.getSimpleName();
    private static final String FIREWALL_REFUSAL = ErrorCode.REQUEST_INVALID.name()
            + " (a path the security firewall refuses: an empty segment, a ';', an encoded '.')";
    private static final String NOT_ACCEPTABLE = ErrorCode.REQUEST_NOT_ACCEPTABLE.name()
            + " (an Accept header that allows no JSON response)";
    private static final String UNSUPPORTED_BODY = ErrorCode.REQUEST_MEDIA_TYPE_UNSUPPORTED.name()
            + " (a body sent as another type than application/json)";

    @Override
    public void customise(OpenAPI openApi) {
        if (Objects.isNull(openApi.getPaths())) {
            return;
        }
        for (PathItem path : openApi.getPaths().values()) {
            for (Operation operation : path.readOperations()) {
                if (Objects.isNull(operation.getResponses())) {
                    operation.setResponses(new ApiResponses());
                }
                document(operation.getResponses(), "400", FIREWALL_REFUSAL);
                document(operation.getResponses(), "406", NOT_ACCEPTABLE);
                if (Objects.nonNull(operation.getRequestBody())) {
                    document(operation.getResponses(), "415", UNSUPPORTED_BODY);
                }
            }
        }
    }

    private static void document(ApiResponses responses, String status, String refusal) {
        ApiResponse response = responses.get(status);
        if (Objects.isNull(response)) {
            responses.addApiResponse(status, new ApiResponse()
                    .description(refusal)
                    .content(new Content().addMediaType(APPLICATION_PROBLEM_JSON_VALUE,
                            new MediaType().schema(new Schema<>().$ref(PROBLEM_SCHEMA)))));
        } else if (Objects.isNull(response.getDescription())) {
            response.setDescription(refusal);
        } else if (!response.getDescription().contains(refusal)) {
            response.setDescription(response.getDescription() + "; " + refusal);
        }
    }
}
