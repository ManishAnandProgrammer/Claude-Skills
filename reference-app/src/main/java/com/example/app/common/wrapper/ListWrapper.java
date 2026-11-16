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

/**
 * An unpaged list: only one the code bounds (an enum's values, a configured maximum enforced on create, a
 * fixed relation such as a student's subjects this session), or one the review profile's
 * {@code unpagedLists} names with the developer's statement. Paged lists use {@link PaginationWrapper} or
 * {@link CursorWrapper}, never this.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "A whole list, without pages")
public class ListWrapper<T> {

    @Schema(description = "The items")
    private List<T> response;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Schema(description = "Optional extra data about the list")
    private Map<String, Object> extraDetails;

    public static <T> ListWrapper<T> fromList(@NonNull List<T> list) {
        ListWrapper<T> wrapper = new ListWrapper<>();
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
