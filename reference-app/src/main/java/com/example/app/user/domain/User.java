package com.example.app.user.domain;

import com.example.app.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.Locale;

/** An account. The email is stored normalised (trimmed, lower-case), so it is unique as people read it. */
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

    /** A new self-registered account. The caller hashes the password. */
    public static User register(String email, String fullName, String passwordHash) {
        return User.builder()
                .email(normalizeEmail(email))
                .fullName(fullName.strip())
                .passwordHash(passwordHash)
                .role(Role.USER)
                .build();
    }

    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
