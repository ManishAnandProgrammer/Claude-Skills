package com.example.app.support;

import com.example.app.tenant.service.TenantProvisioningJob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;

/** The test base's way to provision approved tenants now: the job's run, instead of its schedule. */
@TestComponent
class TenantProvisioningForTests {

    @Autowired
    private TenantProvisioningJob job;

    void provisionNow() {
        job.provisionPending();
    }
}
