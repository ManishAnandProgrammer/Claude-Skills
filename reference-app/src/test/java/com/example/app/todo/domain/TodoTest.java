package com.example.app.todo.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
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

    @Test
    @DisplayName("A todo starts with no tags, takes its owner's tags as a whole, and another owner's tag is an impossible state")
    void replaceTagsTakesOnlyItsOwnersTagsAsAWhole() {
        UUID owner = UUID.randomUUID();
        Todo todo = Todo.builder().ownerId(owner).title("Tagged").build();
        Tag first = tag(owner, "First");
        Tag second = tag(owner, "Second");
        Tag third = tag(owner, "Third");
        Tag foreign = tag(UUID.randomUUID(), "Foreign");
        assertThat(todo.tagIds()).isEmpty();

        todo.replaceTags(List.of(first, second));
        todo.replaceTags(List.of(second, third));

        assertThat(todo.tagIds()).containsExactlyInAnyOrder(second.getId(), third.getId());
        assertThatThrownBy(() -> todo.replaceTags(List.of(first, foreign))).isInstanceOf(IllegalStateException.class);
        assertThat(todo.tagIds()).containsExactlyInAnyOrder(second.getId(), third.getId());
        todo.replaceTags(List.of());
        assertThat(todo.tagIds()).isEmpty();
    }

    private static Tag tag(UUID owner, String name) {
        return Tag.builder().id(UUID.randomUUID()).ownerId(owner).name(name).build();
    }
}
