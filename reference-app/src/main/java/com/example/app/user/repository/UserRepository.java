package com.example.app.user.repository;

import com.example.app.user.domain.User;
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

/**
 * Emails are stored normalised ({@code ck_users_email_normalized}): look them up with
 * {@code Emails.normalize}, so a lookup matches the stored form and uses {@code uq_users_email}.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * The account a token is about to be issued for, read with a share lock (FOR SHARE) held until the issue
     * commits: every change of the account's access (a suspension, a password change) updates this row
     * first, so an issue and such a change never interleave. The issue either commits before the change
     * starts (and the change revokes what it issued) or reads the changed row (and refuses).
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select u from User u where u.id = :userId")
    Optional<User> findForTokenIssue(UUID userId);

    /**
     * The accounts with these ids, locked for update in id order (two changes locking the same accounts
     * queue instead of deadlocking): an account status change locks the caller and the target together.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id in :userIds order by u.id")
    List<User> findAllForUpdate(Collection<UUID> userIds);
    // session-version:begin
    // tenancy:begin
    // tenancy:else

    /** The account's session version, by primary key; empty when the account doesn't exist. */
    @Query("select u.sessionVersion from User u where u.id = :userId")
    Optional<Integer> findSessionVersion(UUID userId);

    /**
     * Raises the account's session version, in the transaction of the access change that needs it. A
     * machine-written stamp (DATA-24's exception): the number alone, neither {@code version} nor
     * {@code updated_at}. No clear: the entity maps the column read-only, so a copy loaded earlier can't
     * write its older number back, and the caller's entities stay attached.
     */
    @Modifying(flushAutomatically = true)
    @Query("update User u set u.sessionVersion = u.sessionVersion + 1 where u.id = :userId")
    int bumpSessionVersion(UUID userId);
    // tenancy:end
    // session-version:end
}
