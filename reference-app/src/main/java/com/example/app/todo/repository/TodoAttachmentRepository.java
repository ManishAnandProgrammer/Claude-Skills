package com.example.app.todo.repository;

import com.example.app.todo.domain.TodoAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface TodoAttachmentRepository extends JpaRepository<TodoAttachment, UUID> {

    @Query("""
            select link.mediaFileId
              from TodoAttachment link
             where link.todo.id = :todoId
             order by link.mediaFileId""")
    List<UUID> findMediaFileIdsOfTodo(UUID todoId);
}
