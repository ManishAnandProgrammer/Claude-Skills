package com.example.app.tenant.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.pagination.Paging;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.common.wrapper.PaginationWrapper;
import com.example.app.tenant.api.TenantApproved;
import com.example.app.tenant.api.TenantChangeSource;
import com.example.app.tenant.api.TenantCreated;
// session-version:begin
import com.example.app.tenant.api.TenantDeactivated;
// session-version:end
import com.example.app.tenant.api.TenantProvisioned;
import com.example.app.tenant.api.TenantStatus;
import com.example.app.tenant.api.TenantView;
import com.example.app.tenant.api.Tenants;
import com.example.app.tenant.domain.Tenant;
import com.example.app.tenant.domain.TenantStatusChange;
import com.example.app.tenant.dto.request.TenantCreateRequest;
import com.example.app.tenant.dto.request.TenantFilter;
import com.example.app.tenant.dto.request.TenantStatusRequest;
import com.example.app.tenant.dto.response.TenantResponse;
import com.example.app.tenant.mapper.TenantMapper;
import com.example.app.tenant.repository.TenantRepository;
import com.example.app.tenant.repository.TenantStatusChangeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TenantService implements Tenants {

    private static final String STATUS_EXTENSION = "tenantStatus";

    private final TenantRepository repository;
    private final TenantStatusChangeRepository statusChanges;
    private final TenantMapper mapper;
    private final Paging paging;
    private final ApplicationEventPublisher events;

    @Transactional
    public UUID create(TenantCreateRequest request) {
        Tenant tenant = createPending(request.name(), request.ownerEmail(), TenantChangeSource.STAFF);
        events.publishEvent(new TenantApproved(tenant.getId()));
        return tenant.getId();
    }

    @Override
    @Transactional
    public UUID register(String name, String ownerEmail) {
        return createPending(name, ownerEmail, TenantChangeSource.REGISTRATION).getId();
    }

    public PaginationWrapper<TenantResponse> findByFilter(TenantFilter filter) {
        return PaginationWrapper.fromPage(repository.findByFilter(filter, paging.offset(filter)));
    }

    public ObjectWrapper<TenantResponse> findById(UUID id) {
        return ObjectWrapper.withObject(mapper.toResponse(getTenant(id)));
    }

    @Override
    public List<TenantView> findAllById(Collection<UUID> ids) {
        return repository.findAllById(ids).stream().map(mapper::toView).toList();
    }

    @Override
    @Transactional
    public void approve(UUID tenantId) {
        Tenant tenant = getTenant(tenantId);
        if (tenant.getStatus() != TenantStatus.PENDING) {
            throw AppException.of(ErrorCode.TENANT_STATUS_CHANGE_INVALID, tenant.getStatus(), TenantStatus.ACTIVE);
        }
        events.publishEvent(new TenantApproved(tenantId));
    }

    /** A PENDING tenant becomes ACTIVE only through provisioning, so never without its tables. */
    @Transactional
    public ObjectWrapper<TenantResponse> changeStatus(UUID id, TenantStatusRequest request) {
        Tenant tenant = getTenant(id);
        tenant.checkVersion(request.version());
        if (tenant.getStatus() == TenantStatus.PENDING && request.status() == TenantStatus.ACTIVE) {
            throw AppException.of(ErrorCode.TENANT_STATUS_CHANGE_INVALID, tenant.getStatus(), request.status());
        }
        change(tenant, request.status(), TenantChangeSource.STAFF);
        repository.flush();
        return ObjectWrapper.withObject(mapper.toResponse(tenant));
    }

    /** A tenant staff rejected meanwhile keeps its status. */
    @Transactional
    public void activateProvisioned(UUID tenantId) {
        Tenant tenant = getTenant(tenantId);
        if (tenant.getStatus() == TenantStatus.PENDING) {
            events.publishEvent(new TenantProvisioned(tenantId));
            change(tenant, TenantStatus.ACTIVE, TenantChangeSource.PROVISIONING);
        }
    }

    /** A refusal leaves the caller's transaction committable: a refresh commits its own writes even when it refuses. */
    @Override
    @Transactional(readOnly = true, noRollbackFor = AppException.class)
    public void requireActive(UUID tenantId) {
        Optional<Tenant> tenant = repository.findById(tenantId);
        if (tenant.isEmpty()) {
            throw AppException.of(ErrorCode.TENANT_NOT_ACTIVE);
        }
        if (!tenant.get().isActive()) {
            throw AppException.of(ErrorCode.TENANT_NOT_ACTIVE).withExtension(STATUS_EXTENSION, tenant.get().getStatus());
        }
    }

    @Override
    public List<UUID> activeIds() {
        return repository.findIdsByStatus(TenantStatus.ACTIVE);
    }

    @Override
    public List<UUID> idsHoldingData() {
        return repository.findIdsByStatusIn(List.of(TenantStatus.ACTIVE, TenantStatus.SUSPENDED, TenantStatus.CLOSED));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForMemberChange(UUID tenantId) {
        repository.findForUpdateById(tenantId).orElseThrow(AppException.supply(ErrorCode.TENANT_NOT_FOUND, tenantId));
    }

    public Tenant getTenant(UUID id) {
        return repository.findById(id).orElseThrow(AppException.supply(ErrorCode.TENANT_NOT_FOUND, id));
    }

    private Tenant createPending(String name, String ownerEmail, TenantChangeSource source) {
        Tenant tenant = repository.saveAndFlush(Tenant.create(name));
        statusChanges.save(TenantStatusChange.of(tenant, null, source));
        events.publishEvent(new TenantCreated(tenant.getId(), ownerEmail));
        return tenant;
    }

    private void change(Tenant tenant, TenantStatus target, TenantChangeSource source) {
        TenantStatus previous = tenant.getStatus();
        tenant.changeStatus(target);
        statusChanges.save(TenantStatusChange.of(tenant, previous, source));
        // session-version:begin
        if (previous == TenantStatus.ACTIVE && target != TenantStatus.ACTIVE) {
            events.publishEvent(new TenantDeactivated(tenant.getId()));
        }
        // session-version:end
    }
}
