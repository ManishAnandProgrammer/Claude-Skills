package com.example.app.common.security;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Core's own codes only: this test is copied with the core alone, which has no feature's codes. */
class DecisionTest {

    private static final UUID FIRST = UUID.randomUUID();
    private static final UUID SECOND = UUID.randomUUID();

    @Test
    @DisplayName("Two refusals keep the policy's order, and orThrow throws the first with its arguments")
    void twoRefusalsKeepTheirOrder() {
        Decision decision = Decision.of(List.of(
                Decision.Refusal.of(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, FIRST),
                Decision.Refusal.of(ErrorCode.DUPLICATE_RECORD, SECOND)));

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.codes()).containsExactly(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, ErrorCode.DUPLICATE_RECORD);
        assertThat(decision.refusals().get(1).arguments()).containsExactly(SECOND);
        assertThatThrownBy(decision::orThrow).isInstanceOfSatisfying(AppException.class, refused -> {
            assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.OPTIMISTIC_LOCK_CONFLICT);
            assertThat(refused.getArgs()).containsExactly(FIRST);
        });
    }

    @Test
    @DisplayName("No refusal is allowed: nothing listed, nothing thrown")
    void noRefusalIsAllowed() {
        Decision decision = Decision.of(List.of());

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.refusals()).isEmpty();
        assertThatNoException().isThrownBy(decision::orThrow);
    }

    @Test
    @DisplayName("A refusal keeps a null argument, as AppException.of takes it")
    void aRefusalKeepsANullArgument() {
        Decision decision = Decision.refused(ErrorCode.RESOURCE_NOT_FOUND, (Object) null);

        assertThat(decision.refusals()).singleElement()
                .satisfies(refusal -> assertThat(refusal.arguments()).containsExactly((Object) null));
    }
}
