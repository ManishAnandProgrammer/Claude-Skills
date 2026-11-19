package com.example.app.user.repository;

import com.example.app.user.domain.AccessRole;
import com.example.app.user.repository.custom.CustomAccessRoleRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface AccessRoleRepository extends JpaRepository<AccessRole, UUID>, CustomAccessRoleRepository {

    /** The role with its permissions, in one statement. */
    @EntityGraph(attributePaths = "permissionLinks")
    Optional<AccessRole> findWithPermissionsById(UUID id);

    /** The copy of a built-in role (uq_roles_built_in): signup's {@code USER}. Written out: "In" is a keyword. */
    @Query("select r from AccessRole r where r.builtIn = :builtIn")
    Optional<AccessRole> findBuiltIn(String builtIn);
}
