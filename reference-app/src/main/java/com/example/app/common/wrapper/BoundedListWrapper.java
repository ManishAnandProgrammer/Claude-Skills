package com.example.app.common.wrapper;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "A whole list, without pages")
public class BoundedListWrapper<T> {

    @Schema(description = "The items")
    private List<T> response;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Schema(description = "Optional extra data about the list")
    private Map<String, Object> extraDetails;

    public static <T> BoundedListWrapper<T> fromList(@NonNull List<T> list) {
        BoundedListWrapper<T> wrapper = new BoundedListWrapper<>();
        wrapper.response = List.copyOf(list);
        return wrapper;
    }

    public void addExtraDetails(@NonNull String key, Object value) {
        if (Objects.isNull(extraDetails)) {
            extraDetails = new LinkedHashMap<>();
        }
        extraDetails.put(key, value);
    }
}
