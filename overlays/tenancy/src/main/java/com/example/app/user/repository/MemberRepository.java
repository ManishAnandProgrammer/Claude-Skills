package com.example.app.user.repository;

import com.example.app.user.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** The current tenant's members: the session's tenant picks them, as for every tenant entity. */
public interface MemberRepository extends JpaRepository<Member, UUID> {

    Optional<Member> findByUserId(UUID userId);
}
