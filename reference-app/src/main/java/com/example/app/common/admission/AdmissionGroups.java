package com.example.app.common.admission;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * {@code app.admission.groups.*}: the requests that take their permit from a bulkhead of their own
 * instead of the {@code api} group's, so a burst of one kind (CPU-bound sign-ins) can't use up the
 * permits every other request needs.
 *
 * @param paths        group name (a {@code resilience4j.bulkhead.instances} entry) to the path patterns it
 *                     admits; a wildcard version segment admits every API version (see limits.yml)
 * @param warnInterval at most one WARN per group per interval while refusing: under overload a line per
 *                     refusal would itself be load
 */
@Validated
@ConfigurationProperties(prefix = "app.admission.groups")
record AdmissionGroups(@NotNull Map<String, @NotEmpty List<String>> paths, @NotNull Duration warnInterval) {
}
