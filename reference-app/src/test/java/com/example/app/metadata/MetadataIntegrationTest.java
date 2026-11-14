package com.example.app.metadata;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.exception.GlobalExceptionHandler;
import com.example.app.metadata.api.AttendanceConfig;
import com.example.app.metadata.api.Metadata;
import com.example.app.metadata.api.MetadataKey;
import com.example.app.metadata.api.MetadataKeys;
import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.request.ServletWebRequest;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The metadata mechanism through the example keys ({@code ATTENDANCE_DEFAULTS},
 * {@code ATTENDANCE_CONFIG_<sessionId>}): bootstrap copies this class only with the example piece.
 * Each test uses session ids of its own; the fixed key is created once and never changed here.
 */
class MetadataIntegrationTest extends IntegrationTest {

    private static final String METADATA = "/api/v1/metadata/";
    /** Distinct across tests and runs sharing the database; 16 digits, under the 18 a key allows. */
    private static final int RACERS = 6;

    @Autowired
    private Metadata metadata;

    @Autowired
    private GlobalExceptionHandler exceptionHandler;

    private static UUID newSessionId() {
        return UUID.randomUUID();
    }

    private static String path(MetadataKey<?> key) {
        return METADATA + key.name();
    }

    private static String updatePayload(String value, long version) {
        return """
                {"value": %s, "version": %d}
                """.formatted(value, version);
    }

    private record Admin(UUID id, String accessToken) {
    }

    /** A signed-up user promoted to ADMIN (no endpoint grants it), signed in again so the token carries it. */
    private Admin newAdmin() throws Exception {
        TestUser user = newUser();
        assertThat(sql("update users set role = 'ADMIN' where id = ?", user.id())).isEqualTo(1);
        ResultActions login = login(user.email(), PASSWORD).andExpect(status().isOk());
        return new Admin(user.id(), read(login, "$.response.accessToken"));
    }

    /** A session's configuration stored as raw JSON, as an older or newer instance could have written it. */
    private void storeRaw(MetadataKey<?> key, String json) {
        assertThat(sql("""
                insert into configuration_metadata (id, key, value, created_at, updated_at, version)
                values (uuidv7(), ?, ?::jsonb, now(), now(), 0)
                """, key.name(), json)).isEqualTo(1);
    }

    private void ensureDefaultsExist() {
        sql("""
                insert into configuration_metadata (id, key, value, created_at, updated_at, version)
                values (uuidv7(), ?, '{}'::jsonb, now(), now(), 0)
                on conflict (key) do nothing
                """, MetadataKeys.ATTENDANCE_DEFAULTS.name());
    }

    @Test
    @DisplayName("create stores the record as a JSON object; get reads it back equal, with version 0 and the audit fields")
    void createStoresTheValueAndGetReadsIt() throws Exception {
        Admin admin = newAdmin();
        MetadataKey<AttendanceConfig> key = MetadataKeys.attendance(newSessionId());
        AttendanceConfig config = new AttendanceConfig(15, false, LocalTime.of(7, 45));

        metadata.create(key, config);

        assertThat(metadata.get(key)).isEqualTo(config);
        String stored = jdbc.queryForObject("select value::text from configuration_metadata where key = ?", String.class, key.name());
        assertThat(stored).contains("\"graceMinutes\": 15", "\"allowLateMark\": false", "\"dayStartsAt\": \"07:45:00\"");
        getAs(admin.accessToken(), path(key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.key").value(key.name()))
                .andExpect(jsonPath("$.response.value.graceMinutes").value(15))
                .andExpect(jsonPath("$.response.value.allowLateMark").value(false))
                .andExpect(jsonPath("$.response.value.dayStartsAt").value("07:45:00"))
                .andExpect(jsonPath("$.response.version").value(0))
                .andExpect(jsonPath("$.response.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.response.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.response.createdBy").isEmpty());
    }

    @Test
    @DisplayName("A fixed key is read like a per-context one")
    void fixedKeyIsReadable() throws Exception {
        Admin admin = newAdmin();
        ensureDefaultsExist();

        getAs(admin.accessToken(), path(MetadataKeys.ATTENDANCE_DEFAULTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.key").value("ATTENDANCE_DEFAULTS"))
                .andExpect(jsonPath("$.response.value.graceMinutes").isNumber());
    }

    @Test
    @DisplayName("PUT replaces the value and increments the version; the same value again changes nothing")
    void updateReplacesTheValueAndBumpsTheVersion() throws Exception {
        Admin admin = newAdmin();
        MetadataKey<AttendanceConfig> key = MetadataKeys.attendance(newSessionId());
        metadata.create(key, AttendanceConfig.defaults());
        String value = """
                {"graceMinutes": 20, "allowLateMark": false, "dayStartsAt": "08:30"}""";

        putAs(admin.accessToken(), path(key), updatePayload(value, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.value.graceMinutes").value(20))
                .andExpect(jsonPath("$.response.value.dayStartsAt").value("08:30:00"))
                .andExpect(jsonPath("$.response.version").value(1))
                .andExpect(jsonPath("$.response.updatedBy").value(admin.id().toString()));
        putAs(admin.accessToken(), path(key), updatePayload(value, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.version").value(1));

        assertThat(metadata.get(key)).isEqualTo(new AttendanceConfig(20, false, LocalTime.of(8, 30)));
    }

    @Test
    @DisplayName("The typed update replaces the value; a stale version is refused")
    void typedUpdateChecksTheVersion() {
        MetadataKey<AttendanceConfig> key = MetadataKeys.attendance(newSessionId());
        metadata.create(key, AttendanceConfig.defaults());
        AttendanceConfig changed = new AttendanceConfig(30, null, null);

        assertThat(metadata.update(key, changed, 0)).isEqualTo(changed);

        assertThatThrownBy(() -> metadata.update(key, AttendanceConfig.defaults(), 0))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(metadata.get(key)).isEqualTo(changed);
    }

    @Test
    @DisplayName("PUT with a stale version is 409 OPTIMISTIC_LOCK_CONFLICT and changes nothing")
    void updateWithAStaleVersionIs409() throws Exception {
        Admin admin = newAdmin();
        MetadataKey<AttendanceConfig> key = MetadataKeys.attendance(newSessionId());
        metadata.create(key, AttendanceConfig.defaults());
        putAs(admin.accessToken(), path(key), updatePayload("""
                {"graceMinutes": 5}""", 0)).andExpect(status().isOk());

        putAs(admin.accessToken(), path(key), updatePayload("""
                {"graceMinutes": 50}""", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK_CONFLICT"));

        getAs(admin.accessToken(), path(key))
                .andExpect(jsonPath("$.response.value.graceMinutes").value(5))
                .andExpect(jsonPath("$.response.version").value(1));
    }

    @Test
    @DisplayName("Concurrent PUTs with one version apply exactly once; the others are 409 OPTIMISTIC_LOCK_CONFLICT")
    void concurrentUpdatesWithOneVersionApplyOnce() throws Exception {
        Admin admin = newAdmin();
        MetadataKey<AttendanceConfig> key = MetadataKeys.attendance(newSessionId());
        metadata.create(key, AttendanceConfig.defaults());

        List<Answer> answers = race(() -> putAs(admin.accessToken(), path(key), updatePayload("""
                {"graceMinutes": 45}""", 0)));

        assertThat(answers).extracting(Answer::status).containsOnlyOnce(200).containsOnly(200, 409);
        assertRefusalsAre(answers, 409, "OPTIMISTIC_LOCK_CONFLICT");
        Long version = jdbc.queryForObject("select version from configuration_metadata where key = ?", Long.class, key.name());
        assertThat(version).isEqualTo(1L);
    }

    @Test
    @DisplayName("A key nobody declared is 404 METADATA_KEY_UNKNOWN on GET and PUT, also a per-context id not written as the factory writes it")
    void unknownKeyIs404() throws Exception {
        Admin admin = newAdmin();
        UUID sessionId = newSessionId();
        metadata.create(MetadataKeys.attendance(sessionId), AttendanceConfig.defaults());
        getAs(admin.accessToken(), METADATA + "ATTENDANCE_CONFIG_" + sessionId).andExpect(status().isOk());

        for (String name : List.of("NO_SUCH_CONFIG", "ATTENDANCE_CONFIG_" + sessionId.toString().toUpperCase(),
                "ATTENDANCE_CONFIG_" + sessionId + "0", "ATTENDANCE_CONFIG_not-a-uuid", "ATTENDANCE_CONFIG_",
                "attendance_defaults", "ATTENDANCE_CONFIG_7")) {
            getAs(admin.accessToken(), METADATA + name)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("METADATA_KEY_UNKNOWN"));
            putAs(admin.accessToken(), METADATA + name, updatePayload("{}", 0))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("METADATA_KEY_UNKNOWN"));
        }
    }

    @Test
    @DisplayName("A declared key whose value was never created is 404 METADATA_NOT_FOUND, through the API and to other modules")
    void declaredButNeverCreatedIs404() throws Exception {
        Admin admin = newAdmin();
        MetadataKey<AttendanceConfig> key = MetadataKeys.attendance(newSessionId());

        getAs(admin.accessToken(), path(key))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("METADATA_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("The configuration %s has not been set up yet.".formatted(key.name())));
        putAs(admin.accessToken(), path(key), updatePayload("{}", 0))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("METADATA_NOT_FOUND"));
        assertThatThrownBy(() -> metadata.get(key))
                .isInstanceOfSatisfying(AppException.class,
                        refused -> assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.METADATA_NOT_FOUND));
    }

    @Test
    @DisplayName("A value breaking its record's rules is 400 VALIDATION_FAILED naming the field, with the configured maximum; nothing changes")
    void invalidValueIs400NamingTheField() throws Exception {
        Admin admin = newAdmin();
        MetadataKey<AttendanceConfig> key = MetadataKeys.attendance(newSessionId());
        metadata.create(key, AttendanceConfig.defaults());

        putAs(admin.accessToken(), path(key), updatePayload("""
                {"graceMinutes": -1}""", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed", contains("value.graceMinutes: must be greater than or equal to 0")));
        putAs(admin.accessToken(), path(key), updatePayload("""
                {"graceMinutes": 121}""", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failed", contains("value.graceMinutes: must be at most 120")));
        putAs(admin.accessToken(), path(key), updatePayload("""
                {"graceMinutes": 120}""", 0))
                .andExpect(status().isOk());
        assertThatThrownBy(() -> metadata.create(MetadataKeys.attendance(newSessionId()), new AttendanceConfig(-5, null, null)))
                .isInstanceOfSatisfying(AppException.class,
                        refused -> assertThat(refused.getFailedValidations()).containsExactly("value.graceMinutes: must be greater than or equal to 0"));
    }

    @Test
    @DisplayName("A value that isn't the record's JSON is 400 REQUEST_UNREADABLE naming the field; a body without value or version is 400 VALIDATION_FAILED")
    void unreadableValueIs400() throws Exception {
        Admin admin = newAdmin();
        MetadataKey<AttendanceConfig> key = MetadataKeys.attendance(newSessionId());
        metadata.create(key, AttendanceConfig.defaults());

        putAs(admin.accessToken(), path(key), "{\"value\": {\"graceMinutes\": 5")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"));
        putAs(admin.accessToken(), path(key), updatePayload("""
                {"graceMinutes": "ten"}""", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"))
                .andExpect(jsonPath("$.failed", contains("value.graceMinutes: invalid value")));
        putAs(admin.accessToken(), path(key), updatePayload("""
                {"dayStartsAt": "8 o'clock"}""", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failed", contains("value.dayStartsAt: invalid value")));
        putAs(admin.accessToken(), path(key), updatePayload("""
                {"graceMinutes": 5, "graceMinuets": 6}""", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"))
                .andExpect(jsonPath("$.failed", contains("value.graceMinuets: unknown field")));
        for (String notAnObject : List.of("5", "null", "[]", "\"text\"")) {
            putAs(admin.accessToken(), path(key), updatePayload(notAnObject, 0))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"))
                    .andExpect(jsonPath("$.failed", contains("value: must be a JSON object")));
        }
        putAs(admin.accessToken(), path(key), "{\"version\": 0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed", contains("value: must not be null")));
        putAs(admin.accessToken(), path(key), "{\"value\": {}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failed", contains("version: must not be null")));
        assertThat(metadata.get(key)).isEqualTo(AttendanceConfig.defaults());
    }

    @Test
    @DisplayName("A field missing from the stored JSON, or from a PUT, takes the record's default")
    void missingFieldGetsItsDefault() throws Exception {
        Admin admin = newAdmin();
        MetadataKey<AttendanceConfig> stored = MetadataKeys.attendance(newSessionId());
        storeRaw(stored, """
                {"graceMinutes": 5}""");

        assertThat(metadata.get(stored)).isEqualTo(new AttendanceConfig(5, true, LocalTime.of(8, 0)));
        getAs(admin.accessToken(), path(stored))
                .andExpect(jsonPath("$.response.value.allowLateMark").value(true))
                .andExpect(jsonPath("$.response.value.dayStartsAt").value("08:00:00"));
        putAs(admin.accessToken(), path(stored), updatePayload("""
                {"allowLateMark": false}""", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.value.graceMinutes").value(10))
                .andExpect(jsonPath("$.response.value.allowLateMark").value(false));
    }

    @Test
    @DisplayName("A stored field the record doesn't know (removed, or from a newer instance) is ignored, not an unreadable row")
    void unknownStoredFieldIsTolerated() throws Exception {
        Admin admin = newAdmin();
        MetadataKey<AttendanceConfig> key = MetadataKeys.attendance(newSessionId());
        storeRaw(key, """
                {"graceMinutes": 7, "markHalfDayAfterMinutes": 90, "nested": {"a": [1, 2]}}""");

        assertThat(metadata.get(key)).isEqualTo(new AttendanceConfig(7, null, null));
        getAs(admin.accessToken(), path(key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.value.graceMinutes").value(7))
                .andExpect(jsonPath("$.response.value.markHalfDayAfterMinutes").doesNotExist());
    }

    @Test
    @DisplayName("Two sessions' configurations are independent rows: changing one leaves the other")
    void perContextKeysAreIndependent() throws Exception {
        Admin admin = newAdmin();
        MetadataKey<AttendanceConfig> first = MetadataKeys.attendance(newSessionId());
        MetadataKey<AttendanceConfig> second = MetadataKeys.attendance(newSessionId());
        metadata.create(first, new AttendanceConfig(5, null, null));
        metadata.create(second, new AttendanceConfig(25, null, null));

        putAs(admin.accessToken(), path(first), updatePayload("""
                {"graceMinutes": 6}""", 0)).andExpect(status().isOk());

        assertThat(metadata.get(first).graceMinutes()).isEqualTo(6);
        assertThat(metadata.get(second).graceMinutes()).isEqualTo(25);
        getAs(admin.accessToken(), path(second))
                .andExpect(jsonPath("$.response.value.graceMinutes").value(25))
                .andExpect(jsonPath("$.response.version").value(0));
    }

    /**
     * What a session module's "session created" code does: the first session starts from the fixed
     * defaults, every later one from the session before it. The copy is a row of its own.
     */
    @Test
    @DisplayName("Seeding a new session copies the previous session's configuration (the first one copies the defaults); the copy is independent")
    void createFromCopiesThePreviousSession() {
        ensureDefaultsExist();
        AttendanceConfig defaults = metadata.get(MetadataKeys.ATTENDANCE_DEFAULTS);
        MetadataKey<AttendanceConfig> firstSession = MetadataKeys.attendance(newSessionId());
        MetadataKey<AttendanceConfig> secondSession = MetadataKeys.attendance(newSessionId());

        assertThat(metadata.createFrom(MetadataKeys.ATTENDANCE_DEFAULTS, firstSession)).isEqualTo(defaults);
        AttendanceConfig adjusted = new AttendanceConfig(12, false, LocalTime.of(9, 0));
        metadata.update(firstSession, adjusted, 0);
        assertThat(metadata.createFrom(firstSession, secondSession)).isEqualTo(adjusted);

        metadata.update(secondSession, new AttendanceConfig(3, null, null), 0);
        assertThat(metadata.get(firstSession)).isEqualTo(adjusted);
        assertThat(metadata.get(secondSession).graceMinutes()).isEqualTo(3);
        assertThatThrownBy(() -> metadata.createFrom(MetadataKeys.attendance(newSessionId()), MetadataKeys.attendance(newSessionId())))
                .isInstanceOfSatisfying(AppException.class,
                        refused -> assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.METADATA_NOT_FOUND));
    }

    @Test
    @DisplayName("Without a token GET and PUT are 401; a signed-in non-admin is 403 ACCESS_DENIED and changes nothing")
    void onlyAdminsReachTheApi() throws Exception {
        TestUser user = newUser();
        MetadataKey<AttendanceConfig> key = MetadataKeys.attendance(newSessionId());
        metadata.create(key, AttendanceConfig.defaults());
        String payload = updatePayload("""
                {"graceMinutes": 99}""", 0);

        getAs(null, path(key))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        putAs(null, path(key), payload)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        getAs(user.accessToken(), path(key))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        putAs(user.accessToken(), path(key), payload)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        getAs(user.accessToken(), METADATA + "NO_SUCH_CONFIG")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        assertThat(metadata.get(key)).isEqualTo(AttendanceConfig.defaults());
    }

    @Test
    @DisplayName("Concurrent creates of one key store one row; each other is refused 409 METADATA_KEY_TAKEN without internals")
    void concurrentCreatesOfOneKeyStoreOne() throws Exception {
        MetadataKey<AttendanceConfig> key = MetadataKeys.attendance(newSessionId());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> pending = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int racer = 0; racer < RACERS; racer++) {
                Callable<String> create = () -> {
                    start.await();
                    return createAnswer(key);
                };
                pending.add(executor.submit(create));
            }
            start.countDown();
            List<String> answers = new ArrayList<>();
            for (Future<String> answer : pending) {
                answers.add(answer.get());
            }

            assertThat(answers).containsOnlyOnce("created").containsOnly("created", "409 METADATA_KEY_TAKEN");
        }
        Integer rows = jdbc.queryForObject("select count(*) from configuration_metadata where key = ?", Integer.class, key.name());
        assertThat(rows).isEqualTo(1);
    }

    /** One racer's create, its refusal answered by the real error handler, as it is for a request that seeds. */
    private String createAnswer(MetadataKey<AttendanceConfig> key) throws Exception {
        try {
            metadata.create(key, AttendanceConfig.defaults());
            return "created";
        } catch (DataIntegrityViolationException refused) {
            ResponseEntity<ProblemDetail> answer = exceptionHandler.handleDataIntegrity(refused,
                    new ServletWebRequest(new MockHttpServletRequest("PUT", "/seed")));
            ProblemDetail problem = answer.getBody();
            assertNoInternals(problem.getDetail());
            return answer.getStatusCode().value() + " " + problem.getProperties().get("errorCode");
        }
    }

    @Test
    @DisplayName("A key is UPPER_SNAKE_CASE (with an optional canonical UUID) with a record value, and a context id is required")
    void keysAreCheckedWhenDeclared() {
        assertThatThrownBy(() -> new MetadataKey<>("lower_case", AttendanceConfig.class)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MetadataKey<>("NOT_A_RECORD", String.class)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetadataKeys.attendance(null)).isInstanceOf(NullPointerException.class);
        UUID sessionId = newSessionId();
        assertThat(MetadataKeys.byName(MetadataKeys.attendance(sessionId).name())).contains(MetadataKeys.attendance(sessionId));
        assertThat(MetadataKeys.byName(UUID.randomUUID().toString())).isEmpty();
    }
}
