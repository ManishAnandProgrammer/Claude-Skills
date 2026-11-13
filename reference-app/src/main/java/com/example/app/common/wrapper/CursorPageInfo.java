package com.example.app.common.wrapper;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Position of this batch in a cursor-paged list")
public record CursorPageInfo(
        @Schema(description = "Send as 'after' for the next batch; absent on the last batch") String nextCursor,
        @Schema(description = "Whether a next batch exists", example = "true") boolean hasMore) {
}
