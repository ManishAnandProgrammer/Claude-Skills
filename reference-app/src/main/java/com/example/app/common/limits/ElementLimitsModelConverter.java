package com.example.app.common.limits;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.Schema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.Objects;

/**
 * The property customizer can't write a {@link MaxLength} on a collection's element type into the API docs:
 * swagger only hands it the field's own annotations, never those on a type argument.
 */
@Component
@RequiredArgsConstructor
class ElementLimitsModelConverter implements ModelConverter {

    private static final String REF_PREFIX = "#/components/schemas/";

    private final Limits limits;

    @Override
    public Schema resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        if (!chain.hasNext()) {
            return null;
        }
        Schema resolved = chain.next().resolve(type, context, chain);
        if (Objects.isNull(resolved) || Objects.isNull(type.getType())) {
            return resolved;
        }
        Schema model = definitionOf(resolved, context);
        if (Objects.nonNull(model) && Objects.nonNull(model.getProperties())) {
            Class<?> modelClass = Json.mapper().constructType(type.getType()).getRawClass();
            for (Field field : modelClass.getDeclaredFields()) {
                applyElementLimit(field, (Schema<?>) model.getProperties().get(field.getName()));
            }
        }
        return resolved;
    }

    private static Schema definitionOf(Schema resolved, ModelConverterContext context) {
        if (Objects.isNull(resolved.get$ref())) {
            return resolved;
        }
        return context.getDefinedModels().get(resolved.get$ref().replace(REF_PREFIX, ""));
    }

    private void applyElementLimit(Field field, Schema<?> property) {
        if (Objects.isNull(property) || Objects.isNull(property.getItems())
                || !(field.getAnnotatedType() instanceof AnnotatedParameterizedType parameterized)) {
            return;
        }
        for (java.lang.reflect.AnnotatedType argument : parameterized.getAnnotatedActualTypeArguments()) {
            MaxLength maxLength = argument.getAnnotation(MaxLength.class);
            if (Objects.nonNull(maxLength)) {
                property.getItems().setMaxLength(limits.maxLength(maxLength.value()));
            }
        }
    }
}
