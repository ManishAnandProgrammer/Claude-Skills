package com.example.app.metadata.mapper;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.exception.JsonPointers;
import com.example.app.common.exception.RequestError;
import com.example.app.metadata.api.MetadataKey;
import com.example.app.metadata.domain.ConfigurationMetadata;
import com.example.app.metadata.dto.response.MetadataResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class MetadataMapper {

    private static final String VALUE = "value";

    private final JsonMapper jsonMapper;

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
     * A field this version of the record doesn't know (removed since, or written by a newer instance
     * during a deployment) is ignored rather than making the row unreadable.
     */
    public <T> T readStoredValue(ConfigurationMetadata metadata, MetadataKey<T> key) {
        return jsonMapper.readerFor(key.type())
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(metadata.getValue());
    }

    /** Every refusal points to the field, never Jackson's message, which names Java classes. */
    public <T> T readRequestValue(JsonNode value, MetadataKey<T> key) {
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

    private static String pointerOf(JacksonException exception) {
        List<String> tokens = new ArrayList<>(List.of(VALUE));
        tokens.addAll(JsonPointers.tokensOf(exception));
        return JsonPointers.of(tokens);
    }

    private static AppException unreadable(String pointer, String detail) {
        return AppException.withErrors(ErrorCode.REQUEST_UNREADABLE, List.of(RequestError.atPointer(pointer, detail)));
    }
}
