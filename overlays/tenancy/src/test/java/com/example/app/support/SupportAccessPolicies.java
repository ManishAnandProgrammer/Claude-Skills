package com.example.app.support;

import com.example.app.tenant.api.SupportAccessPolicy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@TestConfiguration(proxyBeanMethods = false)
public class SupportAccessPolicies {

    public static final Set<UUID> WITHOUT_CONSENT = ConcurrentHashMap.newKeySet();

    @Bean
    @Primary
    SupportAccessPolicy consentRequiringSupportAccess() {
        return request -> !WITHOUT_CONSENT.contains(request.tenantId());
    }
}
