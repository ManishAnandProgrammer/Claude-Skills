package com.example.app.common.time;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.ZoneId;

/** @param zone a region id ({@code Europe/Berlin}), so daylight saving moves with it; an unknown one stops the start */
@Validated
@ConfigurationProperties(prefix = "app.business-calendar")
record BusinessCalendarProperties(@NotNull ZoneId zone) {
}
