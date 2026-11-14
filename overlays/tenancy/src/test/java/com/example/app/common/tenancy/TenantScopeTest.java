package com.example.app.common.tenancy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantScopeTest {

    @Test
    @DisplayName("Outside any scope and request there is no tenant: NO_TENANT, never a guess")
    void noScopeIsNoTenant() {
        assertThat(TenantContext.current()).isEqualTo(TenantContext.NO_TENANT);
        assertThat(TenantContext.findId()).isEmpty();
        assertThatThrownBy(TenantContext::id).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("A nested scope wins inside and gives the outer tenant back, also when the work throws")
    void scopesNestAndRestore() {
        UUID outer = UUID.randomUUID();
        UUID inner = UUID.randomUUID();

        TenantScope.runAs(outer, () -> {
            assertThat(TenantContext.id()).isEqualTo(outer);
            assertThat(TenantScope.callAs(inner, TenantContext::id)).isEqualTo(inner);
            assertThatThrownBy(() -> TenantScope.runAs(inner, () -> {
                throw new IllegalStateException("fails inside");
            })).hasMessage("fails inside");
            assertThat(TenantContext.id()).isEqualTo(outer);
        });

        assertThat(TenantContext.current()).isEqualTo(TenantContext.NO_TENANT);
    }

    @Test
    @DisplayName("An async task runs as the tenant it was submitted under, and the worker keeps none afterwards")
    void taskDecoratorCarriesTheTenant() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID[] seen = new UUID[1];
        Runnable decorated = TenantScope.callAs(tenant, () -> new TenantTaskDecorator().decorate(() -> seen[0] = TenantContext.current()));

        Thread worker = Thread.ofVirtual().start(decorated);
        worker.join();

        assertThat(seen[0]).isEqualTo(tenant);
        assertThat(TenantContext.current()).isEqualTo(TenantContext.NO_TENANT);
    }

    @Test
    @DisplayName("A schema name is t_ and the id's hex digits: derived, never taken from input")
    void schemaNamesAreDerived() {
        UUID tenant = UUID.fromString("0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d");

        assertThat(TenantSchemas.of(tenant)).isEqualTo("t_0190a9c26c437cc19f2a1d1e5a6b7c8d").matches("t_[0-9a-f]{32}");
    }
}
