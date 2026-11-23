package com.example.app.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One value of the request that failed: in the body (pointer) or a parameter (parameter)")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RequestError(
        @Schema(description = "JSON Pointer (RFC 6901) to the body value; # for the body as a whole", example = "#/items/0/name")
        String pointer,

        @Schema(description = "The query or path parameter", example = "size")
        String parameter,

        @Schema(description = "What is wrong with the value", example = "must not be blank")
        String detail) {

    /** A value that couldn't be read or converted: the converter's own message names Java classes. */
    public static final String INVALID_VALUE = "invalid value";
    public static final String UNKNOWN_FIELD = "unknown field";

    public static RequestError atPointer(String pointer, String detail) {
        return new RequestError(pointer, null, detail);
    }

    public static RequestError atParameter(String parameter, String detail) {
        return new RequestError(null, parameter, detail);
    }
}
