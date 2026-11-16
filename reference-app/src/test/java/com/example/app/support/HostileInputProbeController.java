package com.example.app.support;

import com.example.app.common.limits.ConfiguredMax;
import com.example.app.common.limits.ConfiguredMaxSize;
import com.example.app.common.limits.EarliestDate;
import com.example.app.common.limits.LatestDate;
import com.example.app.common.limits.MaxLength;
import com.example.app.common.pagination.Cursor;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A test-only endpoint that takes one value of every type through each channel a client can send one:
 * a JSON body, query parameters, and the keys of a cursor. The application's own binding, validation and
 * error handling answer it, so StrictJsonInputIntegrationTest's matrix holds for whatever endpoints a project
 * declares. Found by component scanning, as any controller under the root package; it answers nothing but
 * 200 or the refusal.
 */
@RestController
@RequestMapping("/api/{version}/hostile-input-probes")
class HostileInputProbeController {

    static final String SORT = "PROBE";

    enum Level {
        LOW,
        HIGH
    }

    record ProbeBody(
            @NotBlank @MaxLength("search-query") String text,
            @NotNull @EarliestDate("probe.day") @LatestDate("probe.day") LocalDate day,
            @NotNull Instant moment,
            @NotNull UUID id,
            @NotNull Level level,
            @NotNull @Min(0) @ConfiguredMax("cursor-page.max-size") Integer count,
            @NotNull Boolean flag,
            @NotNull @ConfiguredMaxSize("cursor-page.max-size") List<@NotNull UUID> ids) {
    }

    /** The same values as query parameters: a filter, bound through setters. */
    @Getter
    @Setter
    static class ProbeQuery {

        @NotBlank
        @MaxLength("search-query")
        private String text;

        @NotNull
        @EarliestDate("probe.day")
        @LatestDate("probe.day")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate day;

        @NotNull
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private Instant moment;

        @NotNull
        private UUID id;

        @NotNull
        private Level level;

        @NotNull
        @Min(0)
        @ConfiguredMax("cursor-page.max-size")
        private Integer count;

        @NotNull
        private Boolean flag;

        @NotNull
        @ConfiguredMaxSize("cursor-page.max-size")
        private List<@NotNull UUID> ids;
    }

    @PostMapping(version = "v1+")
    @ApiResponse(responseCode = "200", description = "Every value read")
    @ApiResponse(responseCode = "400", description = "REQUEST_UNREADABLE, VALIDATION_FAILED")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED")
    void body(@Valid @RequestBody ProbeBody body) {
    }

    @GetMapping(version = "v1+")
    @ApiResponse(responseCode = "200", description = "Every value read")
    @ApiResponse(responseCode = "400", description = "REQUEST_PARAMETER_INVALID, VALIDATION_FAILED")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED")
    void query(@Valid @ParameterObject ProbeQuery query) {
    }

    /** A cursor of sort {@link #SORT} with one key per type a cursor carries, read as a list reads it. */
    @GetMapping(path = "/cursor", version = "v1+")
    @ApiResponse(responseCode = "200", description = "Every key read")
    @ApiResponse(responseCode = "400", description = "REQUEST_PARAMETER_INVALID")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED")
    void cursor(@RequestParam String cursor) {
        Cursor read = Cursor.read(cursor, SORT, 5);
        read.text(0);
        read.date(1);
        read.instant(2);
        read.uuid(3);
        read.number(4);
    }
}
