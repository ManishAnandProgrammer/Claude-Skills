package com.example.app.user.service;

import com.example.app.common.ratelimit.RateLimit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** The tenant members API's limits ({@code app.rate-limit.members.*}, config/tenancy.yml). */
@Validated
@ConfigurationProperties(prefix = "app.rate-limit.members")
record MemberRateLimitProperties(@Valid @NotNull RateLimit addPerAccount) {
}
