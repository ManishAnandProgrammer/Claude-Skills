package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.CurrentUser;
import com.example.app.common.security.StaffAccounts;
import com.example.app.common.wrapper.ObjectWrapper;
// session-version:begin
import com.example.app.tenant.api.PlatformAudit;
// session-version:end
import com.example.app.user.domain.PlatformUser;
import com.example.app.user.dto.response.PlatformStaffResponse;
import com.example.app.user.mapper.PlatformStaffMapper;
import com.example.app.user.repository.PlatformUserRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Transactional(readOnly = true)
public class PlatformStaffService implements StaffAccounts {

    private final PlatformUserRepository repository;
    private final PlatformStaffMapper mapper;
    private final CurrentUser currentUser;
    private final Clock clock;
    // session-version:begin
    private final PlatformAudit audit;
    private final MembershipService memberships;
    private final ImpersonationProperties impersonation;
    // session-version:end

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean isActive(UUID staffId) {
        return repository.findById(staffId).filter(PlatformUser::isActive).isPresent();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    Optional<PlatformUser> findByEmail(String email) {
        return repository.findByEmail(email);
    }

    /**
     * The caller's row is read under the lock with the target's, whatever their token says: of two staff members
     * deactivating each other at once, the second finds itself deactivated.
     */
    @Transactional
    public ObjectWrapper<PlatformStaffResponse> deactivate(UUID staffId) {
        UUID callerId = currentUser.id();
        if (staffId.equals(callerId)) {
            throw AppException.of(ErrorCode.PLATFORM_STAFF_OWN_DEACTIVATION);
        }
        List<PlatformUser> locked = repository.findAllForUpdate(List.of(callerId, staffId));
        if (locked.stream().noneMatch(staff -> staff.getId().equals(callerId) && staff.isActive())) {
            throw AppException.of(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        PlatformUser target = locked.stream().filter(staff -> staff.getId().equals(staffId)).findFirst()
                .orElseThrow(AppException.supply(ErrorCode.PLATFORM_STAFF_NOT_FOUND, staffId));
        if (target.isActive()) {
            Instant now = clock.instant();
            target.deactivate(now);
            repository.flush();
            // session-version:begin
            memberships.bumpSessionVersionsOf(audit.impersonatedBy(staffId, now.minus(impersonation.tokenTtl())));
            // session-version:end
            log.info("Staff member deactivated: platformUserId={}, deactivatedBy={}", staffId, callerId);
        }
        return ObjectWrapper.withObject(mapper.toResponse(target));
    }
    // session-version:begin

    /**
     * Share-locked, so an impersonation either commits before a deactivation looks for whom its staff member acted
     * as, or waits for the deactivation and is refused.
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = AppException.class)
    void requireActiveForImpersonation(UUID staffId) {
        repository.findForImpersonation(staffId).filter(PlatformUser::isActive)
                .orElseThrow(AppException.supply(ErrorCode.AUTHENTICATION_REQUIRED));
    }
    // session-version:end
}
