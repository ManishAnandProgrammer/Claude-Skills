package com.example.app.metadata.api;

import com.example.app.common.limits.ConfiguredMax;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalTime;
import java.util.Objects;

/**
 * A school session's attendance rules (the example configuration: {@link MetadataKeys#attendance}).
 *
 * <p>Components are boxed so the compact constructor can tell a field missing from the stored JSON
 * (null) from a stored 0 or false, and give it its default. A field added later therefore reads on
 * every row written before it.</p>
 */
@Schema(description = "Attendance rules of one school session")
public record AttendanceConfig(
        @Schema(description = "Minutes after the day starts that still count as on time", example = "10")
        @PositiveOrZero @ConfiguredMax("attendance.max-grace-minutes")
        Integer graceMinutes,

        @Schema(description = "Whether a late arrival can be marked late instead of absent", example = "true")
        Boolean allowLateMark,

        @Schema(description = "When the school day starts (ISO local time)", example = "08:00")
        LocalTime dayStartsAt) {

    private static final int DEFAULT_GRACE_MINUTES = 10;
    private static final boolean DEFAULT_ALLOW_LATE_MARK = true;
    private static final LocalTime DEFAULT_DAY_STARTS_AT = LocalTime.of(8, 0);

    public AttendanceConfig {
        if (Objects.isNull(graceMinutes)) {
            graceMinutes = DEFAULT_GRACE_MINUTES;
        }
        if (Objects.isNull(allowLateMark)) {
            allowLateMark = DEFAULT_ALLOW_LATE_MARK;
        }
        if (Objects.isNull(dayStartsAt)) {
            dayStartsAt = DEFAULT_DAY_STARTS_AT;
        }
    }

    /** The rules before anyone changed them. */
    public static AttendanceConfig defaults() {
        return new AttendanceConfig(null, null, null);
    }
}
