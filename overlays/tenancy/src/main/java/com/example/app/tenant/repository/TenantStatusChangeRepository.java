package com.example.app.tenant.repository;

import com.example.app.tenant.domain.TenantStatusChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TenantStatusChangeRepository extends JpaRepository<TenantStatusChange, UUID> {
}
