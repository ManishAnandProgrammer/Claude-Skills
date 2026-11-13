package com.example.app.user.service;

import com.example.app.common.ratelimit.RateLimit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Limits on the public sign-in endpoints ({@code app.rate-limit.sign-in.*}): per client IP, and per
 * account where the request names one, so neither one machine trying many accounts nor many machines
 * trying one account gets through.
 *
 * <p>Trade-off of the per-account login limit: anyone who knows an email can keep that account's
 * login answering 429 for as long as they keep trying. Accepted for now, as the price of stopping
 * distributed password guessing; a progressive delay would soften it.</p>
 */
@Validated
@ConfigurationProperties(prefix = "app.rate-limit.sign-in")
record SignInRateLimitProperties(@Valid @NotNull RateLimit loginPerIp,
                                 @Valid @NotNull RateLimit loginPerAccount,
                                 @Valid @NotNull RateLimit signupPerIp,
                                 @Valid @NotNull RateLimit refreshPerIp,
                                 @Valid @NotNull RateLimit logoutPerIp) {
}
