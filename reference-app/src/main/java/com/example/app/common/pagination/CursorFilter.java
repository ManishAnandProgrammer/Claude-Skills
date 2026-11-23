package com.example.app.common.pagination;

import com.example.app.common.limits.ConfiguredMax;
import com.example.app.common.limits.MaxLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public abstract class CursorFilter {

    @MaxLength("cursor")
    @Schema(description = "pageInfo.nextCursor of the previous batch; absent for the first batch")
    private String after;

    @Min(1)
    @ConfiguredMax("cursor-page.max-size")
    @Schema(description = "Batch size; the default comes from configuration")
    private Integer size;
}
