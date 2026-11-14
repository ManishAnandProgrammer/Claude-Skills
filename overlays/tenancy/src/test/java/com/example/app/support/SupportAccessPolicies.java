package com.example.app.support;

import com.example.app.tenant.api.SupportAccessPolicy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A strict application's support-access policy, in the shared test context: it refuses the tenants a
 * test lists in {@link #WITHOUT_CONSENT} and allows the rest, like the default.
 */
@TestConfiguration(proxyBeanMethods = false)
public class SupportAccessPolicies {

    /** Tenants whose admin gave no consent; a test adds its own fresh tenant only. */
    public static final Set<UUID> WITHOUT_CONSENT = ConcurrentHashMap.newKeySet();

    @Bean
    @Primary
    SupportAccessPolicy consentRequiringSupportAccess() {
        return request -> !WITHOUT_CONSENT.contains(request.tenantId());
    }
}
