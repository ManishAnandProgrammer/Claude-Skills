package com.example.app.metadata.mapper;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.exception.JsonPointers;
import com.example.app.common.exception.RequestError;
import com.example.app.metadata.api.MetadataKey;
import com.example.app.metadata.domain.ConfigurationMetadata;
import com.example.app.metadata.dto.response.MetadataResponse;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

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
     * Every refusal is 400 {@code REQUEST_UNREADABLE} pointing to the field ({@code #/value/graceMinutes}),
     * never Jackson's message, which names Java classes.
     */
    public <T> T toValue(JsonNode value, MetadataKey<T> key) {
        if (!value.isObject()) {
            throw unreadable(JsonPointers.of(List.of(VALUE)), "must be a JSON object");
        }
        try {
            return jsonMapper.treeToValue(value, key.type());
        } catch (UnrecognizedPropertyException unknown) {
            throw unreadable(pointerOf(unknown), RequestError.UNKNOWN_FIELD);
        } catch (JacksonException unreadable) {
            throw unreadable(pointerOf(unreadable), RequestError.INVALID_VALUE);
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

    /** The value is the body's {@code value}, so Jackson's path inside it starts there. */
    private static String pointerOf(JacksonException exception) {
        List<String> tokens = new ArrayList<>(List.of(VALUE));
        tokens.addAll(JsonPointers.tokensOf(exception));
        return JsonPointers.of(tokens);
    }

    private static AppException unreadable(String pointer, String detail) {
        return AppException.withErrors(ErrorCode.REQUEST_UNREADABLE, List.of(RequestError.atPointer(pointer, detail)));
    }
}
