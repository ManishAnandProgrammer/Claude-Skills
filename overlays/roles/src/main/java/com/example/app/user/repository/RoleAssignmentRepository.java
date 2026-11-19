package com.example.app.user.repository;

import com.example.app.user.domain.RoleAssignment;
import com.example.app.user.repository.custom.CustomRoleAssignmentRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoleAssignmentRepository extends JpaRepository<RoleAssignment, UUID>, CustomRoleAssignmentRepository {

    Optional<RoleAssignment> findByUserIdAndRoleId(UUID userId, UUID roleId);

    /** A person's assignments with their roles' codes and names, in one statement (uq_user_roles_user_id_role_id). */
    @EntityGraph(attributePaths = "role")
    List<RoleAssignment> findByUserId(UUID userId);
}
