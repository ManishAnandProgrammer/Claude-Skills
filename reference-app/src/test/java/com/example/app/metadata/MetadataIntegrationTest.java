package com.example.app.metadata;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.exception.GlobalExceptionHandler;
import com.example.app.metadata.api.TodoListSettings;
import com.example.app.metadata.api.Metadata;
import com.example.app.metadata.api.MetadataKey;
import com.example.app.metadata.api.MetadataKeys;
import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
 * The metadata mechanism through the example keys: the admin API on the fixed key
 * {@code TODO_LIST_DEFAULTS}, the typed {@link Metadata} on per-context keys
 * ({@code TODO_LIST_SETTINGS_<listId>}), which the admin API never reaches. Bootstrap copies this
 * class only with the example piece.
 *
 * <p>The fixed key is one row shared by every test here: each test starts from the record's
 * defaults at version 0, and the lock keeps tests that change it from running at the same time.
 * Per-context tests use list ids of their own.</p>
 */
@ResourceLock(MetadataIntegrationTest.FIXED_KEY)
class MetadataIntegrationTest extends IntegrationTest {

    static final String FIXED_KEY = "TODO_LIST_DEFAULTS";
    private static final MetadataKey<TodoListSettings> DEFAULTS = MetadataKeys.TODO_LIST_DEFAULTS;
    private static final String METADATA = "/api/v1/metadata/";
    private static final String DEFAULTS_PATH = METADATA + FIXED_KEY;
    private static final int RACERS = 6;

    @Autowired
    private Metadata metadata;

    @Autowired
    private GlobalExceptionHandler exceptionHandler;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static UUID newListId() {
        return UUID.randomUUID();
    }

    private static String updatePayload(String value, long version) {
        return """
                {"value": %s, "version": %d}
                """.formatted(value, version);
    }

    @BeforeEach
    void resetTheFixedKey() {
        storeFixedKeyRaw("{}");
    }

    /** The fixed key's row replaced by {@code json} at version 0, as an older or newer instance could have written it. */
    private void storeFixedKeyRaw(String json) {
        sql("delete from configuration_metadata where key = ?", FIXED_KEY);
        storeRaw(DEFAULTS, json);
    }

    /** A configuration stored as raw JSON, as an older or newer instance could have written it. */
    private void storeRaw(MetadataKey<?> key, String json) {
        assertThat(sql("""
                insert into configuration_metadata (id, key, value, created_at, updated_at, version)
                values (uuidv7(), ?, ?::jsonb, now(), now(), 0)
                """, key.name(), json)).isEqualTo(1);
    }

    private long storedVersion(MetadataKey<?> key) {
        return jdbc.queryForObject("select version from configuration_metadata where key = ?", Long.class, key.name());
    }

    @Test
    @DisplayName("create stores the record as a JSON object; get reads it back equal")
    void createStoresTheValueAndGetReadsIt() {
        MetadataKey<TodoListSettings> key = MetadataKeys.todoListSettings(newListId());
        TodoListSettings settings = new TodoListSettings(15, true, LocalTime.of(7, 45));

        metadata.create(key, settings);

        assertThat(metadata.get(key)).isEqualTo(settings);
        String stored = jdbc.queryForObject("select value::text from configuration_metadata where key = ?", String.class, key.name());
        assertThat(stored).contains("\"reminderMinutesBefore\": 15", "\"archiveDoneTodos\": true", "\"dailyDigestAt\": \"07:45:00\"");
        assertThat(storedVersion(key)).isZero();
    }

    @Test
    @DisplayName("GET of a fixed key returns its value with the defaults filled in, version 0 and the audit fields")
    void getReadsAFixedKey() throws Exception {
        TestUser admin = newAdmin();

        getAs(admin.accessToken(), DEFAULTS_PATH)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.key").value(FIXED_KEY))
                .andExpect(jsonPath("$.response.value.reminderMinutesBefore").value(30))
                .andExpect(jsonPath("$.response.value.archiveDoneTodos").value(false))
                .andExpect(jsonPath("$.response.value.dailyDigestAt").value("09:00:00"))
                .andExpect(jsonPath("$.response.version").value(0))
                .andExpect(jsonPath("$.response.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.response.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.response.createdBy").isEmpty());
    }

    @Test
    @DisplayName("PUT replaces the value and increments the version; the same value again changes nothing")
    void updateReplacesTheValueAndBumpsTheVersion() throws Exception {
        TestUser admin = newAdmin();
        String value = """
                {"reminderMinutesBefore": 20, "archiveDoneTodos": true, "dailyDigestAt": "08:30"}""";

        putAs(admin.accessToken(), DEFAULTS_PATH, updatePayload(value, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.value.reminderMinutesBefore").value(20))
                .andExpect(jsonPath("$.response.value.dailyDigestAt").value("08:30:00"))
                .andExpect(jsonPath("$.response.version").value(1))
                .andExpect(jsonPath("$.response.updatedBy").value(admin.id().toString()));
        putAs(admin.accessToken(), DEFAULTS_PATH, updatePayload(value, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.version").value(1));

        assertThat(metadata.get(DEFAULTS)).isEqualTo(new TodoListSettings(20, true, LocalTime.of(8, 30)));
    }

    @Test
    @DisplayName("The typed update replaces the value; a stale version is refused")
    void typedUpdateChecksTheVersion() {
        MetadataKey<TodoListSettings> key = MetadataKeys.todoListSettings(newListId());
        metadata.create(key, TodoListSettings.defaults());
        TodoListSettings changed = new TodoListSettings(60, null, null);

        assertThat(metadata.update(key, changed, 0)).isEqualTo(changed);

        assertThatThrownBy(() -> metadata.update(key, TodoListSettings.defaults(), 0))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(metadata.get(key)).isEqualTo(changed);
    }

    @Test
    @DisplayName("PUT with a stale version is 409 OPTIMISTIC_LOCK_CONFLICT and changes nothing")
    void updateWithAStaleVersionIs409() throws Exception {
        TestUser admin = newAdmin();
        putAs(admin.accessToken(), DEFAULTS_PATH, updatePayload("""
                {"reminderMinutesBefore": 5}""", 0)).andExpect(status().isOk());

        putAs(admin.accessToken(), DEFAULTS_PATH, updatePayload("""
                {"reminderMinutesBefore": 50}""", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK_CONFLICT"));

        getAs(admin.accessToken(), DEFAULTS_PATH)
                .andExpect(jsonPath("$.response.value.reminderMinutesBefore").value(5))
                .andExpect(jsonPath("$.response.version").value(1));
    }

    @Test
    @DisplayName("Concurrent PUTs with one version apply exactly once; the others are 409 OPTIMISTIC_LOCK_CONFLICT")
    void concurrentUpdatesWithOneVersionApplyOnce() throws Exception {
        TestUser admin = newAdmin();

        List<Answer> answers = race(() -> putAs(admin.accessToken(), DEFAULTS_PATH, updatePayload("""
                {"reminderMinutesBefore": 45}""", 0)));

        assertThat(answers).extracting(Answer::status).containsOnlyOnce(200).containsOnly(200, 409);
        assertRefusalsAre(answers, 409, "OPTIMISTIC_LOCK_CONFLICT");
        assertThat(storedVersion(DEFAULTS)).isEqualTo(1L);
    }

    @Test
    @DisplayName("A name that isn't a fixed key is 404 METADATA_KEY_UNKNOWN on GET and PUT, also a per-context key whose value exists")
    void unknownKeyIs404() throws Exception {
        TestUser admin = newAdmin();
        UUID listId = newListId();
        MetadataKey<TodoListSettings> perContext = MetadataKeys.todoListSettings(listId);
        metadata.create(perContext, TodoListSettings.defaults());
        getAs(admin.accessToken(), DEFAULTS_PATH).andExpect(status().isOk());

        for (String name : List.of(perContext.name(), "NO_SUCH_CONFIG", "TODO_LIST_SETTINGS_" + listId.toString().toUpperCase(),
                "TODO_LIST_SETTINGS_not-a-uuid", "TODO_LIST_SETTINGS_", "todo_list_defaults", FIXED_KEY + "_")) {
            getAs(admin.accessToken(), METADATA + name)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("METADATA_KEY_UNKNOWN"));
            putAs(admin.accessToken(), METADATA + name, updatePayload("""
                    {"reminderMinutesBefore": 99}""", 0))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("METADATA_KEY_UNKNOWN"));
        }
        assertThat(metadata.get(perContext)).isEqualTo(TodoListSettings.defaults());
    }

    @Test
    @DisplayName("A declared key whose value was never created is 404 METADATA_NOT_FOUND, through the API and to other modules")
    void declaredButNeverCreatedIs404() throws Exception {
        TestUser admin = newAdmin();
        getAs(admin.accessToken(), DEFAULTS_PATH).andExpect(status().isOk());
        sql("delete from configuration_metadata where key = ?", FIXED_KEY);

        getAs(admin.accessToken(), DEFAULTS_PATH)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("METADATA_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("The configuration %s has not been set up yet.".formatted(FIXED_KEY)));
        putAs(admin.accessToken(), DEFAULTS_PATH, updatePayload("{}", 0))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("METADATA_NOT_FOUND"));
        assertThatThrownBy(() -> metadata.get(MetadataKeys.todoListSettings(newListId())))
                .isInstanceOfSatisfying(AppException.class,
                        refused -> assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.METADATA_NOT_FOUND));
    }

    @Test
    @DisplayName("A value breaking its record's rules is 400 VALIDATION_FAILED naming the field, with the configured maximum; nothing changes")
    void invalidValueIs400NamingTheField() throws Exception {
        TestUser admin = newAdmin();

        putAs(admin.accessToken(), DEFAULTS_PATH, updatePayload("""
                {"reminderMinutesBefore": -1}""", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed", contains("value.reminderMinutesBefore: must be greater than or equal to 0")));
        putAs(admin.accessToken(), DEFAULTS_PATH, updatePayload("""
                {"reminderMinutesBefore": 1441}""", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failed", contains("value.reminderMinutesBefore: must be at most 1440")));
        assertThat(storedVersion(DEFAULTS)).isZero();
        putAs(admin.accessToken(), DEFAULTS_PATH, updatePayload("""
                {"reminderMinutesBefore": 1440}""", 0))
                .andExpect(status().isOk());
        assertThatThrownBy(() -> metadata.create(MetadataKeys.todoListSettings(newListId()), new TodoListSettings(-5, null, null)))
                .isInstanceOfSatisfying(AppException.class,
                        refused -> assertThat(refused.getFailedValidations()).containsExactly("value.reminderMinutesBefore: must be greater than or equal to 0"));
    }

    @Test
    @DisplayName("A value that isn't the record's JSON is 400 REQUEST_UNREADABLE naming the field; a body without value or version is 400 VALIDATION_FAILED")
    void unreadableValueIs400() throws Exception {
        TestUser admin = newAdmin();

        putAs(admin.accessToken(), DEFAULTS_PATH, "{\"value\": {\"reminderMinutesBefore\": 5")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"));
        putAs(admin.accessToken(), DEFAULTS_PATH, updatePayload("""
                {"reminderMinutesBefore": "ten"}""", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"))
                .andExpect(jsonPath("$.failed", contains("value.reminderMinutesBefore: invalid value")));
        putAs(admin.accessToken(), DEFAULTS_PATH, updatePayload("""
                {"dailyDigestAt": "8 o'clock"}""", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failed", contains("value.dailyDigestAt: invalid value")));
        putAs(admin.accessToken(), DEFAULTS_PATH, updatePayload("""
                {"reminderMinutesBefore": 5, "reminderMinutesBefroe": 6}""", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"))
                .andExpect(jsonPath("$.failed", contains("value.reminderMinutesBefroe: unknown field")));
        for (String notAnObject : List.of("5", "null", "[]", "\"text\"")) {
            putAs(admin.accessToken(), DEFAULTS_PATH, updatePayload(notAnObject, 0))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"))
                    .andExpect(jsonPath("$.failed", contains("value: must be a JSON object")));
        }
        putAs(admin.accessToken(), DEFAULTS_PATH, "{\"version\": 0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed", contains("value: must not be null")));
        putAs(admin.accessToken(), DEFAULTS_PATH, "{\"value\": {}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failed", contains("version: must not be null")));
        assertThat(metadata.get(DEFAULTS)).isEqualTo(TodoListSettings.defaults());
    }

    @Test
    @DisplayName("A field missing from the stored JSON, or from a PUT, takes the record's default")
    void missingFieldGetsItsDefault() throws Exception {
        TestUser admin = newAdmin();
        MetadataKey<TodoListSettings> stored = MetadataKeys.todoListSettings(newListId());
        storeRaw(stored, """
                {"reminderMinutesBefore": 5}""");
        storeFixedKeyRaw("""
                {"reminderMinutesBefore": 5}""");

        assertThat(metadata.get(stored)).isEqualTo(new TodoListSettings(5, false, LocalTime.of(9, 0)));
        getAs(admin.accessToken(), DEFAULTS_PATH)
                .andExpect(jsonPath("$.response.value.reminderMinutesBefore").value(5))
                .andExpect(jsonPath("$.response.value.archiveDoneTodos").value(false))
                .andExpect(jsonPath("$.response.value.dailyDigestAt").value("09:00:00"));
        putAs(admin.accessToken(), DEFAULTS_PATH, updatePayload("""
                {"archiveDoneTodos": true}""", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.value.reminderMinutesBefore").value(30))
                .andExpect(jsonPath("$.response.value.archiveDoneTodos").value(true));
    }

    @Test
    @DisplayName("A stored field the record doesn't know (removed, or from a newer instance) is ignored, not an unreadable row")
    void unknownStoredFieldIsTolerated() throws Exception {
        TestUser admin = newAdmin();
        MetadataKey<TodoListSettings> key = MetadataKeys.todoListSettings(newListId());
        String newer = """
                {"reminderMinutesBefore": 7, "snoozeMinutes": 90, "nested": {"a": [1, 2]}}""";
        storeRaw(key, newer);
        storeFixedKeyRaw(newer);

        assertThat(metadata.get(key)).isEqualTo(new TodoListSettings(7, null, null));
        getAs(admin.accessToken(), DEFAULTS_PATH)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.value.reminderMinutesBefore").value(7))
                .andExpect(jsonPath("$.response.value.snoozeMinutes").doesNotExist());
    }

    @Test
    @DisplayName("Two lists' settings are independent rows: changing one leaves the other")
    void perContextKeysAreIndependent() {
        MetadataKey<TodoListSettings> first = MetadataKeys.todoListSettings(newListId());
        MetadataKey<TodoListSettings> second = MetadataKeys.todoListSettings(newListId());
        metadata.create(first, new TodoListSettings(5, null, null));
        metadata.create(second, new TodoListSettings(25, null, null));

        metadata.update(first, new TodoListSettings(6, null, null), 0);

        assertThat(metadata.get(first).reminderMinutesBefore()).isEqualTo(6);
        assertThat(metadata.get(second).reminderMinutesBefore()).isEqualTo(25);
        assertThat(storedVersion(second)).isZero();
    }

    /**
     * What the todo module's "list created" code would do: the first list starts from the fixed
     * defaults, a list made from another one from that list's settings. The copy is a row of its own.
     */
    @Test
    @DisplayName("Seeding a new list copies another list's settings (the first one copies the defaults); the copy is independent")
    void createFromCopiesAnotherList() {
        TodoListSettings defaults = metadata.get(MetadataKeys.TODO_LIST_DEFAULTS);
        MetadataKey<TodoListSettings> firstList = MetadataKeys.todoListSettings(newListId());
        MetadataKey<TodoListSettings> secondList = MetadataKeys.todoListSettings(newListId());

        assertThat(metadata.createFrom(MetadataKeys.TODO_LIST_DEFAULTS, firstList)).isEqualTo(defaults);
        TodoListSettings adjusted = new TodoListSettings(12, true, LocalTime.of(18, 0));
        metadata.update(firstList, adjusted, 0);
        assertThat(metadata.createFrom(firstList, secondList)).isEqualTo(adjusted);

        metadata.update(secondList, new TodoListSettings(3, null, null), 0);
        assertThat(metadata.get(firstList)).isEqualTo(adjusted);
        assertThat(metadata.get(secondList).reminderMinutesBefore()).isEqualTo(3);
        assertThatThrownBy(() -> metadata.createFrom(MetadataKeys.todoListSettings(newListId()), MetadataKeys.todoListSettings(newListId())))
                .isInstanceOfSatisfying(AppException.class,
                        refused -> assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.METADATA_NOT_FOUND));
    }

    @Test
    @DisplayName("Without a token GET and PUT are 401; a signed-in non-admin is 403 ACCESS_DENIED and changes nothing")
    void onlyAdminsReachTheApi() throws Exception {
        TestUser user = newUser();
        String payload = updatePayload("""
                {"reminderMinutesBefore": 99}""", 0);

        getAs(null, DEFAULTS_PATH)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        putAs(null, DEFAULTS_PATH, payload)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        getAs(user.accessToken(), DEFAULTS_PATH)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        putAs(user.accessToken(), DEFAULTS_PATH, payload)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        getAs(user.accessToken(), METADATA + "NO_SUCH_CONFIG")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        assertThat(metadata.get(DEFAULTS)).isEqualTo(TodoListSettings.defaults());
    }

    @Test
    @DisplayName("Concurrent creates of one key store one row; each other is refused 409 METADATA_KEY_TAKEN without internals")
    void concurrentCreatesOfOneKeyStoreOne() throws Exception {
        MetadataKey<TodoListSettings> key = MetadataKeys.todoListSettings(newListId());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> pending = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int racer = 0; racer < RACERS; racer++) {
                Callable<String> create = () -> {
                    awaitLatch(start);
                    return createAnswer(key);
                };
                pending.add(executor.submit(create));
            }
            start.countDown();
            List<String> answers = new ArrayList<>();
            for (Future<String> answer : pending) {
                answers.add(within(answer));
            }

            assertThat(answers).containsOnlyOnce("created").containsOnly("created", "409 METADATA_KEY_TAKEN");
        }
        Integer rows = jdbc.queryForObject("select count(*) from configuration_metadata where key = ?", Integer.class, key.name());
        assertThat(rows).isEqualTo(1);
    }

    /** One racer's create, its refusal answered by the real error handler, as it is for a request that seeds. */
    private String createAnswer(MetadataKey<TodoListSettings> key) throws Exception {
        try {
            metadata.create(key, TodoListSettings.defaults());
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
    @DisplayName("A key is UPPER_SNAKE_CASE (with an optional canonical UUID) with a record value; byName resolves fixed keys only")
    void keysAreCheckedWhenDeclared() {
        assertThatThrownBy(() -> new MetadataKey<>("lower_case", TodoListSettings.class)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MetadataKey<>("NOT_A_RECORD", String.class)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetadataKeys.todoListSettings(null)).isInstanceOf(NullPointerException.class);
        assertThat(MetadataKeys.byName(FIXED_KEY)).contains(DEFAULTS);
        assertThat(MetadataKeys.byName(MetadataKeys.todoListSettings(newListId()).name())).isEmpty();
        assertThat(MetadataKeys.byName(UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    @DisplayName("delete removes the value in the caller's transaction: a rollback keeps it, a commit frees the key; nothing to delete is no error")
    void deleteRemovesTheValueInTheCallersTransaction() {
        MetadataKey<TodoListSettings> key = MetadataKeys.todoListSettings(newListId());
        metadata.create(key, new TodoListSettings(9, null, null));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            metadata.delete(key);
            status.setRollbackOnly();
        });
        assertThat(metadata.get(key).reminderMinutesBefore()).isEqualTo(9);

        transaction.executeWithoutResult(status -> metadata.delete(key));
        assertThatThrownBy(() -> metadata.get(key))
                .isInstanceOfSatisfying(AppException.class,
                        refused -> assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.METADATA_NOT_FOUND));
        metadata.delete(key);
        metadata.create(key, TodoListSettings.defaults());
        assertThat(metadata.get(key)).isEqualTo(TodoListSettings.defaults());
    }
}
