package com.example.app.common.pagination;

import com.example.app.common.limits.ConfiguredMax;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/**
 * Query parameters of an offset-paged list (bounded admin tables). A mutable class: query
 * parameters bind through setters. Sizes and the page cap come from {@code app.limits.offset-page};
 * a value beyond them is a 400, never silently clamped.
 */
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
