package com.example.app.planning.repository;

import com.example.app.planning.domain.Plan;
import com.example.app.planning.repository.custom.CustomPlanRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface PlanRepository extends JpaRepository<Plan, UUID>, CustomPlanRepository {

    Optional<Plan> findByIdAndOwnerId(UUID id, UUID ownerId);

    /**
     * Serialises the owner's plan writers until the transaction ends: two concurrent inserts that
     * overlap each wait in the exclusion constraint for the other's uncommitted row, a deadlock (503)
     * instead of one 409. A transaction-scoped advisory lock with a 64-bit key of the owner (DATA-29),
     * since the owner's row is another module's.
     */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(hashtextextended('plans:' || cast(:ownerId as text), 0))) owner_lock",
            nativeQuery = true)
    Integer lockPlansOfOwner(UUID ownerId);
}
