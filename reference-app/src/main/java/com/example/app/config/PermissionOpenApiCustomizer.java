package com.example.app.config;

import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.Requires;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

import java.util.Objects;

import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;

/**
 * Documents the 403 of every {@code @Requires} handler from the annotation itself, {@code ACCESS_DENIED (without X)}
 * ahead of what its {@code *Api} interface says: the permission is written once, on the handler, so the published
 * document can't drift from it (house-review REST-20, AUTHZ-03). An interface that already names
 * {@code ACCESS_DENIED} (with its own reason) is left as it is.
 */
@Component
class PermissionOpenApiCustomizer implements OperationCustomizer {

    private static final String FORBIDDEN = "403";
    private static final String PROBLEM_SCHEMA = "#/components/schemas/" + ProblemDetail.class.getSimpleName();

    @Override
    public Operation customize(Operation operation, HandlerMethod handler) {
        Requires requires = handler.getMethodAnnotation(Requires.class);
        if (Objects.isNull(requires)) {
            return operation;
        }
        String refusal = "%s (without %s)".formatted(ErrorCode.ACCESS_DENIED.name(), requires.value().name());
        if (Objects.isNull(operation.getResponses())) {
            operation.setResponses(new ApiResponses());
        }
        ApiResponse forbidden = operation.getResponses().get(FORBIDDEN);
        if (Objects.isNull(forbidden)) {
            operation.getResponses().addApiResponse(FORBIDDEN, new ApiResponse()
                    .description(refusal)
                    .content(new Content().addMediaType(APPLICATION_PROBLEM_JSON_VALUE,
                            new MediaType().schema(new Schema<>().$ref(PROBLEM_SCHEMA)))));
        } else if (Objects.isNull(forbidden.getDescription())) {
            forbidden.setDescription(refusal);
        } else if (!forbidden.getDescription().contains(ErrorCode.ACCESS_DENIED.name())) {
            forbidden.setDescription(refusal + "; " + forbidden.getDescription());
        }
        return operation;
    }
}
