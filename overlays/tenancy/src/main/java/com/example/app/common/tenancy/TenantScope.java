package com.example.app.common.tenancy;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs work under a given tenant: a job's loop over the tenants, an event listener seeding a new
 * tenant, an async task. The only way to switch the tenant (an ArchUnit rule keeps it so).
 *
 * <p>Wrap the call into the {@code @Transactional} method, never the reverse: the tenant is fixed when
 * the transaction's session opens, so a switch inside an open transaction is ignored by Hibernate and
 * the database alike. The previous tenant comes back in {@code finally}, so nested scopes and thread
 * pools never keep a tenant they didn't set.</p>
 */
public final class TenantScope {

    private static final ThreadLocal<UUID> SCOPED = new ThreadLocal<>();

    private TenantScope() {
    }

    public static void runAs(UUID tenantId, Runnable work) {
        callAs(tenantId, () -> {
            work.run();
            return null;
        });
    }

    public static <T> T callAs(UUID tenantId, Supplier<T> work) {
        Objects.requireNonNull(tenantId, "tenantId");
        UUID previous = SCOPED.get();
        SCOPED.set(tenantId);
        try {
            return work.get();
        } finally {
            if (Objects.isNull(previous)) {
                SCOPED.remove();
            } else {
                SCOPED.set(previous);
            }
        }
    }

    static Optional<UUID> scoped() {
        return Optional.ofNullable(SCOPED.get());
    }
}
