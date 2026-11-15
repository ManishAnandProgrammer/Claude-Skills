package com.example.app.user.repository;

import com.example.app.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
// session-version:begin
// tenancy:begin
// tenancy:else
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
// tenancy:end
// session-version:end

import java.util.Optional;
import java.util.UUID;

/**
 * Emails are stored normalised ({@code ck_users_email_normalized}): look them up with
 * {@code User.normalizeEmail}, so a lookup matches the stored form and uses {@code uq_users_email}.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
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
