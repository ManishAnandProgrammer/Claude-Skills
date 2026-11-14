package com.example.app.media.api;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileCountRangeTest {

    private final FileCountRange range = new FileCountRange(2, 5);

    @Test
    @DisplayName("Both bounds are inside the range; one step past either is UPLOAD_FILE_COUNT_OUT_OF_RANGE")
    void boundsAreInclusive() {
        assertThatCode(() -> range.requireWithin(2)).doesNotThrowAnyException();
        assertThatCode(() -> range.requireWithin(5)).doesNotThrowAnyException();
        assertOutOfRange(1);
        assertOutOfRange(6);
    }

    @Test
    @DisplayName("5 files, minimum 2: removing 4 alone is refused; removing 4 and adding 1 passes")
    void removingBelowTheMinimumNeedsFilesAdded() {
        assertThatThrownBy(() -> range.requireWithin(5, 4, 0))
                .isInstanceOfSatisfying(AppException.class,
                        refused -> assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.UPLOAD_FILE_COUNT_OUT_OF_RANGE));
        assertThatCode(() -> range.requireWithin(5, 4, 1)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Adding past the maximum is refused even when files are removed too, unless enough are")
    void addingPastTheMaximumIsRefused() {
        assertOutOfRange(5 - 1 + 2);
        assertThatCode(() -> range.requireWithin(5, 2, 2)).doesNotThrowAnyException();
    }

    private void assertOutOfRange(int count) {
        assertThatThrownBy(() -> range.requireWithin(count))
                .isInstanceOfSatisfying(AppException.class,
                        refused -> assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.UPLOAD_FILE_COUNT_OUT_OF_RANGE));
    }
}
