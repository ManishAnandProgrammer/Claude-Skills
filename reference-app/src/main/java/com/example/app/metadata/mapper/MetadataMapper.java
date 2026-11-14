package com.example.app.metadata.mapper;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.metadata.api.MetadataKey;
import com.example.app.metadata.domain.ConfigurationMetadata;
import com.example.app.metadata.dto.response.MetadataResponse;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Objects;

/**
 * Values to and from JSON with the application's {@code JsonMapper}, the one that reads request
 * bodies: the same ISO dates and the same strictness, except for stored documents.
 */
@Component
public class MetadataMapper {

    private static final String VALUE = "value";

    private final JsonMapper jsonMapper;

    public MetadataMapper(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public <T> ConfigurationMetadata toEntity(MetadataKey<T> key, T value) {
        return ConfigurationMetadata.builder()
                .key(key.name())
                .value(toJson(value))
                .build();
    }

    public String toJson(Object value) {
        return jsonMapper.writeValueAsString(value);
    }

    /**
     * Tolerant of unknown fields (DATA-27): a field this version of the record doesn't know (removed
     * since, or written by a newer instance during a deployment) is ignored rather than making the row
     * unreadable. A missing field takes the record's default.
     */
    public <T> T toValue(ConfigurationMetadata metadata, MetadataKey<T> key) {
        return jsonMapper.readerFor(key.type())
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(metadata.getValue());
    }

    /**
     * An admin's value, read strictly like any request body: a misspelt field is refused, not dropped.
     * Every refusal is 400 {@code REQUEST_UNREADABLE} naming the field ({@code value.graceMinutes}),
     * never Jackson's message, which names Java classes.
     */
    public <T> T toValue(JsonNode value, MetadataKey<T> key) {
        if (!value.isObject()) {
            throw AppException.withFailedValidations(ErrorCode.REQUEST_UNREADABLE, List.of(VALUE + ": must be a JSON object"));
        }
        try {
            return jsonMapper.treeToValue(value, key.type());
        } catch (UnrecognizedPropertyException unknown) {
            throw AppException.withFailedValidations(ErrorCode.REQUEST_UNREADABLE, List.of(pathOf(unknown) + ": unknown field"));
        } catch (JacksonException unreadable) {
            throw AppException.withFailedValidations(ErrorCode.REQUEST_UNREADABLE, List.of(pathOf(unreadable) + ": invalid value"));
        }
    }

    public MetadataResponse toResponse(ConfigurationMetadata metadata, Object value) {
        return new MetadataResponse(
                metadata.getKey(),
                value,
                metadata.getCreatedAt(),
                metadata.getUpdatedAt(),
                metadata.getCreatedBy(),
                metadata.getUpdatedBy(),
                metadata.getVersion());
    }

    private static String pathOf(JacksonException exception) {
        StringBuilder path = new StringBuilder(VALUE);
        for (JacksonException.Reference reference : exception.getPath()) {
            if (Objects.nonNull(reference.getPropertyName())) {
                path.append('.').append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }
}
