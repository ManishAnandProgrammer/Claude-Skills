package com.example.app.todo.repository;

import com.example.app.todo.domain.Tag;
import com.example.app.todo.repository.custom.CustomTagRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TagRepository extends JpaRepository<Tag, UUID>, CustomTagRepository {

    Optional<Tag> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<Tag> findByIdInAndOwnerId(Collection<UUID> ids, UUID ownerId);
}
