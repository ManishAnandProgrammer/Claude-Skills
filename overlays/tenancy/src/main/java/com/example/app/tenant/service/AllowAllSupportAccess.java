package com.example.app.tenant.service;

import com.example.app.tenant.api.SupportAccessPolicy;
import com.example.app.tenant.api.SupportAccessRequest;
import org.springframework.stereotype.Component;

/**
 * The default {@link SupportAccessPolicy}: platform staff may impersonate any tenant. Every
 * impersonation is still audited ({@code platform_audit}). Replace this class to require consent or
 * a ticket.
 */
@Component
class AllowAllSupportAccess implements SupportAccessPolicy {

    @Override
    public boolean allows(SupportAccessRequest request) {
        return true;
    }
}
