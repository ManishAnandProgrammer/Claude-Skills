package com.example.app.media.api;

import com.example.app.user.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A purpose names its role as text (the media module doesn't depend on the user module), so a misspelt one is caught here. */
class UploadPurposeTest {

    @Test
    @DisplayName("Every purpose names at least one role, each a Role constant: a misspelt one would refuse its uploads")
    void everyRoleIsARole() {
        List<String> roles = Arrays.stream(Role.values()).map(Role::name).toList();

        for (UploadPurpose purpose : UploadPurpose.values()) {
            assertThat(purpose.roles()).as(purpose.name()).isNotEmpty();
            assertThat(roles).as(purpose.name()).containsAll(purpose.roles());
        }
    }

    @Test
    @DisplayName("A purpose takes exactly the roles it names: each of them may upload, every other role and an unknown one may not")
    void onlyTheNamedRolesMayUpload() {
        for (UploadPurpose purpose : UploadPurpose.values()) {
            for (Role role : Role.values()) {
                assertThat(purpose.mayUpload(role.name()::equals)).as(purpose + " for " + role).isEqualTo(purpose.roles().contains(role.name()));
            }
            assertThat(purpose.mayUpload("NOT_A_ROLE"::equals)).as(purpose.name()).isFalse();
        }
    }
}
