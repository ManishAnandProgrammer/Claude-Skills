package com.example.app.todo.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TodoTest {

    @Test
    @DisplayName("A todo moves only to a list of its own owner; another owner's list is an impossible state")
    void movesOnlyWithinItsOwnersLists() {
        UUID owner = UUID.randomUUID();
        Todo todo = Todo.builder().ownerId(owner).todoList(TodoList.builder().ownerId(owner).name("A").build()).build();
        TodoList sameOwner = TodoList.builder().ownerId(owner).name("B").build();
        TodoList otherOwner = TodoList.builder().ownerId(UUID.randomUUID()).name("C").build();

        todo.moveTo(sameOwner);

        assertThat(todo.getTodoList()).isSameAs(sameOwner);
        assertThatThrownBy(() -> todo.moveTo(otherOwner)).isInstanceOf(IllegalStateException.class);
        assertThat(todo.getTodoList()).isSameAs(sameOwner);
    }

    @Test
    @DisplayName("DONE sets completedAt, OPEN clears it, and the status it already has changes nothing")
    void changeStatusKeepsCompletedAtInStep() {
        Todo todo = Todo.builder().ownerId(UUID.randomUUID()).title("Dishes").build();
        Instant done = Instant.parse("2026-11-13T10:00:00Z");

        todo.changeStatus(TodoStatus.DONE, done);
        assertThat(todo.getStatus()).isEqualTo(TodoStatus.DONE);
        assertThat(todo.getCompletedAt()).isEqualTo(done);

        todo.changeStatus(TodoStatus.DONE, done.plusSeconds(60));
        assertThat(todo.getCompletedAt()).isEqualTo(done);

        todo.changeStatus(TodoStatus.OPEN, done.plusSeconds(120));
        assertThat(todo.getStatus()).isEqualTo(TodoStatus.OPEN);
        assertThat(todo.getCompletedAt()).isNull();
    }
}
