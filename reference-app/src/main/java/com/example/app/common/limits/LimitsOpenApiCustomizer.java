package com.example.app.common.limits;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.ParameterCustomizer;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Writes the configured limits into the API docs: {@code maxLength} for {@link MaxLength} body
 * fields and query parameters, {@code maximum} for {@link ConfiguredMax}, {@code maxItems} for
 * {@link ConfiguredMaxSize}. Without it the schema couldn't show a limit that lives in configuration.
 */
@Component
class LimitsOpenApiCustomizer implements PropertyCustomizer, ParameterCustomizer {

    private final Limits limits;

    LimitsOpenApiCustomizer(Limits limits) {
        this.limits = limits;
    }

    @Override
    public Schema customize(Schema property, AnnotatedType type) {
        if (Objects.nonNull(type.getCtxAnnotations())) {
            for (Annotation annotation : type.getCtxAnnotations()) {
                apply(property, annotation);
            }
        }
        return property;
    }

    @Override
    public Parameter customize(Parameter parameter, MethodParameter methodParameter) {
        if (Objects.nonNull(parameter) && Objects.nonNull(parameter.getSchema())) {
            for (Annotation annotation : methodParameter.getParameterAnnotations()) {
                apply(parameter.getSchema(), annotation);
            }
        }
        return parameter;
    }

    private void apply(Schema<?> schema, Annotation annotation) {
        if (annotation instanceof MaxLength maxLength) {
            schema.setMaxLength(limits.maxLength(maxLength.value()));
        } else if (annotation instanceof ConfiguredMax configuredMax) {
            schema.setMaximum(BigDecimal.valueOf(limits.getLong(configuredMax.value())));
        } else if (annotation instanceof ConfiguredMaxSize configuredMaxSize) {
            schema.setMaxItems(limits.get(configuredMaxSize.value()));
        }
    }
}
