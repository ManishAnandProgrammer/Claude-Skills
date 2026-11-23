package com.example.app.tenant.service;

import com.example.app.tenant.api.SupportAccessPolicy;
import com.example.app.tenant.api.SupportAccessRequest;
import org.springframework.stereotype.Component;

@Component
class AllowAllSupportAccess implements SupportAccessPolicy {

    @Override
    public boolean allows(SupportAccessRequest request) {
        return true;
    }
}
