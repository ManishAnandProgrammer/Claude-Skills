package com.example.app.user.repository;

import com.example.app.user.domain.PersonalGrant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PersonalGrantRepository extends JpaRepository<PersonalGrant, UUID> {

    Optional<PersonalGrant> findByUserIdAndPermission(UUID userId, String permission);

    List<PersonalGrant> findByUserIdOrderByPermission(UUID userId);
}
