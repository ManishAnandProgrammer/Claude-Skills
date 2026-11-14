package com.example.app.media.repository;

import com.example.app.media.api.UploadPurpose;
import com.example.app.media.domain.UploadPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UploadPolicyRepository extends JpaRepository<UploadPolicy, UUID> {

    Optional<UploadPolicy> findByPurpose(UploadPurpose purpose);

    List<UploadPolicy> findAllByOrderByPurpose();
}
