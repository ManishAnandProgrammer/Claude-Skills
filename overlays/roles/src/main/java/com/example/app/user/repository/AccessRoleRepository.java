package com.example.app.user.repository;

import com.example.app.user.domain.AccessRole;
import com.example.app.user.repository.custom.CustomAccessRoleRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface AccessRoleRepository extends JpaRepository<AccessRole, UUID>, CustomAccessRoleRepository {

    @EntityGraph(attributePaths = "permissionLinks")
    Optional<AccessRole> findWithPermissionsById(UUID id);

    /** Written out: in a derived query's name, the "In" of BuiltIn is a keyword. */
    @Query("select r from AccessRole r where r.builtIn = :builtIn")
    Optional<AccessRole> findBuiltIn(String builtIn);
}
