package com.example.app.user.service;

import com.example.app.common.ratelimit.RateLimit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.rate-limit.sign-in")
record SignInRateLimitProperties(@Valid @NotNull RateLimit loginPerIp,
                                 @Valid @NotNull RateLimit loginPerAccount,
                                 @Valid @NotNull RateLimit signupPerIp,
                                 @Valid @NotNull RateLimit refreshPerIp,
                                 @Valid @NotNull RateLimit logoutPerIp) {
}
