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
 * One batch of a cursor-paged list: the items under {@code response}, where the batch ends under
 * {@code pageInfo}. No total count: a cursor list is open-ended, and counting it costs a scan.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "One batch of a cursor-paged list")
public class CursorWrapper<T> {

    @Schema(description = "Items in this batch")
    private List<T> response;

    @Schema(description = "Where this batch ends")
    private CursorPageInfo pageInfo;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Schema(description = "Optional extra data about the list")
    private Map<String, Object> extraDetails;

    public static <T> CursorWrapper<T> of(@NonNull List<T> items, String nextCursor) {
        CursorWrapper<T> wrapper = new CursorWrapper<>();
        wrapper.response = List.copyOf(items);
        wrapper.pageInfo = new CursorPageInfo(nextCursor, Objects.nonNull(nextCursor));
        return wrapper;
    }

    public void addExtraDetails(@NonNull String key, Object value) {
        if (Objects.isNull(extraDetails)) {
            extraDetails = new LinkedHashMap<>();
        }
        extraDetails.put(key, value);
    }
}
