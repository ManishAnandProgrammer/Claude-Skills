package com.example.app.metadata.api;

import java.util.List;
import java.util.Optional;
// example:begin
import java.util.Objects;
import java.util.UUID;
// example:end

/**
 * Every configuration the application keeps. A key not declared here can't be read or written.
 *
 * <ul>
 *   <li>A <b>fixed</b> key is a constant: one value for the whole application (or tenant). The admin
 *       API ({@code /metadata/{key}}) reaches fixed keys only, through {@link #byName}.</li>
 *   <li>A <b>per-context</b> key is a factory method, its name a prefix plus the context's id in its
 *       canonical form ({@code FEE_RULES_<sessionId>}). Only the module that owns the context
 *       writes it, through its own typed endpoint ({@code PUT /school-sessions/{id}/fee-rules}),
 *       which checks the context's rules first; so {@link #byName} never resolves one.</li>
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

    /** The fixed key of that name, if any. */
    static Optional<MetadataKey<?>> byName(String name) {
        List<MetadataKey<?>> fixedKeys = List.of(
                // Per-context keys are never listed: the context's owner writes them through its own endpoint.
                // example:begin
                ATTENDANCE_DEFAULTS
                // example:end
        );
        for (MetadataKey<?> key : fixedKeys) {
            if (key.name().equals(name)) {
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }
}
