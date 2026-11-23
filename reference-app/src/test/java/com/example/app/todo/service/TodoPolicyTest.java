package com.example.app.todo.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.Decision;
import com.example.app.todo.domain.Todo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TodoPolicyTest {

    private static final TodoPolicy POLICY = new TodoPolicy();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID REQUESTER = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();
    private static final Todo TODO = Todo.builder().id(UUID.randomUUID()).ownerId(OWNER).requesterId(REQUESTER).title("Asked").build();
    private static final Todo UNREQUESTED = Todo.builder().id(UUID.randomUUID()).ownerId(OWNER).title("Mine").build();

    enum Rule {
        READ(POLICY::read),
        EDIT(POLICY::edit);

        private final BiFunction<Todo, UUID, Decision> decide;

        Rule(BiFunction<Todo, UUID, Decision> decide) {
            this.decide = decide;
        }
    }

    static Stream<Arguments> decisions() {
        return Stream.of(
                Arguments.of(Rule.READ, TODO, OWNER, null),
                Arguments.of(Rule.READ, TODO, REQUESTER, null),
                Arguments.of(Rule.READ, TODO, STRANGER, ErrorCode.TODO_NOT_FOUND),
                Arguments.of(Rule.READ, UNREQUESTED, STRANGER, ErrorCode.TODO_NOT_FOUND),
                Arguments.of(Rule.EDIT, TODO, OWNER, null),
                Arguments.of(Rule.EDIT, TODO, REQUESTER, ErrorCode.TODO_NOT_YOURS),
                Arguments.of(Rule.EDIT, TODO, STRANGER, ErrorCode.TODO_NOT_FOUND),
                Arguments.of(Rule.EDIT, UNREQUESTED, STRANGER, ErrorCode.TODO_NOT_FOUND));
    }

    @ParameterizedTest(name = "{0} by {2}: {3}")
    @MethodSource("decisions")
    @DisplayName("Each rule allows its callers and refuses the others with their code alone and its arguments (null: allowed)")
    void decides(Rule rule, Todo todo, UUID caller, ErrorCode refusal) {
        Decision decision = rule.decide.apply(todo, caller);

        assertThat(decision.isAllowed()).isEqualTo(Objects.isNull(refusal));
        if (Objects.isNull(refusal)) {
            assertThat(decision.refusals()).isEmpty();
            decision.orThrow();
        } else {
            // A reach refusal stands alone, and names only the todo's own id: nothing of another person's.
            assertThat(decision.refusals()).singleElement().satisfies(only -> {
                assertThat(only.code()).isEqualTo(refusal);
                assertThat(only.arguments()).containsExactly(todo.getId());
            });
            assertThatThrownBy(decision::orThrow).isInstanceOfSatisfying(AppException.class, refused -> {
                assertThat(refused.getErrorCode()).isEqualTo(refusal);
                assertThat(refused.getArgs()).containsExactly(todo.getId());
            });
        }
    }
}
