package com.example.app.config;

import com.example.app.common.security.CurrentUser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.UUID;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditor")
class JpaAuditingConfig {

    @Bean
    AuditorAware<UUID> auditor(CurrentUser currentUser) {
        return currentUser::findId;
    }
}
