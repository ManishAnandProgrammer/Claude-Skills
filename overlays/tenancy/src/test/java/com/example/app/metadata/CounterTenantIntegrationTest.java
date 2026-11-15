package com.example.app.metadata;

import com.example.app.common.tenancy.TenantScope;
import com.example.app.metadata.api.Counters;
import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Display numbers count per tenant (house-review TEN-18): a counter is a row of the tenant's own data,
 * never a sequence the tenants share, so a tenant's numbers move with its data and never reveal how much
 * another tenant numbers.
 */
class CounterTenantIntegrationTest extends IntegrationTest {

    @Autowired
    private Counters counters;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private void createIn(TestTenant tenant, String name) {
        TenantScope.runAs(tenant.id(), () -> counters.create(name, 0));
    }

    /** A business transaction in the tenant that takes a number and commits. */
    private long nextIn(TestTenant tenant, String name) {
        return TenantScope.callAs(tenant.id(), () -> new TransactionTemplate(transactionManager).execute(status -> counters.next(name)));
    }

    @Test
    @DisplayName("A counter of one name counts on its own in each tenant: no number is shared across tenants")
    void eachTenantCountsItsOwnNumbers() throws Exception {
        TestTenant other = newTenant();
        String invoices = Counters.perContext("TEST_INVOICE", UUID.randomUUID());
        createIn(testTenant(), invoices);
        createIn(other, invoices);

        assertThat(List.of(nextIn(testTenant(), invoices), nextIn(testTenant(), invoices))).containsExactly(1L, 2L);
        assertThat(nextIn(other, invoices)).isEqualTo(1L);
        assertThat(nextIn(testTenant(), invoices)).isEqualTo(3L);
    }
}
