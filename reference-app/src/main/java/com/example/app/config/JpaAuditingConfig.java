package com.example.app.config;

import com.example.app.common.security.CurrentUser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/** Audit moments come from the application's {@link Clock} (microseconds, like the columns), not the system clock. */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditor", dateTimeProviderRef = "auditingTime")
class JpaAuditingConfig {

    @Bean
    AuditorAware<UUID> auditor(CurrentUser currentUser) {
        return currentUser::findId;
    }

    @Bean
    DateTimeProvider auditingTime(Clock clock) {
        return () -> Optional.of(clock.instant());
    }
}
