package com.example.app.common.wrapper;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "One object")
public class ObjectWrapper<T> {

    @Schema(description = "The object")
    private T response;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Schema(description = "Optional extra data about the object")
    private Map<String, Object> extraDetails;

    public static <T> ObjectWrapper<T> withObject(T object) {
        ObjectWrapper<T> wrapper = new ObjectWrapper<>();
        wrapper.response = object;
        return wrapper;
    }

    public void addExtraDetails(@NonNull String key, Object value) {
        if (Objects.isNull(extraDetails)) {
            extraDetails = new LinkedHashMap<>();
        }
        extraDetails.put(key, value);
    }
}
