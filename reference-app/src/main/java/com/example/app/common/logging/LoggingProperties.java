package com.example.app.common.logging;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.logging")
public record LoggingProperties(@NotNull ClientIpMode clientIp, @Valid @NotNull RequestCompletion requestCompletion) {

    public record RequestCompletion(boolean enabled, @NotNull Duration slowThreshold) {
    }
}
