package com.example.app.user.domain;

import com.example.app.common.persistence.AuditableEntity;
import com.example.app.common.validation.Emails;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** An account. The email is stored normalised ({@link Emails}), so it is unique as people read it. */
@Entity
@Table(name = "users")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends AuditableEntity {

    @Column(nullable = false)
    private String email;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;
    // session-version:begin
    // tenancy:begin
    // tenancy:else

    /**
     * The number every access token of the account carries (claim {@code sv}); a token with another one is
     * refused. Read-only here: only {@code UserRepository.bumpSessionVersion} writes it, so an entity loaded
     * before a bump can never write its older copy back, and a new account starts at the column's default.
     */
    @Column(name = "session_version", insertable = false, updatable = false)
    private int sessionVersion;
    // tenancy:end
    // session-version:end

    /** A new self-registered account. The caller hashes the password. */
    public static User register(String email, String fullName, String passwordHash) {
        return User.builder()
                .email(Emails.normalize(email))
                .fullName(fullName.strip())
                .passwordHash(passwordHash)
                .role(Role.USER)
                .status(UserStatus.ACTIVE)
                .build();
    }

    /** The caller hashes the password; the service ends every session in the same transaction. */
    public void changePassword(String newPasswordHash) {
        passwordHash = newPasswordHash;
    }

    /** Suspends or reactivates; the service ends every session in the same transaction on a suspension. */
    public void changeStatus(UserStatus newStatus) {
        status = newStatus;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }
}
