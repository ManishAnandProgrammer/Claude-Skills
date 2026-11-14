package com.example.app.todo.repository;

import com.example.app.todo.domain.Tag;
import com.example.app.todo.repository.custom.CustomTagRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TagRepository extends JpaRepository<Tag, UUID>, CustomTagRepository {

    Optional<Tag> findByIdAndOwnerId(UUID id, UUID ownerId);

    /**
     * The owner's tags among {@code ids}, locked {@code FOR KEY SHARE} in id order (no deadlock between
     * two writers) until the transaction ends: a tag put on a todo can't race the tag's delete (the
     * delete waits, then its cascade removes the new rows too), while a rename, which changes no key,
     * doesn't wait. Native, because JPA's lock modes stop at {@code FOR SHARE}, which blocks a rename.
     */
    @Query(value = """
            select * from tags
             where id in (:ids) and owner_id = :ownerId
             order by id
               for key share""",
            nativeQuery = true)
    List<Tag> findForKeyShareByIdInAndOwnerId(Collection<UUID> ids, UUID ownerId);
}
