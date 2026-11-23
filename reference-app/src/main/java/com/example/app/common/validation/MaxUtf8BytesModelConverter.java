package com.example.app.common.validation;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.Schema;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.Objects;

/**
 * No string has more characters than its UTF-8 bytes, so {@link MaxUtf8Bytes} bounds the length too: published as
 * {@code maxLength}, also through a composed constraint ({@link PasswordPolicy}). Written on the model once swagger
 * has resolved it: swagger applies a field's {@code @Size} after every property customizer, and a {@code @Size}
 * without a max publishes {@code Integer.MAX_VALUE}.
 */
@Component
class MaxUtf8BytesModelConverter implements ModelConverter {

    private static final String REF_PREFIX = "#/components/schemas/";

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
                MaxUtf8Bytes maxBytes = AnnotatedElementUtils.findMergedAnnotation(field, MaxUtf8Bytes.class);
                Schema<?> property = (Schema<?>) model.getProperties().get(field.getName());
                if (Objects.nonNull(maxBytes) && Objects.nonNull(property)) {
                    property.setMaxLength(maxBytes.value());
                }
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
}
