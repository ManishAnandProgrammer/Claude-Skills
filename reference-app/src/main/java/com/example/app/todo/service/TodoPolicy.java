package com.example.app.todo.service;

import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.Decision;
import com.example.app.todo.domain.Todo;
import com.example.app.todo.domain.Todo_;
import org.springframework.data.jpa.domain.PredicateSpecification;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class TodoPolicy {

    public PredicateSpecification<Todo> listScope(UUID callerId) {
        return ownedBy(callerId);
    }

    public PredicateSpecification<Todo> editScope(UUID callerId) {
        return ownedBy(callerId);
    }

    public Decision read(Todo todo, UUID callerId) {
        if (todo.getOwnerId().equals(callerId) || callerId.equals(todo.getRequesterId())) {
            return Decision.allowed();
        }
        return Decision.refused(ErrorCode.TODO_NOT_FOUND, todo.getId());
    }

    /** The requester may read the todo, so a 403 tells them nothing new; anyone else gets the 404 of a missing todo. */
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
