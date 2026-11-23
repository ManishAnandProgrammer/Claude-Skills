package com.example.app.user.repository;

import com.example.app.user.domain.User;
// roles:begin
// roles:else
// tenancy:begin
// tenancy:else
import com.example.app.user.repository.custom.CustomUserRepository;
// tenancy:end
// roles:end
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
// session-version:begin
// tenancy:begin
// tenancy:else
import org.springframework.data.jpa.repository.Modifying;
// tenancy:end
// session-version:end
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Emails are stored normalised ({@code ck_users_email_normalized}): look one up by {@code Emails.normalize}'s result. */
// roles:begin
// public interface UserRepository extends JpaRepository<User, UUID> {
// roles:else
// tenancy:begin
// public interface UserRepository extends JpaRepository<User, UUID> {
// tenancy:else
public interface UserRepository extends JpaRepository<User, UUID>, CustomUserRepository {
// tenancy:end
// roles:end

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select u from User u where u.id = :userId")
    Optional<User> findForTokenIssue(UUID userId);

    /** In id order: two changes locking the same accounts queue instead of deadlocking. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id in :userIds order by u.id")
    List<User> findAllForUpdate(Collection<UUID> userIds);
    // session-version:begin
    // tenancy:begin
    // tenancy:else

    @Query("select u.sessionVersion from User u where u.id = :userId")
    Optional<Integer> findSessionVersion(UUID userId);

    /**
     * A machine-written stamp (house-review DATA-24's exception): the number alone, neither {@code version} nor
     * {@code updated_at}. No clear: the entity maps the column read-only, so the caller's entities stay attached.
     */
    @Modifying(flushAutomatically = true)
    @Query("update User u set u.sessionVersion = u.sessionVersion + 1 where u.id = :userId")
    int bumpSessionVersion(UUID userId);
    // tenancy:end
    // session-version:end
}
