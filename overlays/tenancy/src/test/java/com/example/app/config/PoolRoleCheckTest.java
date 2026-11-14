package com.example.app.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The startup check itself; every tenancy integration test starts the application through it. */
class PoolRoleCheckTest {

    @Test
    @DisplayName("A superuser or BYPASSRLS pool role, or one owning tables, stops startup; a plain role passes")
    void onlyAPlainRolePasses() {
        assertThatThrownBy(() -> PoolRoleCheck.check(true, 0))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("BYPASSRLS");
        assertThatThrownBy(() -> PoolRoleCheck.check(false, 3))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("owns 3 table(s)");
        assertThatCode(() -> PoolRoleCheck.check(false, 0)).doesNotThrowAnyException();
    }
}
