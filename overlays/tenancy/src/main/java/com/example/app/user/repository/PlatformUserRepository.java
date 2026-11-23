package com.example.app.user.repository;

import com.example.app.user.domain.PlatformUser;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Emails are stored normalised, as the users' are: look one up by {@code Emails.normalize}'s result. */
public interface PlatformUserRepository extends JpaRepository<PlatformUser, UUID> {

    Optional<PlatformUser> findByEmail(String email);

    /** In id order: two deactivations locking the same staff members queue instead of deadlocking. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PlatformUser s where s.id in :staffIds order by s.id")
    List<PlatformUser> findAllForUpdate(Collection<UUID> staffIds);
    // session-version:begin

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select s from PlatformUser s where s.id = :staffId")
    Optional<PlatformUser> findForImpersonation(UUID staffId);
    // session-version:end
}
