package com.example.app.common.web;

import com.example.app.common.exception.RequestError;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Documents the members every error response adds to Spring's {@code ProblemDetail}: springdoc only
 * sees the class, whose extension members are a map it can't describe.
 */
@Component
class ProblemSchemaOpenApiCustomizer implements OpenApiCustomizer {

    private static final String PROBLEM_DETAIL = "ProblemDetail";
    private static final String REQUEST_ERROR = "RequestError";

    @Override
    public void customise(OpenAPI openApi) {
        if (Objects.isNull(openApi.getComponents()) || Objects.isNull(openApi.getComponents().getSchemas())) {
            return;
        }
        Schema<?> problem = openApi.getComponents().getSchemas().get(PROBLEM_DETAIL);
        if (Objects.isNull(problem)) {
            return;
        }
        Schema<?> requestError = ModelConverters.getInstance()
                .resolveAsResolvedSchema(new AnnotatedType(RequestError.class))
                .schema;
        openApi.getComponents().addSchemas(REQUEST_ERROR, requestError);
        problem.addProperty(ProblemTypes.ERROR_CODE, new StringSchema()
                .description("The failure, the same code as the end of type; clients act on it")
                .example("VALIDATION_FAILED"));
        problem.addProperty(ProblemTypes.ERRORS, new ArraySchema()
                .items(new Schema<>().$ref(REQUEST_ERROR))
                .description("""
                        The request values that failed (RFC 9457): on VALIDATION_FAILED, and on REQUEST_PARAMETER_INVALID, \
                        REQUEST_PARAMETER_MISSING and REQUEST_UNREADABLE when the value is known"""));
    }
}
