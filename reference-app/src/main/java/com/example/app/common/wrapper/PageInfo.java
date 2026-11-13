package com.example.app.common.wrapper;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

@Schema(description = "Position of this page in the full list")
public record PageInfo(
        @Schema(description = "Zero-based page index", example = "0") int number,
        @Schema(description = "Page size actually used (capped at 100)", example = "20") int size,
        @Schema(description = "Items across all pages", example = "57") long totalElements,
        @Schema(description = "Number of pages", example = "3") int totalPages,
        @Schema(description = "Whether a page follows this one", example = "true") boolean hasNext,
        @Schema(description = "Whether a page precedes this one", example = "false") boolean hasPrevious) {

    public static PageInfo of(Page<?> page) {
        return new PageInfo(page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages(),
                page.hasNext(), page.hasPrevious());
    }
}
