package com.example.app.todo.repository;

import com.example.app.todo.domain.TimeEntry;
import com.example.app.todo.repository.custom.CustomTimeEntryRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TimeEntryRepository extends JpaRepository<TimeEntry, UUID>, CustomTimeEntryRepository {

    Optional<TimeEntry> findByIdAndTodoIdAndOwnerId(UUID id, UUID todoId, UUID ownerId);
}
