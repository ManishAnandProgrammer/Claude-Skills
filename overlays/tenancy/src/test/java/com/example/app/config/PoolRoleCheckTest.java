package com.example.app.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PoolRoleCheckTest {

    @Test
    @DisplayName("A superuser or BYPASSRLS pool role, one owning tables, or one that can write staff accounts beyond a deactivation stops startup; a plain role passes")
    void onlyAPlainRolePasses() {
        assertThatThrownBy(() -> PoolRoleCheck.check(true, 0, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("BYPASSRLS");
        assertThatThrownBy(() -> PoolRoleCheck.check(false, 3, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("owns 3 table(s)");
        assertThatThrownBy(() -> PoolRoleCheck.check(false, 0, true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("platform_users");
        assertThatCode(() -> PoolRoleCheck.check(false, 0, false)).doesNotThrowAnyException();
    }
}
