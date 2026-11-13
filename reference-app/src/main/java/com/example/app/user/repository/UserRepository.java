package com.example.app.user.repository;

import com.example.app.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Emails are stored normalised: look them up with {@code User.normalizeEmail}, so the unique index is used. */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
