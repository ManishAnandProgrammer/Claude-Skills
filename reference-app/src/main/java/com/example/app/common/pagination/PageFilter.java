package com.example.app.common.pagination;

import com.example.app.common.limits.ConfiguredMax;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public abstract class PageFilter {

    @Min(0)
    @ConfiguredMax("offset-page.max-page")
    @Schema(description = "Zero-based page index", defaultValue = "0")
    private int page;

    @Min(1)
    @ConfiguredMax("offset-page.max-size")
    @Schema(description = "Page size; the default comes from configuration")
    private Integer size;
}
