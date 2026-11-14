package com.example.app.common.pagination;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Reading a cursor back: the query itself runs against a stub, only the keys' types matter here. */
class KeysetPagesTest {

    /** A row of a list sorted by an Integer column (minutes, a count) with the id as tie-breaker. */
    private record Row(Integer amount, UUID id) {
    }

    private static final Keyset<Object, Row> BY_AMOUNT = new Keyset<>("AMOUNT", true, List.of(
            new KeyColumn<Object, Row, Integer>(root -> null, Row::amount, Integer.class),
            new KeyColumn<Object, Row, UUID>(root -> null, Row::id, UUID.class)));

    private final EntityManager entityManager = mock(EntityManager.class, RETURNS_DEEP_STUBS);
    private final KeysetPages keysetPages = new KeysetPages(entityManager, JsonMapper.builder().build());

    private static String cursor(String amount) {
        String json = """
                {"sort": "AMOUNT", "keys": ["%s", "%s"]}
                """.formatted(amount, UUID.randomUUID());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private CursorBatch<Row> fetchAfter(String after) {
        ProjectionQuery<Object, Row> query = new ProjectionQuery<>(Object.class, Row.class,
                (builder, root) -> null, (builder, root, criteria) -> List.of());
        return keysetPages.fetch(query, BY_AMOUNT, after, 20);
    }

    @Test
    @DisplayName("A cursor with an Integer key is read back, so the next batch of an Integer-sorted list loads")
    void integerKeyIsReadBack() {
        when(entityManager.createQuery(any(CriteriaQuery.class)).setMaxResults(anyInt()).getResultList()).thenReturn(List.of());

        CursorBatch<Row> batch = fetchAfter(cursor("45"));

        assertThat(batch.nextCursor()).isNull();
    }

    @Test
    @DisplayName("A tampered Integer key is 400 REQUEST_PARAMETER_INVALID naming after, not a 500")
    void tamperedIntegerKeyIsRefused() {
        assertThatExceptionOfType(AppException.class)
                .isThrownBy(() -> fetchAfter(cursor("forty-five")))
                .satisfies(refused -> {
                    assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.REQUEST_PARAMETER_INVALID);
                    assertThat(refused.getExtensions()).containsEntry("parameter", "after");
                });
    }
}
