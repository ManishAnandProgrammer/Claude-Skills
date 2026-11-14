package com.example.app.media;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The policies through the API: everyone signed in reads them, an admin replaces them, without a restart. */
@ResourceLock(UploadUrlIntegrationTest.POLICY_LOCK)
class UploadPolicyIntegrationTest extends IntegrationTest {

    private static final String POLICIES = "/api/v1/upload-policies";
    private static final String TODO_POLICY = POLICIES + "/" + UploadUrlIntegrationTest.PURPOSE;
    private static final String TODO_ENTRY = "$.response[?(@.purpose == 'TODO_ATTACHMENT')]";

    @AfterEach
    void restoreThePolicy() {
        sql("""
                update upload_policies
                   set min_size_bytes = 1, max_size_bytes = 10485760, min_files = 0, max_files = 5,
                       content_types = array['application/pdf', 'image/jpeg', 'image/png']
                 where purpose = 'TODO_ATTACHMENT'
                """);
    }

    private long currentVersion(TestUser user) throws Exception {
        List<Integer> versions = read(getAs(user.accessToken(), POLICIES).andExpect(status().isOk()), TODO_ENTRY + ".version");
        return versions.getFirst();
    }

    private static String limits(long minSize, long maxSize, int minFiles, int maxFiles, String contentTypes, long version) {
        return """
                {"minSizeBytes": %d, "maxSizeBytes": %d, "minFiles": %d, "maxFiles": %d, "contentTypes": %s, "version": %d}
                """.formatted(minSize, maxSize, minFiles, maxFiles, contentTypes, version);
    }

    private ResultActions replace(TestUser user, String body) throws Exception {
        return putAs(user.accessToken(), TODO_POLICY, body);
    }

    @Test
    @DisplayName("Every signed-in user reads the limits; canUpload says whether their role may upload")
    void listShowsLimitsAndCanUpload() throws Exception {
        getAs(newUser().accessToken(), POLICIES)
                .andExpect(status().isOk())
                .andExpect(jsonPath(TODO_ENTRY + ".minSizeBytes").value(1))
                .andExpect(jsonPath(TODO_ENTRY + ".maxSizeBytes").value(10485760))
                .andExpect(jsonPath(TODO_ENTRY + ".minFiles").value(0))
                .andExpect(jsonPath(TODO_ENTRY + ".maxFiles").value(5))
                .andExpect(jsonPath(TODO_ENTRY + ".contentTypes[*]", containsInAnyOrder("image/jpeg", "image/png", "application/pdf")))
                .andExpect(jsonPath(TODO_ENTRY + ".canUpload").value(true));
        getAs(newAdmin().accessToken(), POLICIES)
                .andExpect(status().isOk())
                .andExpect(jsonPath(TODO_ENTRY + ".canUpload").value(false));
        getAs(null, POLICIES)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("An admin's new limits apply to the next request, without a restart; the version increments")
    void adminUpdateAppliesAtOnce() throws Exception {
        TestUser admin = newAdmin();
        TestUser user = newUser();
        long version = currentVersion(admin);
        uploadUrl(user, UploadUrlIntegrationTest.PURPOSE, "big.pdf", "application/pdf", 1001);

        replace(admin, limits(1, 1000, 0, 5, "[\"application/pdf\"]", version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.maxSizeBytes").value(1000))
                .andExpect(jsonPath("$.response.contentTypes[*]", containsInAnyOrder("application/pdf")))
                .andExpect(jsonPath("$.response.updatedBy").value(admin.id().toString()))
                .andExpect(jsonPath("$.response.version").value(version + 1));

        postAs(user.accessToken(), "/api/v1/upload-urls", """
                {"purpose": "TODO_ATTACHMENT", "files": [{"fileName": "big.pdf", "contentType": "application/pdf", "sizeBytes": 1001}]}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UPLOAD_SIZE_OUT_OF_RANGE"));
        postAs(user.accessToken(), "/api/v1/upload-urls", """
                {"purpose": "TODO_ATTACHMENT", "files": [{"fileName": "a.png", "contentType": "image/png", "sizeBytes": 10}]}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UPLOAD_TYPE_NOT_ALLOWED"));
        uploadUrl(user, UploadUrlIntegrationTest.PURPOSE, "fits.pdf", "application/pdf", 1000);
    }

    @Test
    @DisplayName("The same limits again change nothing, not even the version")
    void sameLimitsAgainChangeNothing() throws Exception {
        TestUser admin = newAdmin();
        long version = currentVersion(admin);

        replace(admin, limits(1, 10485760, 0, 5, "[\"application/pdf\", \"image/png\", \"image/jpeg\"]", version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.version").value(version));
    }

    @Test
    @DisplayName("A user who isn't an admin is 403 ACCESS_DENIED; no token is 401")
    void onlyAnAdminReplaces() throws Exception {
        TestUser user = newUser();
        String body = limits(1, 1000, 0, 5, "[\"application/pdf\"]", currentVersion(user));

        replace(user, body)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        putAs(null, TODO_POLICY, body)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        getAs(user.accessToken(), POLICIES).andExpect(jsonPath(TODO_ENTRY + ".maxSizeBytes").value(10485760));
    }

    @Test
    @DisplayName("A stale version is 409 OPTIMISTIC_LOCK_CONFLICT")
    void staleVersionIs409() throws Exception {
        TestUser admin = newAdmin();
        long version = currentVersion(admin);
        replace(admin, limits(1, 2000, 0, 5, "[\"application/pdf\"]", version)).andExpect(status().isOk());

        replace(admin, limits(1, 3000, 0, 5, "[\"application/pdf\"]", version))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK_CONFLICT"));
    }

    @Test
    @DisplayName("A type the application never accepts (SVG) is 400 UPLOAD_TYPE_NOT_ALLOWED, and the policy is unchanged")
    void neverAllowedTypeIs400() throws Exception {
        TestUser admin = newAdmin();

        replace(admin, limits(1, 1000, 0, 5, "[\"application/pdf\", \"image/svg+xml\"]", currentVersion(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UPLOAD_TYPE_NOT_ALLOWED"));
        getAs(admin.accessToken(), POLICIES).andExpect(jsonPath(TODO_ENTRY + ".maxSizeBytes").value(10485760));
    }

    static Stream<Arguments> invalidLimits() {
        return Stream.of(
                Arguments.of("minSizeBytes above maxSizeBytes", limits(2000, 1000, 0, 5, "[\"application/pdf\"]", 0)),
                Arguments.of("minFiles above maxFiles", limits(1, 1000, 3, 2, "[\"application/pdf\"]", 0)),
                Arguments.of("maxFiles over the configured maximum", limits(1, 1000, 0, 51, "[\"application/pdf\"]", 0)),
                Arguments.of("maxSizeBytes over 5 GiB", limits(1, 5368709121L, 0, 5, "[\"application/pdf\"]", 0)),
                Arguments.of("minSizeBytes of 0", limits(0, 1000, 0, 5, "[\"application/pdf\"]", 0)),
                Arguments.of("no content types", limits(1, 1000, 0, 5, "[]", 0)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidLimits")
    @DisplayName("Limits that break a range or a configured maximum are 400 VALIDATION_FAILED")
    void invalidLimitsAre400(String name, String body) throws Exception {
        replace(newAdmin(), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("An unknown purpose in the path is 400 REQUEST_PARAMETER_INVALID; a purpose without its row is 404 UPLOAD_POLICY_NOT_FOUND")
    void unknownOrUnseededPurpose() throws Exception {
        TestUser admin = newAdmin();
        String body = limits(1, 1000, 0, 5, "[\"application/pdf\"]", currentVersion(admin));

        putAs(admin.accessToken(), POLICIES + "/NO_SUCH_PURPOSE", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"));
        sql("update upload_policies set purpose = 'HIDDEN_FOR_TEST' where purpose = 'TODO_ATTACHMENT'");
        try {
            replace(admin, body)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("UPLOAD_POLICY_NOT_FOUND"));
        } finally {
            sql("update upload_policies set purpose = 'TODO_ATTACHMENT' where purpose = 'HIDDEN_FOR_TEST'");
        }
    }

    @Test
    @DisplayName("The API docs describe both endpoints, the admin one with its 403")
    void apiDocsDescribeTheEndpoints() throws Exception {
        getAs(null, "/v3/api-docs")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/upload-policies'].get.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/upload-policies/{purpose}'].put.responses['403'].description", containsString("ACCESS_DENIED")))
                .andExpect(jsonPath("$.paths['/api/v1/upload-policies/{purpose}'].put.responses['400'].description", containsString("UPLOAD_TYPE_NOT_ALLOWED")))
                .andExpect(jsonPath("$.components.schemas.UploadPolicyUpdateRequest.properties.maxFiles.maximum").value(50));
    }
}
