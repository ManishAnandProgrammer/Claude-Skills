package com.example.app.common.dropdown;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.Permission;
import com.example.app.common.security.Reach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The pieces decide which permissions exist, so the cases pick theirs from the enum. */
class DropdownForTest {

    @Test
    @DisplayName("A held permission whose reach the dropdown lists answers that reach")
    void aHeldPermissionOfASupportedReachPasses() {
        Permission action = first(Reach.OWN);

        assertThat(DropdownFor.requireReach(action, EnumSet.of(action), EnumSet.of(Reach.OWN))).isEqualTo(Reach.OWN);
    }

    @Test
    @DisplayName("A permission the caller doesn't hold is ACCESS_DENIED, whatever else they hold")
    void aPermissionNotHeldIsDenied() {
        Permission action = first(Reach.OWN);
        Set<Permission> allButIt = EnumSet.allOf(Permission.class);
        allButIt.remove(action);

        assertThatThrownBy(() -> DropdownFor.requireReach(action, allButIt, EnumSet.allOf(Reach.class)))
                .isInstanceOfSatisfying(AppException.class, refused -> assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
    }

    @Test
    @DisplayName("A held permission whose reach the dropdown doesn't list is DROPDOWN_REACH_UNSUPPORTED at for")
    void anUnsupportedReachIsRefused() {
        Permission action = first(Reach.ALL);

        assertThatThrownBy(() -> DropdownFor.requireReach(action, EnumSet.of(action), EnumSet.of(Reach.OWN)))
                .isInstanceOfSatisfying(AppException.class, refused -> {
                    assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.DROPDOWN_REACH_UNSUPPORTED);
                    assertThat(refused.getErrors()).singleElement()
                            .satisfies(error -> assertThat(error.parameter()).isEqualTo(DropdownFor.PARAMETER));
                });
    }

    private static Permission first(Reach reach) {
        Optional<Permission> found = Arrays.stream(Permission.values()).filter(permission -> permission.reach() == reach).findFirst();
        assumeTrue(found.isPresent(), "no permission of reach " + reach + " among the pieces");
        return found.get();
    }
}
