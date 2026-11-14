package com.example.app.planning.repository;

import com.example.app.planning.domain.Plan;
import com.example.app.planning.repository.custom.CustomPlanRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PlanRepository extends JpaRepository<Plan, UUID>, CustomPlanRepository {

    Optional<Plan> findByIdAndOwnerId(UUID id, UUID ownerId);
}
