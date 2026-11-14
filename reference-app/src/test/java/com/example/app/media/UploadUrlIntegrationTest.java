package com.example.app.media;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.ResultActions;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /upload-urls} for the project's first purpose ({@link #TESTED_PURPOSE}; skipped while
 * there is none): who may ask, and the policy's limits. The lock keeps the tests that change a
 * purpose's policy from overlapping.
 */
@ResourceLock(IntegrationTest.UPLOAD_POLICIES_LOCK)
class UploadUrlIntegrationTest extends IntegrationTest {

    private static final String PURPOSE = TESTED_PURPOSE;
    private static final long MAX_SIZE = 10_485_760;
    private static final String UPLOAD_URLS = "/api/v1/upload-urls";

    private UploadPolicyLimits saved;

    @BeforeEach
    void setThePolicy() {
        assumeAnUploadPurpose();
        saved = uploadPolicyOf(PURPOSE);
        setUploadPolicy(PURPOSE, 1, MAX_SIZE, 0, 5);
    }

    @AfterEach
    void restoreThePolicy() {
        restoreUploadPolicy(saved);
    }

    private static String file(String fileName, String contentType, long sizeBytes) {
        return """
                {"fileName": "%s", "contentType": "%s", "sizeBytes": %d}""".formatted(fileName, contentType, sizeBytes);
    }

    private ResultActions issue(TestUser user, String purpose, List<String> files) throws Exception {
        return postAs(user.accessToken(), UPLOAD_URLS, """
                {"purpose": "%s", "files": [%s]}
                """.formatted(purpose, String.join(", ", files)));
    }

    private int filesOf(TestUser user) {
        return jdbc.queryForObject("select count(*) from media_files where uploaded_by = ?", Integer.class, user.id());
    }

    private static String signedHeaders(String url) {
        for (String part : url.substring(url.indexOf('?') + 1).split("&")) {
            if (part.startsWith("X-Amz-SignedHeaders=")) {
                return URLDecoder.decode(part.substring("X-Amz-SignedHeaders=".length()), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    @Test
    @DisplayName("Each file gets a key, a URL signed with its size, type and disposition, the headers to send and the expiry; it is recorded PENDING")
    void issuesOneSignedUploadPerFile() throws Exception {
        TestUser user = newUploader();

        ResultActions issued = issue(user, PURPOSE, List.of(file("receipt.pdf", "application/pdf", 48_213), file("photo.png", "image/png", 10)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response", hasSize(2)))
                // tenancy:begin
                // .andExpect(jsonPath("$.response[0].uploadKey", startsWith(testTenant().id() + "/" + PURPOSE + "/")))
                // tenancy:else
                .andExpect(jsonPath("$.response[0].uploadKey", startsWith(PURPOSE + "/")))
                // tenancy:end
                .andExpect(jsonPath("$.response[0].headers['Content-Type']").value("application/pdf"))
                .andExpect(jsonPath("$.response[0].headers['Content-Disposition']", startsWith("attachment;")))
                .andExpect(jsonPath("$.response[0].headers['Content-Disposition']", containsString("receipt.pdf")))
                .andExpect(jsonPath("$.response[0].expiresAt").isNotEmpty());

        String url = read(issued, "$.response[0].url");
        assertThat(signedHeaders(url)).isEqualTo("content-disposition;content-length;content-type;host");
        String uploadKey = read(issued, "$.response[0].uploadKey");
        Map<String, Object> row = jdbc.queryForMap("select * from media_files where key = ?", uploadKey);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("uploaded_by")).isEqualTo(user.id());
        assertThat(row.get("size_bytes")).isEqualTo(48_213L);
        Instant uploadExpiresAt = ((Timestamp) row.get("upload_expires_at")).toInstant();
        String expiresAt = read(issued, "$.response[0].expiresAt");
        assertThat(uploadExpiresAt).isEqualTo(Instant.parse(expiresAt));
        assertThat(Duration.between(uploadExpiresAt, ((Timestamp) row.get("expires_at")).toInstant())).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("Without a token it is 401 AUTHENTICATION_REQUIRED")
    void withoutATokenIs401() throws Exception {
        postAs(null, UPLOAD_URLS, """
                {"purpose": "%s", "files": [%s]}
                """.formatted(PURPOSE, file("a.pdf", "application/pdf", 10)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("A role the purpose doesn't list (roles don't include each other) is 403 ACCESS_DENIED, and nothing is recorded; skipped when it lists every role")
    void aRoleThePurposeDoesNotNameIs403() throws Exception {
        TestUser other = newNonUploader();

        issue(other, PURPOSE, List.of(file("a.pdf", "application/pdf", 10)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        assertThat(filesOf(other)).isZero();
    }

    @Test
    @DisplayName("An unknown purpose is 400 REQUEST_UNREADABLE")
    void unknownPurposeIs400() throws Exception {
        issue(newUser(), "NO_SUCH_PURPOSE", List.of(file("a.pdf", "application/pdf", 10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"));
    }

    @Test
    @DisplayName("A size one byte outside the policy's range, either side, is 400 UPLOAD_SIZE_OUT_OF_RANGE; both bounds pass")
    void sizeMustBeWithinThePolicy() throws Exception {
        setUploadPolicy(PURPOSE, 10, 100, 0, 5);
        TestUser user = newUploader();

        for (long refused : List.of(9L, 101L)) {
            issue(user, PURPOSE, List.of(file("a.pdf", "application/pdf", refused)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("UPLOAD_SIZE_OUT_OF_RANGE"));
        }
        issue(user, PURPOSE, List.of(file("a.pdf", "application/pdf", 10), file("b.pdf", "application/pdf", 100)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("A size of 0 or none is 400 VALIDATION_FAILED: an empty file is never an upload")
    void zeroOrMissingSizeIs400() throws Exception {
        TestUser user = newUploader();

        issue(user, PURPOSE, List.of(file("a.pdf", "application/pdf", 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        issue(user, PURPOSE, List.of("""
                {"fileName": "a.pdf", "contentType": "application/pdf"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/svg+xml", "text/html", "application/xml", "image/png; charset=utf-8", "IMAGE/PNG", "image/webp"})
    @DisplayName("A type outside the policy (SVG, HTML, XML, a parameter, another case, an allowed type the policy doesn't list) is 400 UPLOAD_TYPE_NOT_ALLOWED")
    void typeMustBeExactlyOneOfThePolicys(String contentType) throws Exception {
        TestUser user = newUploader();

        issue(user, PURPOSE, List.of(file("a", contentType, 10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UPLOAD_TYPE_NOT_ALLOWED"));
        assertThat(filesOf(user)).isZero();
    }

    @Test
    @DisplayName("More files than the policy's maxFiles is 400 UPLOAD_FILE_COUNT_OUT_OF_RANGE; exactly maxFiles passes; none is VALIDATION_FAILED")
    void fileCountMustBeWithinThePolicy() throws Exception {
        TestUser user = newUploader();
        List<String> five = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            five.add(file("f" + index + ".pdf", "application/pdf", 10));
        }
        List<String> six = new ArrayList<>(five);
        six.add(file("f5.pdf", "application/pdf", 10));

        issue(user, PURPOSE, six)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UPLOAD_FILE_COUNT_OUT_OF_RANGE"));
        assertThat(filesOf(user)).isZero();
        issue(user, PURPOSE, five).andExpect(status().isCreated()).andExpect(jsonPath("$.response", hasSize(5)));
        issue(user, PURPOSE, List.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("One refused file refuses the request: none of its files is recorded")
    void oneRefusedFileRefusesAll() throws Exception {
        TestUser user = newUploader();

        issue(user, PURPOSE, List.of(file("ok.pdf", "application/pdf", 10), file("evil.svg", "image/svg+xml", 10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UPLOAD_TYPE_NOT_ALLOWED"));
        assertThat(filesOf(user)).isZero();
    }

    @Test
    @DisplayName("A purpose without its policy row is 404 UPLOAD_POLICY_NOT_FOUND")
    void purposeWithoutAPolicyIs404() throws Exception {
        TestUser user = newUploader();
        assertThat(sql("update upload_policies set purpose = 'HIDDEN_FOR_TEST' where purpose = ?", PURPOSE)).isEqualTo(1);
        try {
            issue(user, PURPOSE, List.of(file("a.pdf", "application/pdf", 10)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("UPLOAD_POLICY_NOT_FOUND"));
        } finally {
            assertThat(sql("update upload_policies set purpose = ? where purpose = 'HIDDEN_FOR_TEST'", PURPOSE)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("The API docs describe POST /upload-urls with its 201, 400, 403 and 503")
    void apiDocsDescribeTheEndpoint() throws Exception {
        getAs(null, "/v3/api-docs")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/upload-urls'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/upload-urls'].post.responses['400'].description", containsString("UPLOAD_SIZE_OUT_OF_RANGE")))
                .andExpect(jsonPath("$.paths['/api/v1/upload-urls'].post.responses['403'].description", containsString("ACCESS_DENIED")))
                .andExpect(jsonPath("$.paths['/api/v1/upload-urls'].post.responses['503'].description", containsString("MEDIA_STORAGE_UNAVAILABLE")));
    }
}
