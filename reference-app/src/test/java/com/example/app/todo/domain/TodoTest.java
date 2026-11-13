package com.example.app.todo.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TodoTest {

    @Test
    @DisplayName("A todo moves only to a list of its own owner")
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
}
