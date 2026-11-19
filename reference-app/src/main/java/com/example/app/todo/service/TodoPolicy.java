package com.example.app.todo.service;

import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.Decision;
import com.example.app.todo.domain.Todo;
import com.example.app.todo.domain.Todo_;
import org.springframework.data.jpa.domain.PredicateSpecification;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Who may do what with one todo, after the handler's permission ({@code TODO_*_OWN}) let the caller in: what
 * "own" means for a todo, written once, for the detail, the writes, the record's allowed actions and the list.
 * Plain ifs on the record; a rule about its state, an amount or a date goes here too, and a limit is this
 * module's setting, never a role's. Refusals name the error code; the error contract picks the status.
 *
 * <p>A todo has no state rule (a DONE todo stays editable), so its rules are about the caller alone: its owner
 * does everything, its requester reads it.</p>
 */
@Component
public class TodoPolicy {

    /** The list's scope (Pundit's Scope): the caller's own todos, a condition of the list's Criteria query. */
    public PredicateSpecification<Todo> listScope(UUID callerId) {
        return ownedBy(callerId);
    }

    /**
     * The todos {@link #edit} allows, as a condition of the statement that locks one: a write locks only a row
     * the caller may change, so nobody else's request queues behind its owner's writers (house-review DATA-29).
     */
    public PredicateSpecification<Todo> editScope(UUID callerId) {
        return ownedBy(callerId);
    }

    /** Its owner, or its requester (a second party who reads it); anyone else gets the 404 of a missing todo. */
    public Decision read(Todo todo, UUID callerId) {
        if (todo.getOwnerId().equals(callerId) || callerId.equals(todo.getRequesterId())) {
            return Decision.allowed();
        }
        return Decision.refused(ErrorCode.TODO_NOT_FOUND, todo.getId());
    }

    /**
     * Every change (its fields, status, tags, files, time entries, its delete) and reading its time entries: its
     * owner only. Its requester, who reads it and so knows it exists, gets 403 {@code TODO_NOT_YOURS}; anyone else
     * the 404 of a missing todo.
     */
    public Decision edit(Todo todo, UUID callerId) {
        if (todo.getOwnerId().equals(callerId)) {
            return Decision.allowed();
        }
        if (callerId.equals(todo.getRequesterId())) {
            return Decision.refused(ErrorCode.TODO_NOT_YOURS, todo.getId());
        }
        return Decision.refused(ErrorCode.TODO_NOT_FOUND, todo.getId());
    }

    private static PredicateSpecification<Todo> ownedBy(UUID callerId) {
        return (todo, builder) -> builder.equal(todo.get(Todo_.ownerId), callerId);
    }
}
