package com.example.app.tenant.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.pagination.Paging;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.common.wrapper.PaginationWrapper;
import com.example.app.tenant.api.TenantApproved;
import com.example.app.tenant.api.TenantChangeSource;
import com.example.app.tenant.api.TenantCreated;
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

/**
 * The tenant register (control plane). Staff create tenants and change their status; every change is
 * a {@code tenant_status_changes} row. A tenant never reads another tenant's row: tenant-plane code
 * reaches this service only through {@link Tenants}.
 *
 * <p>A tenant starts PENDING. {@link #approve} is the one way in: it lets the tenancy mode provision the
 * tenant ({@link TenantApproved}), and provisioning activates it once its tables exist
 * ({@link #activateProvisioned}). Staff-created tenants are approved at once; registered ones wait for
 * staff.</p>
 */
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

    /**
     * Staff's tenant: PENDING and approved at once, with its owner's membership ({@link TenantCreated},
     * same transaction: an owner without an account rolls it back). ACTIVE once provisioned.
     */
    @Transactional
    public ObjectWrapper<TenantResponse> create(TenantCreateRequest request) {
        Tenant tenant = createPending(request.name(), request.ownerEmail(), TenantChangeSource.STAFF);
        events.publishEvent(new TenantApproved(tenant.getId()));
        return ObjectWrapper.withObject(mapper.toResponse(tenant));
    }

    @Override
    @Transactional
    public UUID register(String name, String ownerEmail) {
        return createPending(name, ownerEmail, TenantChangeSource.REGISTRATION).getId();
    }

    public PaginationWrapper<TenantResponse> findByFilter(TenantFilter filter) {
        return PaginationWrapper.fromPage(repository.findByFilter(filter, paging.offset(filter, filter.getSort().toSort())));
    }

    public ObjectWrapper<TenantResponse> findById(UUID id) {
        return ObjectWrapper.withObject(mapper.toResponse(getTenant(id)));
    }

    @Override
    public List<TenantView> findAllById(Collection<UUID> ids) {
        return repository.findAllById(ids).stream().map(mapper::toView).toList();
    }

    /** Staff approving a registration: {@link #approve}, answered with the tenant (still PENDING until provisioned). */
    @Transactional
    public ObjectWrapper<TenantResponse> approveByStaff(UUID id) {
        approve(id, TenantChangeSource.STAFF);
        return ObjectWrapper.withObject(mapper.toResponse(getTenant(id)));
    }

    @Override
    @Transactional
    public void approve(UUID tenantId, TenantChangeSource source) {
        Tenant tenant = getTenant(tenantId);
        if (tenant.getStatus() != TenantStatus.PENDING) {
            throw AppException.of(ErrorCode.TENANT_STATUS_CHANGE_INVALID, tenant.getStatus(), TenantStatus.ACTIVE);
        }
        events.publishEvent(new TenantApproved(tenantId));
    }

    /**
     * Staff's changes: suspend, reactivate, close, reject. Activating a PENDING tenant is provisioning's,
     * after {@link #approve}, so a tenant without its tables can't become ACTIVE from here.
     */
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

    /**
     * Provisioning's last step, run under {@code TenantScope.runAs(tenantId, ...)} once the tenant's tables
     * exist: modules seed its defaults ({@link TenantProvisioned}) and it becomes ACTIVE, in the caller's
     * transaction. A tenant staff rejected meanwhile keeps its status.
     */
    @Transactional
    public void activateProvisioned(UUID tenantId) {
        Tenant tenant = getTenant(tenantId);
        if (tenant.getStatus() == TenantStatus.PENDING) {
            events.publishEvent(new TenantProvisioned(tenantId));
            change(tenant, TenantStatus.ACTIVE, TenantChangeSource.PROVISIONING);
        }
    }

    /** Its refusal never dooms the caller's transaction: a refresh commits its token's rotation even when it refuses. */
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
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForMemberChange(UUID tenantId) {
        repository.findForUpdateById(tenantId).orElseThrow(AppException.supply(ErrorCode.TENANT_NOT_FOUND, tenantId));
    }

    /** 404 {@code TENANT_NOT_FOUND}. */
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
    }
}
