package com.example.app.support;

import com.example.app.TestcontainersConfiguration;
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
import java.util.List;
// media:begin
import java.util.Map;
// media:end
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
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
    private static final int RACERS = 6;
    /** Under lock_timeout (3 s), so a request held by a test lock never times out first. */
    private static final Duration LOCK_WAIT_DEADLINE = Duration.ofSeconds(2);

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
        ResultActions login = login(email, PASSWORD).andExpect(status().isOk());
        return new TestUser(UUID.fromString(userId), email,
                read(login, "$.response.accessToken"), read(login, "$.response.refreshToken"));
    }

    // admin:begin
    /** A signed-up user promoted to ADMIN (no endpoint grants it), signed in again so the token carries the role. */
    protected TestUser newAdmin() throws Exception {
        TestUser user = newUser();
        assertThat(sql("update users set role = 'ADMIN' where id = ?", user.id())).isEqualTo(1);
        ResultActions login = login(user.email(), PASSWORD).andExpect(status().isOk());
        return new TestUser(user.id(), user.email(),
                read(login, "$.response.accessToken"), read(login, "$.response.refreshToken"));
    }
    // admin:end

    // media:begin
    private static final HttpClient STORAGE_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final String UPLOAD_URLS = "/api/v1/upload-urls";

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

    /** Sets a purpose's limits directly (no admin, no version check), for tests of how they are applied. */
    protected void setUploadPolicy(String purpose, long minSizeBytes, long maxSizeBytes, int minFiles, int maxFiles) {
        assertThat(sql("""
                update upload_policies set min_size_bytes = ?, max_size_bytes = ?, min_files = ?, max_files = ?
                where purpose = ?
                """, minSizeBytes, maxSizeBytes, minFiles, maxFiles, purpose)).isEqualTo(1);
    }

    // media:end
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

    /** Fires {@code request} from {@link #RACERS} threads released together; returns every answer. */
    protected static List<Answer> race(Callable<ResultActions> request) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Answer>> pending = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int racer = 0; racer < RACERS; racer++) {
                Callable<Answer> once = () -> {
                    start.await();
                    return answerOf(request.call());
                };
                pending.add(executor.submit(once));
            }
            start.countDown();
            List<Answer> answers = new ArrayList<>();
            for (Future<Answer> answer : pending) {
                answers.add(answer.get());
            }
            return answers;
        }
    }

    /** A refusal names no constraint, SQL or Java internals anywhere in its body. */
    protected static void assertNoInternals(String body) {
        assertThat(body).doesNotContain("uq_", "fk_", "SQL", "Exception", "java.");
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
     * the lock they gate.
     */
    protected void awaitLockWaiters(int sessions, Future<?> done) throws InterruptedException {
        long deadline = System.nanoTime() + LOCK_WAIT_DEADLINE.toNanos();
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

    /** For a gate held inside a transaction callback, which can't throw InterruptedException. */
    protected static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
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
