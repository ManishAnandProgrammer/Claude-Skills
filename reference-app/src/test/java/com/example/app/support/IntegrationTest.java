package com.example.app.support;

import com.example.app.TestcontainersConfiguration;
// media:begin
import com.example.app.media.api.UploadPurpose;
import com.example.app.user.domain.Role;
// media:end
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// media:begin
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
// media:end
import java.time.Duration;
import java.util.ArrayList;
// media:begin
import java.util.Arrays;
// media:end
import java.util.List;
// media:begin
import java.util.Map;
// media:end
import java.util.Objects;
// media:begin
import java.util.Optional;
// media:end
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
// media:begin
import static org.junit.jupiter.api.Assumptions.assumeTrue;
// media:end
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base of every integration test: the whole application over MockMvc, one shared context and one
 * shared database. Create your own data (unique emails and names), never assume an empty table,
 * and don't add {@code @MockitoBean} or {@code @TestPropertySource}: either starts another context.
 *
 * <p>{@code @AutoConfigureMetrics} keeps the prometheus registry, which Boot switches off in tests,
 * so the scrape is tested. Every request comes from its own client address
 * ({@link DistinctClientAddresses}).</p>
 *
 * <p>Fixture helpers assert their own success, so a setup that fails fails the test.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, StubBreachedPasswords.class, DistinctClientAddresses.class})
public abstract class IntegrationTest {

    protected static final String PASSWORD = "Correct-Horse-9";
    /** How many requests {@link #race} fires at once. */
    protected static final int RACERS = 6;
    /**
     * How many writes {@link #createsHeldAtTheirInsert} and {@link #writesHeldWhileTheParentWaits} hold at once:
     * below the pool size (10) with the parent's write, the gate and the polling connection.
     */
    protected static final int HELD_CREATES = 4;
    /**
     * Under lock_timeout (3 s), so a request held by a test lock never times out first. A test that
     * waits for several sessions in turn shares one deadline across the waits ({@link #lockWaitDeadline}).
     */
    private static final Duration LOCK_WAIT_DEADLINE = Duration.ofSeconds(2);
    /** How long a test waits for a latch or for a request it started, so a broken gate fails instead of hanging. */
    private static final Duration WAIT_DEADLINE = Duration.ofSeconds(10);

    @Autowired
    protected MockMvc mvc;

    @Value("${spring.application.name}")
    private String applicationName;

    /** For asserting database invariants (row counts) that no endpoint exposes. Writes go through {@link #sql}. */
    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Runs a fixture write in its own committed transaction. The pool runs with auto-commit off, so
     * a bare {@code jdbc.update} outside a transaction is rolled back when its connection returns to
     * the pool, and the test would silently run against data that isn't there.
     */
    protected int sql(String statement, Object... args) {
        Integer rows = new TransactionTemplate(transactionManager).execute(status -> jdbc.update(statement, args));
        return rows;
    }

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /** Hibernate's counters (on in the test profile): clear them, run a request, read the statement count. */
    protected Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    /** A signed-up user and their access token. */
    protected record TestUser(UUID id, String email, String accessToken, String refreshToken) {
    }

    /** The application's name, which bootstrap renames: never write it into a test. */
    protected String applicationName() {
        return applicationName;
    }

    /** The Problem Details {@code type} of an error code; the URN carries the application's name. */
    protected String problemType(String errorCode) {
        return "urn:%s:error:%s".formatted(applicationName, errorCode);
    }

    /** {@code count} random UUIDs, comma-separated: an {@code ids} filter of that size. */
    protected static String randomIds(int count) {
        return Stream.generate(UUID::randomUUID).limit(count).map(UUID::toString).collect(Collectors.joining(","));
    }

    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    /** Signs up a new account and signs it in. */
    protected TestUser newUser() throws Exception {
        String email = uniqueEmail("user");
        String userId = read(signup(email, PASSWORD).andExpect(status().isCreated()), "$.response.id");
        return signIn(new TestUser(UUID.fromString(userId), email, null, null));
    }

    /**
     * Signs an account in again: fresh tokens carrying its current role. A fixture kept for longer than
     * one test (an account shared by a class or the whole run) keeps the account and calls this on use,
     * never a cached access token: that expires after {@code app.jwt.access-token-ttl}, and every later
     * call would be 401 once a slow run passes it.
     */
    protected TestUser signIn(TestUser account) throws Exception {
        ResultActions login = login(account.email(), PASSWORD).andExpect(status().isOk());
        return new TestUser(account.id(), account.email(),
                read(login, "$.response.accessToken"), read(login, "$.response.refreshToken"));
    }

    // admin:begin
    /** A signed-up user given {@code role} (no endpoint grants one), signed in again so the token carries it. */
    protected TestUser newUserWithRole(String role) throws Exception {
        TestUser user = newUser();
        assertThat(sql("update users set role = ? where id = ?", role, user.id())).isEqualTo(1);
        return signIn(user);
    }

    protected TestUser newAdmin() throws Exception {
        return newUserWithRole("ADMIN");
    }
    // admin:end

    // media:begin
    private static final HttpClient STORAGE_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final String UPLOAD_URLS = "/api/v1/upload-urls";
    /** The content types {@link #setUploadPolicy} allows: those of the files the tests upload. */
    private static final String TEST_CONTENT_TYPES = "application/pdf,image/jpeg,image/png";

    /** Every test class that sets an upload policy holds this lock, so none sees another's limits. */
    public static final String UPLOAD_POLICIES_LOCK = "UPLOAD_POLICIES";

    /**
     * The purpose the media piece's own endpoint tests run against: the project's first
     * {@link UploadPurpose}, or empty while the project has none (those tests are then skipped, see
     * {@link #assumeAnUploadPurpose}). Each test sets the purpose's limits itself ({@link #setUploadPolicy}).
     */
    protected static final String TESTED_PURPOSE = UploadPurpose.values().length == 0 ? "" : UploadPurpose.values()[0].name();

    /** A role that may upload for {@link #TESTED_PURPOSE}: its first. */
    protected static final String TESTED_PURPOSE_ROLE = UploadPurpose.values().length == 0 ? "" : UploadPurpose.values()[0].roles().getFirst();

    /** A new user with the role that may upload for {@link #TESTED_PURPOSE}. */
    protected TestUser newUploader() throws Exception {
        return newUserWithRole(TESTED_PURPOSE_ROLE);
    }

    /** A new user with a role that may not upload for {@link #TESTED_PURPOSE}; skips the test when every role may. */
    protected TestUser newNonUploader() throws Exception {
        List<String> uploaders = UploadPurpose.values()[0].roles();
        Optional<String> otherRole = Arrays.stream(Role.values()).map(Role::name)
                .filter(role -> !uploaders.contains(role))
                .findFirst();
        assumeTrue(otherRole.isPresent(), "every role may upload for " + TESTED_PURPOSE);
        return newUserWithRole(otherRole.get());
    }

    /** Skips the calling test until the project declares its first upload purpose (crud-gen Step 5). */
    protected static void assumeAnUploadPurpose() {
        assumeTrue(!TESTED_PURPOSE.isEmpty(), "no UploadPurpose yet: the media endpoint tests run once the first one exists");
    }

    /** One upload URL as {@code POST /upload-urls} answers it. */
    protected record UploadUrl(String uploadKey, String url, Map<String, String> headers) {
    }

    /** Asks for one upload URL; fails the test unless it answers 201. */
    protected UploadUrl uploadUrl(TestUser user, String purpose, String fileName, String contentType, long sizeBytes)
            throws Exception {
        ResultActions issued = postAs(user.accessToken(), UPLOAD_URLS, """
                {"purpose": "%s", "files": [{"fileName": "%s", "contentType": "%s", "sizeBytes": %d}]}
                """.formatted(purpose, fileName, contentType, sizeBytes)).andExpect(status().isCreated());
        return new UploadUrl(read(issued, "$.response[0].uploadKey"), read(issued, "$.response[0].url"),
                read(issued, "$.response[0].headers"));
    }

    /** PUTs {@code body} to a presigned URL with {@code headers}, as a browser would; the store's status. */
    protected static int putToStorage(String url, Map<String, String> headers, byte[] body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        for (Map.Entry<String, String> header : headers.entrySet()) {
            request.header(header.getKey(), header.getValue());
        }
        return STORAGE_CLIENT.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** GETs a presigned URL, as a browser would. */
    protected static HttpResponse<byte[]> getFromStorage(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
        return STORAGE_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    /** Uploads {@code body} as {@code user} for {@code purpose}; returns the upload key, ready to attach. */
    protected String uploadedFile(TestUser user, String purpose, String fileName, String contentType, byte[] body)
            throws Exception {
        UploadUrl upload = uploadUrl(user, purpose, fileName, contentType, body.length);
        assertThat(putToStorage(upload.url(), upload.headers(), body)).as("upload of " + fileName).isEqualTo(200);
        return upload.uploadKey();
    }

    /** A purpose's limits as stored, to put back after a test changed them. */
    protected record UploadPolicyLimits(String purpose, long minSizeBytes, long maxSizeBytes, int minFiles, int maxFiles,
                                        String contentTypes) {
    }

    /** The purpose's limits now ({@link #restoreUploadPolicy} puts them back). */
    protected UploadPolicyLimits uploadPolicyOf(String purpose) {
        return jdbc.queryForObject("""
                select min_size_bytes, max_size_bytes, min_files, max_files, array_to_string(content_types, ',') as content_types
                  from upload_policies
                 where purpose = ?
                """, (row, number) -> new UploadPolicyLimits(purpose, row.getLong("min_size_bytes"), row.getLong("max_size_bytes"),
                row.getInt("min_files"), row.getInt("max_files"), row.getString("content_types")), purpose);
    }

    /**
     * Puts a purpose's limits back as {@link #uploadPolicyOf} read them, so no other class sees a test's
     * limits (every todo create checks the attachment count, even with no files). Nothing when
     * {@code limits} is null: the test was skipped before it read them.
     */
    protected void restoreUploadPolicy(UploadPolicyLimits limits) {
        if (Objects.isNull(limits)) {
            return;
        }
        assertThat(sql("""
                update upload_policies
                   set min_size_bytes = ?, max_size_bytes = ?, min_files = ?, max_files = ?,
                       content_types = string_to_array(?, ',')
                 where purpose = ?
                """, limits.minSizeBytes(), limits.maxSizeBytes(), limits.minFiles(), limits.maxFiles(),
                limits.contentTypes(), limits.purpose())).isEqualTo(1);
    }

    /**
     * Sets a purpose's limits directly (no admin, no version check), with the content types of the test
     * files (PDF, JPEG, PNG), for tests of how they are applied. A class that relies on a purpose's limits
     * holds {@link #UPLOAD_POLICIES_LOCK}, reads them ({@link #uploadPolicyOf}) and sets its own in
     * {@code @BeforeEach}, and puts them back ({@link #restoreUploadPolicy}) in {@code @AfterEach}.
     */
    protected void setUploadPolicy(String purpose, long minSizeBytes, long maxSizeBytes, int minFiles, int maxFiles) {
        assertThat(sql("""
                update upload_policies
                   set min_size_bytes = ?, max_size_bytes = ?, min_files = ?, max_files = ?,
                       content_types = string_to_array(?, ',')
                 where purpose = ?
                """, minSizeBytes, maxSizeBytes, minFiles, maxFiles, TEST_CONTENT_TYPES, purpose)).isEqualTo(1);
    }

    // media:end
    // metadata:begin
    /** A fixed metadata key's row as stored: its JSON value and version, to put back after a test changed it. */
    protected record StoredMetadata(String key, String value, long version) {
    }

    /**
     * The key's row now ({@link #restoreMetadata} puts it back). Fails when the key isn't stored: a fixed
     * key is seeded by the changeset that needs it (META-05), so a missing one is a seed that didn't run.
     */
    protected StoredMetadata storedMetadata(String key) {
        return jdbc.queryForObject("select value::text as value, version from configuration_metadata where key = ?",
                (row, number) -> new StoredMetadata(key, row.getString("value"), row.getLong("version")), key);
    }

    /**
     * Sets a fixed key's value directly, at version 0 (no admin, no version check), creating the row when a
     * test deleted it. A class that changes a fixed key holds the key's {@code @ResourceLock}, reads it
     * ({@link #storedMetadata}) and sets its own in {@code @BeforeEach}, and puts it back
     * ({@link #restoreMetadata}) in {@code @AfterEach}: real code reads the key (a create that starts from
     * it), and other classes must find the seeded value, never a test's.
     */
    protected void storeMetadata(String key, String json) {
        storeMetadataRow(key, json, 0);
    }

    /** Puts a key's row back as {@link #storedMetadata} read it, value and version. */
    protected void restoreMetadata(StoredMetadata stored) {
        storeMetadataRow(stored.key(), stored.value(), stored.version());
    }

    private void storeMetadataRow(String key, String json, long version) {
        assertThat(sql("""
                insert into configuration_metadata (id, key, value, created_at, updated_at, version)
                values (uuidv7(), ?, ?::jsonb, now(), now(), ?)
                on conflict on constraint uq_configuration_metadata_key
                do update set value = excluded.value, version = excluded.version, updated_at = now()
                """, key, json, version)).isEqualTo(1);
    }
    // metadata:end
    protected ResultActions signup(String email, String password) throws Exception {
        return postAs(null, "/api/v1/auth/signup", """
                {"email": "%s", "fullName": "Test User", "password": "%s"}
                """.formatted(email, password));
    }

    protected ResultActions login(String email, String password) throws Exception {
        return postAs(null, "/api/v1/auth/login", """
                {"email": "%s", "password": "%s"}
                """.formatted(email, password));
    }

    protected ResultActions getAs(String token, String path) throws Exception {
        return mvc.perform(authorized(get(path), token));
    }

    protected ResultActions postAs(String token, String path, String json) throws Exception {
        return mvc.perform(withJson(authorized(post(path), token), json));
    }

    protected ResultActions putAs(String token, String path, String json) throws Exception {
        return mvc.perform(withJson(authorized(put(path), token), json));
    }

    protected ResultActions patchAs(String token, String path, String json) throws Exception {
        return mvc.perform(withJson(authorized(patch(path), token), json));
    }

    protected ResultActions deleteAs(String token, String path) throws Exception {
        return mvc.perform(authorized(delete(path), token));
    }

    /** One answer of a raced request: its status and body, so every refusal's body can be checked. */
    public record Answer(int status, String body) {

        public String errorCode() {
            return JsonPath.read(body, "$.errorCode");
        }
    }

    protected static Answer answerOf(ResultActions result) throws Exception {
        MockHttpServletResponse response = result.andReturn().getResponse();
        return new Answer(response.getStatus(), response.getContentAsString());
    }

    /** A thread per task, for a test's own concurrent work (a request or a transaction held meanwhile). */
    protected ExecutorService newExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** Fires {@code request} from {@link #RACERS} threads released together; returns every answer. */
    protected static List<Answer> race(Callable<ResultActions> request) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Answer>> pending = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int racer = 0; racer < RACERS; racer++) {
                Callable<Answer> once = () -> {
                    awaitLatch(start);
                    return answerOf(request.call());
                };
                pending.add(executor.submit(once));
            }
            start.countDown();
            List<Answer> answers = new ArrayList<>();
            for (Future<Answer> answer : pending) {
                answers.add(within(answer));
            }
            return answers;
        }
    }

    /**
     * Fires {@code create} {@link #HELD_CREATES} times, holds each one at its insert into {@code table}
     * while {@code meanwhile} runs, then lets them go; returns the creates' answers. The hold is a SHARE
     * lock on the table, which reads and foreign key checks pass: a parent's delete run as
     * {@code meanwhile} lands between each create's read of the parent and its insert.
     */
    protected List<Answer> createsHeldAtTheirInsert(String table, Callable<ResultActions> create, Callable<ResultActions> meanwhile)
            throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch gateClosed = new CountDownLatch(1);
        CountDownLatch openGate = new CountDownLatch(1);
        List<Future<Answer>> pending = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> gate = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.execute("lock table " + table + " in share mode");
                gateClosed.countDown();
                awaitQuietly(openGate);
            }));
            awaitLatch(gateClosed);
            try {
                for (int creator = 0; creator < HELD_CREATES; creator++) {
                    Callable<Answer> once = () -> answerOf(create.call());
                    pending.add(executor.submit(once));
                }
                awaitLockWaiters(HELD_CREATES, null);
                meanwhile.call();
            } finally {
                openGate.countDown();
            }
            within(gate);
        }
        List<Answer> answers = new ArrayList<>();
        for (Future<Answer> answer : pending) {
            answers.add(within(answer));
        }
        return answers;
    }

    /** The answers of {@link #writesHeldWhileTheParentWaits}: each held write's, then the parent's write's. */
    protected record HeldWrites(List<Answer> writes, Answer parentWrite) {
    }

    /**
     * For a write that locks its parent first (a locked facade view, DATA-29): runs each of {@code writes}
     * at once and holds it at its insert into {@code table} (a SHARE lock on the table, as
     * {@link #createsHeldAtTheirInsert}), by when it holds the parent's lock. Then submits
     * {@code parentWrite} (the parent's delete or state change) on its own thread and asserts that it
     * <b>waits</b> while the gate is shut: that wait is what the parent's lock buys, and without the lock
     * the parent's write would finish under the gate and fail this assertion (TEST-09). Then opens the gate
     * and returns every answer. The writes queue on the parent's lock in no fixed order, and the parent's
     * write may get it between two of them: assert the set of outcomes, never their order.
     */
    protected HeldWrites writesHeldWhileTheParentWaits(String table, List<Callable<ResultActions>> writes,
                                                       Callable<ResultActions> parentWrite) throws Exception {
        assertThat(writes).as("held writes, within the pool").hasSizeBetween(1, HELD_CREATES);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch gateClosed = new CountDownLatch(1);
        CountDownLatch openGate = new CountDownLatch(1);
        List<Future<Answer>> pending = new ArrayList<>();
        Future<Answer> parent;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> gate = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.execute("lock table " + table + " in share mode");
                gateClosed.countDown();
                awaitQuietly(openGate);
            }));
            awaitLatch(gateClosed);
            try {
                long deadline = lockWaitDeadline();
                for (Callable<ResultActions> write : writes) {
                    Callable<Answer> once = () -> answerOf(write.call());
                    pending.add(executor.submit(once));
                }
                // One write holds the parent's lock and waits at its insert; the others wait for the parent.
                awaitLockWaiters(writes.size(), null, deadline);
                Callable<Answer> parentOnce = () -> answerOf(parentWrite.call());
                parent = executor.submit(parentOnce);
                awaitLockWaiters(writes.size() + 1, parent, deadline);
                assertThat(parent.isDone()).as("the parent's write waits for the held writes' lock on the parent").isFalse();
            } finally {
                openGate.countDown();
            }
            within(gate);
        }
        List<Answer> answers = new ArrayList<>();
        for (Future<Answer> answer : pending) {
            answers.add(within(answer));
        }
        return new HeldWrites(answers, within(parent));
    }

    /** A refusal names no constraint (of any kind), SQL or Java internals anywhere in its body. */
    protected static void assertNoInternals(String body) {
        assertThat(body).doesNotContain("uq_", "fk_", "ex_", "ck_", "pk_", "SQL", "Exception", "java.");
    }

    /** Every answer with {@code status} carries {@code errorCode} and no internals: races reach other code paths. */
    protected static void assertRefusalsAre(List<Answer> answers, int status, String errorCode) {
        for (Answer answer : answers) {
            if (answer.status() == status) {
                assertThat(answer.errorCode()).isEqualTo(errorCode);
                assertNoInternals(answer.body());
            }
        }
    }

    /**
     * Waits until {@code sessions} sessions of this database wait on a lock, or {@code done} has
     * finished; fails past {@link #LOCK_WAIT_DEADLINE}. Race tests use it to know a request reached
     * the lock they gate. It counts every waiting session of the database, which is exact because test
     * classes run one at a time (no JUnit parallel execution is configured); running them in parallel
     * would need the count narrowed to the sessions the gate blocks ({@code pg_blocking_pids}).
     */
    protected void awaitLockWaiters(int sessions, Future<?> done) throws InterruptedException {
        awaitLockWaiters(sessions, done, lockWaitDeadline());
    }

    /** A deadline for {@link #awaitLockWaiters(int, Future, long)}: one for every wait of one gated sequence. */
    protected static long lockWaitDeadline() {
        return System.nanoTime() + LOCK_WAIT_DEADLINE.toNanos();
    }

    /** As {@link #awaitLockWaiters(int, Future)}, against a deadline shared with the test's earlier waits. */
    protected void awaitLockWaiters(int sessions, Future<?> done, long deadline) throws InterruptedException {
        while (lockWaiters() < sessions && (Objects.isNull(done) || !done.isDone())) {
            assertThat(System.nanoTime()).as("sessions waiting on a lock").isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    private int lockWaiters() {
        return jdbc.queryForObject("""
                select count(*) from pg_stat_activity
                where datname = current_database() and wait_event_type = 'Lock'
                """, Integer.class);
    }

    /** Waits for {@code latch}; fails past {@link #WAIT_DEADLINE} instead of hanging the build. */
    protected static void awaitLatch(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(WAIT_DEADLINE.toMillis(), TimeUnit.MILLISECONDS)).as("latch released in time").isTrue();
    }

    /** The result of a request the test started; fails past {@link #WAIT_DEADLINE} instead of hanging the build. */
    protected static <T> T within(Future<T> future) throws Exception {
        return future.get(WAIT_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * For a gate held inside a transaction callback, which can't throw InterruptedException. It gives up
     * after {@link #WAIT_DEADLINE}, so a test that failed before opening the gate doesn't keep it shut.
     */
    protected static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(WAIT_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    protected static <T> T read(ResultActions result, String jsonPath) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), jsonPath);
    }

    private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, String token) {
        if (Objects.nonNull(token)) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return request;
    }

    private static MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder request, String json) {
        return request.contentType(MediaType.APPLICATION_JSON).content(json);
    }
}
