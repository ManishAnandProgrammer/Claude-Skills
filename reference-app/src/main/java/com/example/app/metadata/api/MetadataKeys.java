package com.example.app.metadata.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Every configuration the application keeps. A key not declared here can't be read or written,
 * through the admin API or in code.
 *
 * <ul>
 *   <li>A <b>fixed</b> key is a constant: one value for the whole application (or tenant).</li>
 *   <li>A <b>per-context</b> key is a factory method, its name a prefix plus the context's id
 *       ({@code SOME_CONFIG_7}). The admin API resolves it back in {@link #byName}: one
 *       {@code if} per prefix.</li>
 * </ul>
 *
 * <p>An interface only because a module's {@code api} package holds contract types (ARCH-05);
 * nothing implements it.</p>
 */
public interface MetadataKeys {

    // example:begin
    /** The attendance rules a school starts from: the first session copies them. */
    MetadataKey<AttendanceConfig> ATTENDANCE_DEFAULTS = new MetadataKey<>("ATTENDANCE_DEFAULTS", AttendanceConfig.class);

    String ATTENDANCE_PREFIX = "ATTENDANCE_CONFIG_";

    /** One school session's attendance rules, created when the session starts. */
    static MetadataKey<AttendanceConfig> attendance(UUID sessionId) {
        return new MetadataKey<>(ATTENDANCE_PREFIX + Objects.requireNonNull(sessionId, "sessionId"), AttendanceConfig.class);
    }
    // example:end

    /** The declared key of that name, if any. */
    static Optional<MetadataKey<?>> byName(String name) {
        List<MetadataKey<?>> fixedKeys = List.of(
                // example:begin
                ATTENDANCE_DEFAULTS
                // example:end
        );
        for (MetadataKey<?> key : fixedKeys) {
            if (key.name().equals(name)) {
                return Optional.of(key);
            }
        }
        // example:begin
        Optional<UUID> sessionId = contextId(name, ATTENDANCE_PREFIX);
        if (sessionId.isPresent()) {
            return Optional.of(attendance(sessionId.get()));
        }
        // example:end
        return Optional.empty();
    }

    /**
     * The id after {@code prefix} when it is a UUID written as the factory writes it (canonical, lower
     * case), so an upper-case spelling can never name a second row for the same context.
     */
    private static Optional<UUID> contextId(String name, String prefix) {
        if (!name.startsWith(prefix)) {
            return Optional.empty();
        }
        String id = name.substring(prefix.length());
        if (!id.matches(MetadataKey.CANONICAL_UUID)) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(id));
    }
}
