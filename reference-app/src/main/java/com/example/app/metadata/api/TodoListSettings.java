package com.example.app.metadata.api;

import com.example.app.common.limits.ConfiguredMax;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalTime;
import java.util.Objects;

/**
 * One todo list's settings (the example configuration: {@link MetadataKeys#todoListSettings}).
 *
 * <p>Components are boxed so the compact constructor can tell a field missing from the stored JSON
 * (null) from a stored 0 or false, and give it its default. A field added later therefore reads on
 * every row written before it.</p>
 */
@Schema(description = "Settings of one todo list")
public record TodoListSettings(
        @Schema(description = "Minutes before a todo is due that its reminder goes out", example = "30")
        @PositiveOrZero @ConfiguredMax("todo-list-settings.max-reminder-minutes")
        Integer reminderMinutesBefore,

        @Schema(description = "Whether done todos are archived at the end of the day", example = "false")
        Boolean archiveDoneTodos,

        @Schema(description = "When the daily digest of open todos is sent (ISO local time)", example = "09:00")
        LocalTime dailyDigestAt) {

    private static final int DEFAULT_REMINDER_MINUTES_BEFORE = 30;
    private static final boolean DEFAULT_ARCHIVE_DONE_TODOS = false;
    private static final LocalTime DEFAULT_DAILY_DIGEST_AT = LocalTime.of(9, 0);

    public TodoListSettings {
        if (Objects.isNull(reminderMinutesBefore)) {
            reminderMinutesBefore = DEFAULT_REMINDER_MINUTES_BEFORE;
        }
        if (Objects.isNull(archiveDoneTodos)) {
            archiveDoneTodos = DEFAULT_ARCHIVE_DONE_TODOS;
        }
        if (Objects.isNull(dailyDigestAt)) {
            dailyDigestAt = DEFAULT_DAILY_DIGEST_AT;
        }
    }

    /** The settings before anyone changed them. */
    public static TodoListSettings defaults() {
        return new TodoListSettings(null, null, null);
    }
}
