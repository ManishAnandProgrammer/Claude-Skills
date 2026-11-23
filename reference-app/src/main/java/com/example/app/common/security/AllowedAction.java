package com.example.app.common.security;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = """
        Whether you may do this action with this record. When you may not, every reason is listed at once, so all \
        of them can be shown and fixed together; the server checks again on every call""")
public record AllowedAction(
        @Schema(description = "Whether you may do it now", example = "false") boolean allowed,
        @ArraySchema(arraySchema = @Schema(description = """
                Why you may not, most fundamental first: the error the action would answer now is the first. Empty \
                when allowed. A record you can't reach (not yours) lists that reason alone"""))
        List<AllowedActionReason> reasons) {

    public AllowedAction {
        reasons = List.copyOf(reasons);
    }
}
