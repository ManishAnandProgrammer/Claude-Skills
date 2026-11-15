package com.example.app.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JsonPointersTest {

    @Test
    @DisplayName("A property path becomes a pointer: fields, list indexes and map keys are one token each")
    void propertyPathsBecomePointers() {
        assertThat(JsonPointers.fromPropertyPath("email")).isEqualTo("#/email");
        assertThat(JsonPointers.fromPropertyPath("items[0].name")).isEqualTo("#/items/0/name");
        assertThat(JsonPointers.fromPropertyPath("tagIds[12]")).isEqualTo("#/tagIds/12");
        assertThat(JsonPointers.fromPropertyPath("address.city")).isEqualTo("#/address/city");
        assertThat(JsonPointers.fromPropertyPath("labels[colour].value")).isEqualTo("#/labels/colour/value");
        assertThat(JsonPointers.fromPropertyPath("")).isEqualTo("#");
    }

    @Test
    @DisplayName("An element of a set has no position: the pointer names the set")
    void setElementsPointToTheSet() {
        assertThat(JsonPointers.fromPropertyPath("tagIds[]")).isEqualTo("#/tagIds");
    }

    @Test
    @DisplayName("A key holding / or ~ is escaped (RFC 6901): ~ as ~0 first, then / as ~1")
    void slashAndTildeAreEscaped() {
        assertThat(JsonPointers.fromPropertyPath("labels[a/b]")).isEqualTo("#/labels/a~1b");
        assertThat(JsonPointers.fromPropertyPath("labels[x~y]")).isEqualTo("#/labels/x~0y");
        assertThat(JsonPointers.of(List.of("~1"))).isEqualTo("#/~01");
    }
}
