package com.example.app.media.api;

import com.example.app.common.security.Permission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UploadPurposeTest {

    @Test
    @DisplayName("Every purpose names at least one permission: one without any would refuse every upload")
    void everyPurposeNamesAPermission() {
        for (UploadPurpose purpose : UploadPurpose.values()) {
            assertThat(purpose.permissions()).as(purpose.name()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("A purpose takes exactly the permissions it names: a caller holding one of them may upload, holding any other may not")
    void onlyTheNamedPermissionsMayUpload() {
        for (UploadPurpose purpose : UploadPurpose.values()) {
            for (Permission permission : Permission.values()) {
                assertThat(purpose.mayUpload(permission::equals)).as(purpose + " with " + permission)
                        .isEqualTo(purpose.permissions().contains(permission));
            }
        }
    }
}
