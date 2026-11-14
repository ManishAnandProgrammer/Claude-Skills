package com.example.app.metadata.repository;

import com.example.app.metadata.domain.ConfigurationMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ConfigurationMetadataRepository extends JpaRepository<ConfigurationMetadata, UUID> {

    Optional<ConfigurationMetadata> findByKey(String key);
}
