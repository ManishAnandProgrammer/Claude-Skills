package com.example.app.common.persistence;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Every entity's id comes from AuditableEntity: a UUIDv7 the application assigns, in creation order (DATA-09). */
class TimeOrderedIdsIntegrationTest extends IntegrationTest {

    @Test
    @DisplayName("New rows get version 7 ids, a later row's after an earlier one's")
    void newIdsAreVersion7InCreationOrder() throws Exception {
        TestUser first = newUser();
        TestUser second = newUser();

        assertThat(first.id().version()).isEqualTo(7);
        assertThat(second.id().version()).isEqualTo(7);
        assertThat(second.id().toString()).isGreaterThan(first.id().toString());
    }
}
