package com.example.app.user.repository;

import com.example.app.user.domain.PlatformUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Emails are stored normalised, as the users' are: look one up by {@code Emails.normalize}'s result. */
public interface PlatformUserRepository extends JpaRepository<PlatformUser, UUID> {

    Optional<PlatformUser> findByEmail(String email);
}
