package com.example.app.tenant.row;

import com.example.app.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** The row-mode tests' tenant entity, over the test changelog's {@code tenancy_probe}. */
@Entity
@Table(name = "tenancy_probe")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TenancyProbe extends TenantOwnedEntity {

    @Setter
    @Column(nullable = false)
    private String label;
}
