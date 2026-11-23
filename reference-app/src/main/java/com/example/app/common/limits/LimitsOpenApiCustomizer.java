package com.example.app.common.limits;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.customizers.ParameterCustomizer;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.util.Objects;

@Component
@RequiredArgsConstructor
class LimitsOpenApiCustomizer implements PropertyCustomizer, ParameterCustomizer {

    private final Limits limits;

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
            schema.setMaximum(limits.getDecimal(configuredMax.value()));
        } else if (annotation instanceof ConfiguredMaxSize configuredMaxSize && Objects.nonNull(schema.getItems())) {
            // Swagger also calls this for the collection's items with the field's annotations: the
            // size belongs on the collection only.
            schema.setMaxItems(limits.get(configuredMaxSize.value()));
        }
    }
}
