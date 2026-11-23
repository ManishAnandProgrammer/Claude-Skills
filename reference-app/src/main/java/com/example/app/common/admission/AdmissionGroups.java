package com.example.app.common.admission;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Validated
@ConfigurationProperties(prefix = "app.admission.groups")
record AdmissionGroups(@NotNull Map<String, @NotEmpty List<String>> paths, @NotNull @DurationMin(millis = 1) Duration warnInterval) {
}
