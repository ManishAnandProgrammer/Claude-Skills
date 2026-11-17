package com.example.app.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class AppExceptionTest {

    /** A failure one caller must catch on its own (ERR-04): a subclass, still an AppException with its code. */
    private static final class MoveRefusedException extends AppException {

        private MoveRefusedException(UUID recordId) {
            super(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, new Object[] {recordId}, List.of(), null);
        }
    }

    @Test
    @DisplayName("A subclass a caller catches keeps its code and arguments, so the handler answers it like any AppException")
    void subclassKeepsItsCodeAndArguments() {
        UUID recordId = UUID.randomUUID();

        AppException thrown = catchThrowableOfType(AppException.class, () -> {
            throw new MoveRefusedException(recordId);
        });

        assertThat(thrown).isInstanceOf(MoveRefusedException.class);
        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.OPTIMISTIC_LOCK_CONFLICT);
        assertThat(thrown.getArgs()).containsExactly(recordId);
    }
}
