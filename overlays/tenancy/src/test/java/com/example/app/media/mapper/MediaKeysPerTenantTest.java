package com.example.app.media.mapper;

import com.example.app.common.tenancy.TenantScope;
import com.example.app.media.api.UploadPurpose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MediaKeysPerTenantTest {

    private final MediaFileMapper mapper = new MediaFileMapper();

    @Test
    @DisplayName("A new object key starts with the current tenant's id, then the purpose")
    void newKeysStartWithTheTenant() {
        assumeTrue(UploadPurpose.values().length > 0, "no upload purpose declared yet");
        UploadPurpose purpose = UploadPurpose.values()[0];
        UUID tenant = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        String key = TenantScope.callAs(tenant, () -> mapper.newKey(purpose));
        String othersKey = TenantScope.callAs(other, () -> mapper.newKey(purpose));

        assertThat(key).startsWith(tenant + "/" + purpose.name() + "/");
        assertThat(othersKey).startsWith(other + "/" + purpose.name() + "/");
    }
}
