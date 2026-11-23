package com.example.app.metadata.domain;

// tenancy-row:begin
// import com.example.app.common.persistence.TenantOwnedEntity;
// tenancy-row:else
import com.example.app.common.persistence.AuditableEntity;
// tenancy-row:end
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** Mapped for its creation only: every increment is one UPDATE in {@code CounterRepository}. */
@Entity
@Table(name = "counters")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// tenancy-row:begin
// public class Counter extends TenantOwnedEntity {
// tenancy-row:else
public class Counter extends AuditableEntity {
// tenancy-row:end

    @Column(nullable = false, updatable = false)
    private String key;

    @Column(name = "last_value", nullable = false)
    private long lastValue;

    public static Counter startingAfter(String key, long lastValue) {
        return Counter.builder()
                .key(key)
                .lastValue(lastValue)
                .build();
    }
}
