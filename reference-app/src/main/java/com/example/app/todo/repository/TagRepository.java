package com.example.app.todo.repository;

import com.example.app.todo.domain.Tag;
import com.example.app.todo.repository.custom.CustomTagRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TagRepository extends JpaRepository<Tag, UUID>, CustomTagRepository {

    Optional<Tag> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<Tag> findByIdInAndOwnerId(Collection<UUID> ids, UUID ownerId);

    /**
     * {@code FOR NO KEY UPDATE}: another merge or a delete of the target waits, while a link's foreign key check
     * ({@code FOR KEY SHARE}) goes through, so todos may still be tagged with it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Tag> findForUpdateByIdAndOwnerId(UUID id, UUID ownerId);

    /**
     * {@code FOR UPDATE}, the key lock JPA can't ask for: a todo tagged with the source meanwhile waits at its
     * link's foreign key check until the merge has deleted it, then fails on the key (404 {@code TAG_DELETED_MEANWHILE}),
     * instead of adding a link the delete would take away unmoved.
     */
    // tenancy-row:begin
    // @Query(nativeQuery = true, value = """
    //         select * from tags
    //          where tenant_id = :tenantId and id = :id and owner_id = :ownerId
    //            for update""")
    // Optional<Tag> findForKeyUpdateByIdAndOwnerId(UUID tenantId, UUID id, UUID ownerId);
    // tenancy-row:else
    @Query(nativeQuery = true, value = """
            select * from tags
             where id = :id and owner_id = :ownerId
               for update""")
    Optional<Tag> findForKeyUpdateByIdAndOwnerId(UUID id, UUID ownerId);
    // tenancy-row:end
}
