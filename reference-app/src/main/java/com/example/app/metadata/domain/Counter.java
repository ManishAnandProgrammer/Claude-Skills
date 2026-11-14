package com.example.app.metadata.domain;

import com.example.app.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** A gapless counter. Mapped for its creation only: every increment is one UPDATE in {@code CounterRepository}. */
@Entity
@Table(name = "counters")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Counter extends AuditableEntity {

    @Column(nullable = false, updatable = false)
    private String key;

    @Column(name = "last_value", nullable = false)
    private long lastValue;

    /** A new counter; the first number it hands out is {@code lastValue + 1}. */
    public static Counter startingAfter(String key, long lastValue) {
        return Counter.builder()
                .key(key)
                .lastValue(lastValue)
                .build();
    }
}
