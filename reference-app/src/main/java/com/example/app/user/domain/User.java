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

    // roles:begin
    // roles:else
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    // roles:end
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;
    // session-version:begin
    // tenancy:begin
    // tenancy:else

    /**
     * Read-only: only {@code UserRepository.bumpSessionVersion} writes it, so an entity loaded before a bump can never
     * write its older number back.
     */
    @Column(name = "session_version", insertable = false, updatable = false)
    private int sessionVersion;
    // tenancy:end
    // session-version:end

    public static User register(String email, String fullName, String passwordHash) {
        return User.builder()
                .email(Emails.normalize(email))
                .fullName(fullName.strip())
                .passwordHash(passwordHash)
                // roles:begin
                // roles:else
                .role(Role.USER)
                // roles:end
                .status(UserStatus.ACTIVE)
                .build();
    }

    public void changePassword(String newPasswordHash) {
        passwordHash = newPasswordHash;
    }

    public void changeStatus(UserStatus newStatus) {
        status = newStatus;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }
}
