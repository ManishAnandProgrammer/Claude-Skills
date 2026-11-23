package com.example.app.support;

import com.example.app.TestcontainersConfiguration;
import com.example.app.common.security.Permission;
import com.example.app.common.tenancy.TenantScope;
import com.example.app.tenant.api.TenantStatus;
import com.example.app.tenant.dto.request.TenantCreateRequest;
import com.example.app.tenant.service.TenantService;
import com.example.app.user.domain.Member;
import com.example.app.user.repository.MemberRepository;
// media:begin
import com.example.app.media.api.UploadPurpose;
import com.example.app.user.domain.Role;
// media:end
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseDataSource;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
// media:begin
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
// media:end
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
// media:begin
import java.util.Arrays;
// media:end
import java.util.List;
import java.util.Map;
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
 * One shared context and one shared database for the whole run: create your own data (unique values), never
 * assume an empty table, and don't add {@code @MockitoBean} or {@code @TestPropertySource}: either starts another
 * context. {@code @AutoConfigureMetrics} keeps the prometheus registry, which Boot switches off in tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, StubBreachedPasswords.class, DistinctClientAddresses.class,
        SupportAccessPolicies.class, TenantProvisioningForTests.class, PreviousReleaseDatabase.class,
        TransactionGuards.class})
public abstract class IntegrationTest {

    protected static final String PASSWORD = "Correct-Horse-9";
    protected static final int RACERS = 6;
    /** Below the pool size (10) with the parent's write, the gate and the polling connection. */
    protected static final int HELD_CREATES = 4;
    /** Under lock_timeout (3 s), so a request held by a test lock never times out first. */
    private static final Duration LOCK_WAIT_DEADLINE = Duration.ofSeconds(2);
    private static final Duration WAIT_DEADLINE = Duration.ofSeconds(10);

    // example:begin
    // metadata:begin
    /**
     * Spliced into fixture inserts ({@code "insert into todos (id, %stitle ..."}) so a fixture reads the same
     * without the metadata piece, which brings the display number (NOT NULL, unique): random, far above any the
     * counter hands out.
     */
    protected static final String TODO_NUMBER_COLUMN = "number, ";
    protected static final String TODO_NUMBER = "1000000000 + (random() * 1e15)::bigint, ";
    // metadata:else
    // protected static final String TODO_NUMBER_COLUMN = "";
    // protected static final String TODO_NUMBER = "";
    // metadata:end
    // example:end

    @Autowired
    protected MockMvc mvc;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Value("${spring.application.name}")
    private String applicationName;

    /** It sees the test tenant's tables, then the platform's. */
    protected JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantProvisioningForTests provisioning;

    @Autowired
    private MemberRepository members;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static TestTenant testTenant;

    private static final String STAFF_EMAIL = uniqueEmail("staff");
    /** Well within the access token's 15 minutes. */
    private static final Duration STAFF_TOKEN_REUSE = Duration.ofMinutes(5);
    private static String staffToken;
    private static Instant staffTokenIssuedAt;

    @RegisterExtension
    final TestTenantScope testTenantScope = new TestTenantScope(() -> testTenant().id());

    private DataSource pool;

    /** The pool's role only reads staff accounts (0015). Unpooled and auto-commit: each statement commits on its own. */
    @Autowired
    @LiquibaseDataSource
    private DataSource ownerDataSource;

    @Autowired
    void fixtureJdbc(DataSource dataSource) {
        this.pool = dataSource;
        jdbc = new JdbcTemplate(new TenantFixtureDataSource(dataSource, () -> testTenant().id()));
    }

    /**
     * Commits in its own transaction. The pool runs with auto-commit off, so a bare {@code jdbc.update} outside a
     * transaction is rolled back when its connection returns to the pool, and the test would silently run against
     * data that isn't there.
     */
    protected int sql(String statement, Object... args) {
        // The test tenant first: created while this transaction opens its connection, the tenant's
        // after-commit provisioning (row mode) would never run, and it would stay PENDING.
        testTenant();
        Integer rows = new TransactionTemplate(transactionManager).execute(status -> jdbc.update(statement, args));
        return rows;
    }

    /** A row-mode insert still names its tenant itself: tenant_id has no default. */
    protected int sqlIn(TestTenant tenant, String statement, Object... args) {
        JdbcTemplate inTenant = new JdbcTemplate(new TenantFixtureDataSource(pool, tenant::id));
        Integer rows = new TransactionTemplate(transactionManager).execute(status -> inTenant.update(statement, args));
        return rows;
    }

    protected List<Map<String, Object>> queryIn(TestTenant tenant, String query, Object... args) {
        JdbcTemplate inTenant = new JdbcTemplate(new TenantFixtureDataSource(pool, tenant::id));
        return new TransactionTemplate(transactionManager).execute(status -> inTenant.queryForList(query, args));
    }

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    protected Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @BeforeEach
    void forgetEarlierTransactionChecks() {
        TransactionGuards.takeViolations();
    }

    @AfterEach
    void transactionBoundariesHeld() {
        assertThat(TransactionGuards.takeViolations()).as("transaction boundaries the test's requests and calls broke").isEmpty();
    }

    protected record TestUser(UUID id, String email, String accessToken, String refreshToken) {
    }

    protected record TestTenant(UUID id) {
    }

    /** Bootstrap renames the application: never write its name into a test. */
    protected String applicationName() {
        return applicationName;
    }

    protected String problemType(String errorCode) {
        return "urn:%s:error:%s".formatted(applicationName, errorCode);
    }

    protected static String randomIds(int count) {
        return Stream.generate(UUID::randomUUID).limit(count).map(UUID::toString).collect(Collectors.joining(","));
    }

    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    protected TestUser newUser() throws Exception {
        String email = uniqueEmail("user");
        String userId = read(signup(email, PASSWORD).andExpect(status().isCreated()), "$.response.id");
        return signIn(new TestUser(UUID.fromString(userId), email, null, null));
    }

    /**
     * A fixture kept for longer than one test keeps the account and calls this on use, never a cached access
     * token: that expires after {@code app.jwt.access-token-ttl}, and every later call would be 401 once a slow
     * run passes it.
     */
    protected TestUser signIn(TestUser account) throws Exception {
        ResultActions login = login(account.email(), PASSWORD).andExpect(status().isOk());
        return new TestUser(account.id(), account.email(),
                read(login, "$.response.accessToken"), read(login, "$.response.refreshToken"));
    }

    // admin:begin
    // roles:begin
    /** By SQL, so a test of anything else needs no admin to give the role. */
    protected TestUser newUserWithRole(String role) throws Exception {
        TestUser user = newUser();
        sql("delete from user_roles where user_id = ?", user.id());
        giveBuiltInRole(testTenant(), user.id(), role);
        return signIn(user);
    }
    // roles:else
    // /** By SQL: no endpoint grants a role. */
    // protected TestUser newUserWithRole(String role) throws Exception {
    //     TestUser user = newUser();
    //     assertThat(sql("update users set role = ? where id = ?", role, user.id())).isEqualTo(1);
    //     // The token's role is the membership's.
    //     assertThat(sql("update tenant_memberships set role = ? where user_id = ?", role, user.id())).isEqualTo(1);
    //     return signIn(user);
    // }
    // roles:end

    protected TestUser newAdmin() throws Exception {
        return newUserWithRole("ADMIN");
    }
    // admin:end

    // media:begin
    private static final HttpClient STORAGE_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final String UPLOAD_URLS = "/api/v1/upload-urls";
    private static final String TEST_CONTENT_TYPES = "application/pdf,image/jpeg,image/png";

    public static final String UPLOAD_POLICIES_LOCK = "UPLOAD_POLICIES";

    protected static final String TESTED_PURPOSE = UploadPurpose.values().length == 0 ? "" : UploadPurpose.values()[0].name();

    protected TestUser newUploader() throws Exception {
        Optional<Role> uploader = Arrays.stream(Role.values()).filter(IntegrationTest::mayUploadForTheTestedPurpose).findFirst();
        assertThat(uploader).as("a role that may upload for " + TESTED_PURPOSE).isPresent();
        return newUserWithRole(uploader.get().name());
    }

    protected TestUser newNonUploader() throws Exception {
        Optional<Role> other = Arrays.stream(Role.values()).filter(role -> !mayUploadForTheTestedPurpose(role)).findFirst();
        assumeTrue(other.isPresent(), "every role may upload for " + TESTED_PURPOSE);
        return newUserWithRole(other.get().name());
    }

    private static boolean mayUploadForTheTestedPurpose(Role role) {
        return UploadPurpose.values()[0].mayUpload(role::grants);
    }

    protected static void assumeAnUploadPurpose() {
        assumeTrue(!TESTED_PURPOSE.isEmpty(), "no UploadPurpose yet: the media endpoint tests run once the first one exists");
    }

    protected record UploadUrl(String uploadKey, String url, Map<String, String> headers) {
    }

    protected UploadUrl uploadUrl(TestUser user, String purpose, String fileName, String contentType, long sizeBytes)
            throws Exception {
        ResultActions issued = postAs(user.accessToken(), UPLOAD_URLS, """
                {"purpose": "%s", "files": [{"fileName": "%s", "contentType": "%s", "sizeBytes": %d}]}
                """.formatted(purpose, fileName, contentType, sizeBytes)).andExpect(status().isCreated());
        return new UploadUrl(read(issued, "$.response[0].uploadKey"), read(issued, "$.response[0].url"),
                read(issued, "$.response[0].headers"));
    }

    protected static int putToStorage(String url, Map<String, String> headers, byte[] body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        for (Map.Entry<String, String> header : headers.entrySet()) {
            request.header(header.getKey(), header.getValue());
        }
        return STORAGE_CLIENT.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    protected static HttpResponse<byte[]> getFromStorage(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
        return STORAGE_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    protected String uploadedFile(TestUser user, String purpose, String fileName, String contentType, byte[] body)
            throws Exception {
        UploadUrl upload = uploadUrl(user, purpose, fileName, contentType, body.length);
        assertThat(putToStorage(upload.url(), upload.headers(), body)).as("upload of " + fileName).isEqualTo(200);
        return upload.uploadKey();
    }

    protected record UploadPolicyLimits(String purpose, long minSizeBytes, long maxSizeBytes, int minFiles, int maxFiles,
                                        String contentTypes) {
    }

    protected UploadPolicyLimits uploadPolicyOf(String purpose) {
        return jdbc.queryForObject("""
                select min_size_bytes, max_size_bytes, min_files, max_files, array_to_string(content_types, ',') as content_types
                  from upload_policies
                 where purpose = ?
                """, (row, number) -> new UploadPolicyLimits(purpose, row.getLong("min_size_bytes"), row.getLong("max_size_bytes"),
                row.getInt("min_files"), row.getInt("max_files"), row.getString("content_types")), purpose);
    }

    /**
     * Every todo create checks the attachment count, even with no files, so a test's limits left behind break
     * other classes. {@code limits} is null when the test was skipped before it read them.
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
    protected record StoredMetadata(String key, String value, long version) {
    }

    /** A fixed key is seeded by the changeset that needs it, so a key that isn't stored is a seed that didn't run. */
    protected StoredMetadata storedMetadata(String key) {
        return jdbc.queryForObject("select value::text as value, version from configuration_metadata where key = ?",
                (row, number) -> new StoredMetadata(key, row.getString("value"), row.getLong("version")), key);
    }

    protected void storeMetadata(String key, String json) {
        storeMetadataRow(key, json, 0);
    }

    protected void restoreMetadata(StoredMetadata stored) {
        storeMetadataRow(stored.key(), stored.value(), stored.version());
    }

    private void storeMetadataRow(String key, String json, long version) {
        // tenancy-row:begin
        assertThat(sql("""
                insert into configuration_metadata (id, tenant_id, key, value, created_at, updated_at, version)
                values (uuidv7(), ?, ?, ?::jsonb, now(), now(), ?)
                on conflict on constraint uq_configuration_metadata_key
                do update set value = excluded.value, version = excluded.version, updated_at = now()
                """, testTenant().id(), key, json, version)).isEqualTo(1);
        // tenancy-row:else
        // assertThat(sql("""
        //         insert into configuration_metadata (id, key, value, created_at, updated_at, version)
        //         values (uuidv7(), ?, ?::jsonb, now(), now(), ?)
        //         on conflict on constraint uq_configuration_metadata_key
        //         do update set value = excluded.value, version = excluded.version, updated_at = now()
        //         """, key, json, version)).isEqualTo(1);
        // tenancy-row:end
    }
    // metadata:end

    /**
     * Before any test body, {@code @WithoutTestTenant} classes included: the fixture {@code jdbc} names the test
     * tenant on every connection.
     */
    @BeforeEach
    void createTheTestTenantFirst() {
        testTenant();
    }

    protected TestTenant testTenant() {
        synchronized (IntegrationTest.class) {
            if (Objects.isNull(testTenant)) {
                testTenant = newTenantOrFail();
            }
            return testTenant;
        }
    }

    /** Its owner, a new account, is its first membership, an ADMIN. */
    protected TestTenant newTenant() throws Exception {
        String ownerEmail = uniqueEmail("owner");
        signupWithoutATenant(ownerEmail, PASSWORD).andExpect(status().isCreated());
        return createTenant(ownerEmail);
    }

    protected TestUser newUserIn(TestTenant tenant) throws Exception {
        return newUserIn(tenant, "USER");
    }

    protected TestUser newUserIn(TestTenant tenant, String role) throws Exception {
        String email = uniqueEmail("user");
        String userId = read(signupWithoutATenant(email, PASSWORD).andExpect(status().isCreated()), "$.response.id");
        addMember(tenant, UUID.fromString(userId), role);
        return signIn(new TestUser(UUID.fromString(userId), email, null, null));
    }

    /** Reused for a while: the login's per-account rate limit would refuse a sign-in per call. */
    protected String platformToken() throws Exception {
        synchronized (IntegrationTest.class) {
            if (Objects.isNull(staffToken) || staffTokenIssuedAt.plus(STAFF_TOKEN_REUSE).isBefore(Instant.now())) {
                staffToken = platformLogin();
                staffTokenIssuedAt = Instant.now();
            }
            return staffToken;
        }
    }

    /** A test's own staff member, for a test that deactivates one. */
    protected record TestStaff(UUID id, String email, String accessToken) {
    }

    protected TestStaff newStaff() throws Exception {
        String email = uniqueEmail("staff");
        new JdbcTemplate(ownerDataSource).update("""
                insert into platform_users (id, email, full_name, password_hash, role, created_at, updated_at, version)
                values (gen_random_uuid(), ?, 'Test Staff', ?, 'STAFF', now(), now(), 0)
                """, email, passwordEncoder.encode(PASSWORD));
        UUID id = jdbc.queryForObject("select id from platform_users where email = ?", UUID.class, email);
        ResultActions login = postAs(null, "/api/v1/platform/auth/login", """
                {"email": "%s", "password": "%s"}
                """.formatted(email, PASSWORD)).andExpect(status().isOk());
        return new TestStaff(id, email, read(login, "$.response.accessToken"));
    }

    protected void deactivateStaff(UUID staffId) {
        assertThat(new JdbcTemplate(ownerDataSource).update("update platform_users set deactivated_at = now() where id = ?", staffId))
                .isEqualTo(1);
    }

    /** Both rows, as {@code MembershipService} writes them: the tenant's own tables reference the person's member row. */
    protected void addMember(TestTenant tenant, UUID userId, String role) {
        // roles:begin
        assertThat(sql("""
                insert into tenant_memberships (id, tenant_id, user_id, created_at, updated_at, version)
                values (uuidv7(), ?, ?, now(), now(), 0)
                """, tenant.id(), userId)).isEqualTo(1);
        TenantScope.runAs(tenant.id(), () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> members.save(Member.of(userId))));
        giveBuiltInRole(tenant, userId, role);
        // roles:else
        // assertThat(sql("""
        //         insert into tenant_memberships (id, tenant_id, user_id, role, created_at, updated_at, version)
        //         values (gen_random_uuid(), ?, ?, ?, now(), now(), 0)
        //         """, tenant.id(), userId, role)).isEqualTo(1);
        // TenantScope.runAs(tenant.id(), () -> new TransactionTemplate(transactionManager)
        //         .executeWithoutResult(status -> members.save(Member.of(userId))));
        // roles:end
    }
    // roles:begin

    protected static TestTenant tenant(UUID id) {
        return new TestTenant(id);
    }

    protected void giveBuiltInRole(TestTenant tenant, UUID userId, String role) {
        // tenancy-row:begin
        assertThat(sqlIn(tenant, """
                insert into user_roles (id, tenant_id, user_id, role_id, created_at, updated_at, version)
                select uuidv7(), tenant_id, ?, id, now(), now(), 0 from roles where built_in = ?""", userId, role)).isEqualTo(1);
        // tenancy-row:end
        // tenancy-schema:begin
        assertThat(sqlIn(tenant, """
                insert into user_roles (id, user_id, role_id, created_at, updated_at, version)
                select uuidv7(), ?, id, now(), now(), 0 from roles where built_in = ?""", userId, role)).isEqualTo(1);
        // tenancy-schema:end
    }
    // roles:end

    protected void provisionApprovedTenants() {
        provisioning.provisionNow();
    }

    protected ResultActions signupWithoutATenant(String email, String password) throws Exception {
        return postAs(null, "/api/v1/auth/signup", """
                {"email": "%s", "fullName": "Test User", "password": "%s"}
                """.formatted(email, password));
    }

    /** A created account also joins the test tenant, as a plain member. */
    protected ResultActions signup(String email, String password) throws Exception {
        ResultActions result = signupWithoutATenant(email, password);
        if (result.andReturn().getResponse().getStatus() == HttpStatus.CREATED.value()) {
            addMember(testTenant(), UUID.fromString(read(result, "$.response.id")), "USER");
        }
        return result;
    }

    private TestTenant newTenantOrFail() {
        try {
            return newTenant();
        } catch (Exception failure) {
            throw new IllegalStateException("Could not create the test tenant", failure);
        }
    }

    private TestTenant createTenant(String ownerEmail) {
        UUID created = tenantService.create(new TenantCreateRequest("Tenant " + UUID.randomUUID(), ownerEmail));
        provisionApprovedTenants();
        assertThat(tenantService.getTenant(created).getStatus()).as("the new tenant's status").isEqualTo(TenantStatus.ACTIVE);
        return new TestTenant(created);
    }

    private String platformLogin() throws Exception {
        Integer existing = jdbc.queryForObject("select count(*) from platform_users where email = ?", Integer.class, STAFF_EMAIL);
        if (existing == 0) {
            new JdbcTemplate(ownerDataSource).update("""
                    insert into platform_users (id, email, full_name, password_hash, role, created_at, updated_at, version)
                    values (gen_random_uuid(), ?, 'Test Staff', ?, 'STAFF', now(), now(), 0)
                    on conflict (email) do nothing
                    """, STAFF_EMAIL, passwordEncoder.encode(PASSWORD));
        }
        ResultActions login = postAs(null, "/api/v1/platform/auth/login", """
                {"email": "%s", "password": "%s"}
                """.formatted(STAFF_EMAIL, PASSWORD)).andExpect(status().isOk());
        return read(login, "$.response.accessToken");
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

    public record Answer(int status, String body) {

        public String errorCode() {
            return JsonPath.read(body, "$.errorCode");
        }
    }

    protected static Answer answerOf(ResultActions result) throws Exception {
        MockHttpServletResponse response = result.andReturn().getResponse();
        return new Answer(response.getStatus(), response.getContentAsString());
    }

    protected ExecutorService newExecutor() {
        UUID tenantId = testTenant().id();
        return Executors.newThreadPerTaskExecutor(task -> Thread.ofVirtual().unstarted(() -> TenantScope.runAs(tenantId, task)));
    }

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
                answers.add(awaitResult(answer));
            }
            return answers;
        }
    }

    /**
     * The hold is a SHARE lock on {@code table}, which reads and foreign key checks pass: a parent's delete run as
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
            awaitResult(gate);
        }
        List<Answer> answers = new ArrayList<>();
        for (Future<Answer> answer : pending) {
            answers.add(awaitResult(answer));
        }
        return answers;
    }

    protected record HeldWrites(List<Answer> writes, Answer parentWrite) {
    }

    /**
     * For a write that locks its parent first: each of {@code writes} is held at its insert into {@code table} (a
     * SHARE lock, as {@link #createsHeldAtTheirInsert}), by when it holds the parent's lock, so {@code parentWrite}
     * must wait while the gate is shut; without that lock it would finish under the gate. The writes queue on the
     * parent's lock in no fixed order, and the parent's write may get it between two of them: assert the set of
     * outcomes, never their order.
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
            awaitResult(gate);
        }
        List<Answer> answers = new ArrayList<>();
        for (Future<Answer> answer : pending) {
            answers.add(awaitResult(answer));
        }
        return new HeldWrites(answers, awaitResult(parent));
    }

    protected static void assertNoInternals(String body) {
        assertThat(body).doesNotContain("uq_", "fk_", "ex_", "ck_", "pk_", "SQL", "Exception", "java.");
    }

    protected static void assertRefusalsAre(List<Answer> answers, int status, String errorCode) {
        for (Answer answer : answers) {
            if (answer.status() == status) {
                assertThat(answer.errorCode()).isEqualTo(errorCode);
                assertNoInternals(answer.body());
            }
        }
    }

    /**
     * Counts every waiting session of the database, which is exact only because test classes run one at a time
     * (no JUnit parallel execution is configured); in parallel the count would need narrowing to the sessions the
     * gate blocks ({@code pg_blocking_pids}).
     */
    protected void awaitLockWaiters(int sessions, Future<?> done) throws InterruptedException {
        awaitLockWaiters(sessions, done, lockWaitDeadline());
    }

    /** A test that waits for several sessions in turn shares one deadline across the waits. */
    protected static long lockWaitDeadline() {
        return System.nanoTime() + LOCK_WAIT_DEADLINE.toNanos();
    }

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

    protected static void awaitLatch(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(WAIT_DEADLINE.toMillis(), TimeUnit.MILLISECONDS)).as("latch released in time").isTrue();
    }

    protected static <T> T awaitResult(Future<T> future) throws Exception {
        return future.get(WAIT_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * For a gate held inside a transaction callback, which can't throw InterruptedException. It gives up after
     * {@link #WAIT_DEADLINE}, so a test that failed before opening the gate doesn't keep it shut.
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

    /**
     * Only the authorities change; the token's claims (its subject, and with tenancy its tenant) stay. For a check
     * across every permission: no role holds "all but one".
     */
    protected RequestPostProcessor holding(String accessToken, Collection<Permission> permissions) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (Permission permission : permissions) {
            authorities.add(new SimpleGrantedAuthority(permission.name()));
        }
        return SecurityMockMvcRequestPostProcessors.authentication(
                new JwtAuthenticationToken(jwtDecoder.decode(accessToken), authorities));
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
