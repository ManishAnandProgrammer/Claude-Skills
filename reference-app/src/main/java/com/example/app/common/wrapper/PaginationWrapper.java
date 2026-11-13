package com.example.app.common.wrapper;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import org.springframework.data.domain.Page;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One page of a paginated list: the items under {@code response}, the page's position under an
 * explicit, documented {@code pageInfo}. Both come from the same {@link Page}, so the flags can't
 * disagree with the items.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "One page of a paginated list")
public class PaginationWrapper<T> {

    @Schema(description = "Items on this page")
    private List<T> response;

    @Schema(description = "Position of this page in the full list")
    private PageInfo pageInfo;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Schema(description = "Optional extra data about the list, such as counts")
    private Map<String, Object> extraDetails;

    public static <T> PaginationWrapper<T> fromPage(@NonNull Page<T> page) {
        PaginationWrapper<T> wrapper = new PaginationWrapper<>();
        wrapper.response = List.copyOf(page.getContent());
        wrapper.pageInfo = PageInfo.of(page);
        return wrapper;
    }

    public void addExtraDetails(@NonNull String key, Object value) {
        if (Objects.isNull(extraDetails)) {
            extraDetails = new LinkedHashMap<>();
        }
        extraDetails.put(key, value);
    }
}
