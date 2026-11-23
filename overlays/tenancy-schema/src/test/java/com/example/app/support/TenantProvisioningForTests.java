package com.example.app.support;

import com.example.app.tenant.service.TenantProvisioningJob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;

@TestComponent
class TenantProvisioningForTests {

    @Autowired
    private TenantProvisioningJob job;

    void provisionNow() {
        job.provisionPending();
    }
}
