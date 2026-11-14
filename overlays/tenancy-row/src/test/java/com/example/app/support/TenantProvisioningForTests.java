package com.example.app.support;

import org.springframework.boot.test.context.TestComponent;

/**
 * The test base's way to provision approved tenants now. In the row mode there is nothing left to run:
 * an approved tenant is provisioned right after the approving transaction commits
 * ({@code TenantProvisioning}), before the call that approved it returns.
 */
@TestComponent
class TenantProvisioningForTests {

    void provisionNow() {
        // Already done by the approval's own commit.
    }
}
