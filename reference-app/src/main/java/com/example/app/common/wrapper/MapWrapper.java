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
@Schema(description = "Values keyed by name")
public class MapWrapper<K, V> {

    @Schema(description = "The values")
    private Map<K, V> response;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Schema(description = "Optional extra data")
    private Map<String, Object> extraDetails;

    public static <K, V> MapWrapper<K, V> fromMap(@NonNull Map<K, V> map) {
        MapWrapper<K, V> wrapper = new MapWrapper<>();
        wrapper.response = new LinkedHashMap<>(map);
        return wrapper;
    }

    public void addExtraDetails(@NonNull String key, Object value) {
        if (Objects.isNull(extraDetails)) {
            extraDetails = new LinkedHashMap<>();
        }
        extraDetails.put(key, value);
    }
}
