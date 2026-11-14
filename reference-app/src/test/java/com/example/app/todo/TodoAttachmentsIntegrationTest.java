package com.example.app.todo;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A todo's files (the media piece in the example module): attached on create and update by upload key,
 * removed by id, the count kept within the purpose's range, the claim refusing every key that isn't
 * the caller's to use. Bootstrap copies this class with the example and media pieces together.
 */
@ResourceLock(IntegrationTest.UPLOAD_POLICIES_LOCK)
class TodoAttachmentsIntegrationTest extends IntegrationTest {

    private static final String PURPOSE = "TODO_ATTACHMENT";
    private static final long MAX_SIZE = 10_485_760;
    private static final String TODOS = "/api/v1/todos";
    private static final byte[] PDF = "%PDF-1.7 a small test file".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UploadPolicyLimits saved;

    /** Known limits before each test, whatever ran before it; the stored ones back after it, for every other class. */
    @BeforeEach
    void setThePolicy() {
        saved = uploadPolicyOf(PURPOSE);
        setUploadPolicy(PURPOSE, 1, MAX_SIZE, 0, 5);
    }

    @AfterEach
    void restoreThePolicy() {
        restoreUploadPolicy(saved);
    }

    private String createList(TestUser user) throws Exception {
        return read(postAs(user.accessToken(), "/api/v1/todo-lists", """
                {"name": "Files %s"}
                """.formatted(UUID.randomUUID())).andExpect(status().isCreated()), "$.response.id");
    }

    private String upload(TestUser user) throws Exception {
        return uploadedFile(user, PURPOSE, "scan.pdf", "application/pdf", PDF);
    }

    private List<String> uploads(TestUser user, int count) throws Exception {
        List<String> keys = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            keys.add(upload(user));
        }
        return keys;
    }

    private static String json(List<String> values) {
        return values.stream().map(value -> "\"" + value + "\"").collect(Collectors.joining(", ", "[", "]"));
    }

    private ResultActions create(TestUser user, String listId, List<String> attachmentKeys) throws Exception {
        return postAs(user.accessToken(), TODOS, """
                {"todoListId": "%s", "title": "With files", "priority": "LOW", "attachmentKeys": %s}
                """.formatted(listId, json(attachmentKeys)));
    }

    private String createTodo(TestUser user, String listId, List<String> attachmentKeys) throws Exception {
        return read(create(user, listId, attachmentKeys).andExpect(status().isCreated()), "$.response.id");
    }

    private ResultActions update(TestUser user, String listId, String todoId, long version, String title,
                                 List<String> addKeys, List<String> removeIds) throws Exception {
        return putAs(user.accessToken(), TODOS + "/" + todoId, """
                {"todoListId": "%s", "title": "%s", "priority": "LOW", "version": %d,
                 "addAttachmentKeys": %s, "removeAttachmentIds": %s}
                """.formatted(listId, title, version, json(addKeys), json(removeIds)));
    }

    private List<String> attachmentIds(TestUser user, String todoId) throws Exception {
        return read(getAs(user.accessToken(), TODOS + "/" + todoId).andExpect(status().isOk()), "$.response.attachments[*].id");
    }

    private String statusOfKey(String key) {
        return jdbc.queryForObject("select status from media_files where key = ?", String.class, key);
    }

    private String statusOf(String mediaFileId) {
        return jdbc.queryForObject("select status from media_files where id = ?::uuid", String.class, mediaFileId);
    }

    private int todosOf(TestUser user) {
        return jdbc.queryForObject("select count(*) from todos where owner_id = ?", Integer.class, user.id());
    }

    @Test
    @DisplayName("Create attaches the uploads: the todo lists them IN_REVIEW with name, type, size and a read URL")
    void createAttachesTheUploads() throws Exception {
        TestUser user = newUser();
        List<String> keys = uploads(user, 2);

        create(user, createList(user), keys)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.attachments", hasSize(2)))
                .andExpect(jsonPath("$.response.attachments[0].fileName").value("scan.pdf"))
                .andExpect(jsonPath("$.response.attachments[0].contentType").value("application/pdf"))
                .andExpect(jsonPath("$.response.attachments[0].sizeBytes").value(PDF.length))
                .andExpect(jsonPath("$.response.attachments[0].status").value("IN_REVIEW"))
                .andExpect(jsonPath("$.response.attachments[0].url").isNotEmpty())
                .andExpect(jsonPath("$.response.version").value(0));
        assertThat(statusOfKey(keys.get(0))).isEqualTo("IN_REVIEW");
        assertThat(statusOfKey(keys.get(1))).isEqualTo("IN_REVIEW");
    }

    @Test
    @DisplayName("A todo without files has an empty attachments list")
    void noFilesIsAnEmptyList() throws Exception {
        TestUser user = newUser();

        create(user, createList(user), List.of())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.attachments", hasSize(0)));
    }

    @Test
    @DisplayName("The read URL downloads the bytes as an attachment, with the file's name (RFC 5987)")
    void readUrlServesTheBytesAsAnAttachment() throws Exception {
        TestUser user = newUser();
        String key = uploadedFile(user, PURPOSE, "Bericht März.pdf", "application/pdf", PDF);
        String todoId = createTodo(user, createList(user), List.of(key));
        String url = read(getAs(user.accessToken(), TODOS + "/" + todoId), "$.response.attachments[0].url");

        HttpResponse<byte[]> download = getFromStorage(url);

        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(PDF);
        assertThat(download.headers().firstValue(HttpHeaders.CONTENT_TYPE)).hasValue("application/pdf");
        assertThat(download.headers().firstValue(HttpHeaders.CONTENT_DISPOSITION).orElseThrow())
                .startsWith("attachment;")
                .contains("filename*=UTF-8''Bericht%20M%C3%A4rz.pdf");
    }

    /** Sends {@code refusedKey} beside a good one: 409, no todo, and the good one is still claimable. */
    private void assertClaimRefused(TestUser user, String refusedKey) throws Exception {
        String listId = createList(user);
        String goodKey = upload(user);
        int todosBefore = todosOf(user);

        String body = create(user, listId, List.of(goodKey, refusedKey))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("UPLOAD_NOT_AVAILABLE"))
                .andReturn().getResponse().getContentAsString();
        assertNoInternals(body);

        assertThat(todosOf(user)).isEqualTo(todosBefore);
        assertThat(statusOfKey(goodKey)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("Another user's upload is 409 UPLOAD_NOT_AVAILABLE, and nothing of the request is kept")
    void anotherUsersUploadIs409() throws Exception {
        String othersKey = upload(newUser());

        assertClaimRefused(newUser(), othersKey);
        assertThat(statusOfKey(othersKey)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("An upload already attached to a todo is 409 UPLOAD_NOT_AVAILABLE")
    void usedUploadIs409() throws Exception {
        TestUser user = newUser();
        String key = upload(user);
        createTodo(user, createList(user), List.of(key));

        assertClaimRefused(user, key);
    }

    @Test
    @DisplayName("An upload past its claim window is 409 UPLOAD_NOT_AVAILABLE")
    void expiredUploadIs409() throws Exception {
        TestUser user = newUser();
        String key = upload(user);
        assertThat(sql("update media_files set upload_expires_at = now() - interval '2 hours', expires_at = now() - interval '1 minute' where key = ?",
                key)).isEqualTo(1);

        assertClaimRefused(user, key);
    }

    @Test
    @DisplayName("An upload of another purpose, or an unknown key, is 409 UPLOAD_NOT_AVAILABLE")
    void otherPurposeOrUnknownKeyIs409() throws Exception {
        TestUser user = newUser();
        String otherPurposeKey = "OTHER_PURPOSE/" + UUID.randomUUID();
        sql("""
                insert into media_files (id, key, purpose, file_name, content_type, size_bytes, status, uploaded_by,
                                         upload_expires_at, expires_at, created_at, updated_at, version)
                values (gen_random_uuid(), ?, 'OTHER_PURPOSE', 'a.pdf', 'application/pdf', 10, 'PENDING', ?,
                        now() + interval '5 minutes', now() + interval '1 hour', now(), now(), 0)
                """, otherPurposeKey, user.id());

        assertClaimRefused(user, otherPurposeKey);
        assertClaimRefused(user, PURPOSE + "/" + UUID.randomUUID());
    }

    @Test
    @DisplayName("Update refuses a key the caller can't claim (another user's) with 409 UPLOAD_NOT_AVAILABLE, and the todo is unchanged")
    void updateRefusesAnUnclaimableKey() throws Exception {
        TestUser user = newUser();
        String listId = createList(user);
        String todoId = createTodo(user, listId, List.of());
        String othersKey = upload(newUser());

        String body = update(user, listId, todoId, 0, "With files", List.of(othersKey), List.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("UPLOAD_NOT_AVAILABLE"))
                .andReturn().getResponse().getContentAsString();
        assertNoInternals(body);

        assertThat(attachmentIds(user, todoId)).isEmpty();
        assertThat(jdbc.queryForObject("select version from todos where id = ?::uuid", Long.class, todoId)).isZero();
        assertThat(statusOfKey(othersKey)).isEqualTo("PENDING");
    }

    /** Sends {@code refusedKey} in an update of a todo without files: 409, and the todo is unchanged. */
    private void assertUpdateClaimRefused(TestUser user, String refusedKey) throws Exception {
        String listId = createList(user);
        String todoId = createTodo(user, listId, List.of());

        String body = update(user, listId, todoId, 0, "With files", List.of(refusedKey), List.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("UPLOAD_NOT_AVAILABLE"))
                .andReturn().getResponse().getContentAsString();
        assertNoInternals(body);
        assertThat(attachmentIds(user, todoId)).isEmpty();
        assertThat(jdbc.queryForObject("select version from todos where id = ?::uuid", Long.class, todoId)).isZero();
    }

    @Test
    @DisplayName("Update refuses every other unclaimable key too: already attached, expired, another purpose's, unknown")
    void updateRefusesEveryUnclaimableKey() throws Exception {
        TestUser user = newUser();
        String attached = upload(user);
        createTodo(user, createList(user), List.of(attached));
        String expired = upload(user);
        assertThat(sql("update media_files set upload_expires_at = now() - interval '2 hours', expires_at = now() - interval '1 minute' where key = ?",
                expired)).isEqualTo(1);
        String otherPurposeKey = "OTHER_PURPOSE/" + UUID.randomUUID();
        sql("""
                insert into media_files (id, key, purpose, file_name, content_type, size_bytes, status, uploaded_by,
                                         upload_expires_at, expires_at, created_at, updated_at, version)
                values (gen_random_uuid(), ?, 'OTHER_PURPOSE', 'a.pdf', 'application/pdf', 10, 'PENDING', ?,
                        now() + interval '5 minutes', now() + interval '1 hour', now(), now(), 0)
                """, otherPurposeKey, user.id());

        for (String refused : List.of(attached, expired, otherPurposeKey, PURPOSE + "/" + UUID.randomUUID())) {
            assertUpdateClaimRefused(user, refused);
        }
    }

    @Test
    @DisplayName("An update that ends exactly at the purpose's maxFiles is accepted")
    void updateUpToTheMaximumIsAccepted() throws Exception {
        TestUser user = newUser();
        String listId = createList(user);
        String todoId = createTodo(user, listId, uploads(user, 4));

        update(user, listId, todoId, 0, "With files", List.of(upload(user)), List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.attachments", hasSize(5)));
    }

    @Test
    @DisplayName("Fewer files than the purpose's minFiles on create is 400 UPLOAD_FILE_COUNT_OUT_OF_RANGE")
    void createBelowTheMinimumIs400() throws Exception {
        setUploadPolicy(PURPOSE, 1, MAX_SIZE, 2, 5);
        TestUser user = newUser();
        String key = upload(user);

        for (List<String> keys : List.of(List.<String>of(), List.of(key))) {
            create(user, createList(user), keys)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("UPLOAD_FILE_COUNT_OUT_OF_RANGE"));
        }
        assertThat(statusOfKey(key)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("5 files, minimum 2: removing 4 alone is 400 and changes nothing; removing 4 and adding 1 leaves 2")
    void removingBelowTheMinimumNeedsFilesAdded() throws Exception {
        setUploadPolicy(PURPOSE, 1, MAX_SIZE, 2, 5);
        TestUser user = newUser();
        String listId = createList(user);
        String todoId = createTodo(user, listId, uploads(user, 5));
        List<String> files = attachmentIds(user, todoId);
        List<String> fourOfThem = files.subList(0, 4);

        update(user, listId, todoId, 0, "With files", List.of(), fourOfThem)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UPLOAD_FILE_COUNT_OUT_OF_RANGE"));
        assertThat(attachmentIds(user, todoId)).hasSize(5);

        update(user, listId, todoId, 0, "With files", List.of(upload(user)), fourOfThem)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.attachments", hasSize(2)))
                .andExpect(jsonPath("$.response.version").value(1));
        for (String removed : fourOfThem) {
            assertThat(statusOf(removed)).isEqualTo("RELEASED");
        }
    }

    @Test
    @DisplayName("Adding past the purpose's maxFiles is 400 UPLOAD_FILE_COUNT_OUT_OF_RANGE")
    void addingPastTheMaximumIs400() throws Exception {
        TestUser user = newUser();
        String listId = createList(user);
        String todoId = createTodo(user, listId, uploads(user, 5));
        String extra = upload(user);

        update(user, listId, todoId, 0, "With files", List.of(extra), List.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UPLOAD_FILE_COUNT_OUT_OF_RANGE"));
        assertThat(statusOfKey(extra)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("A MISSING file keeps its slot: at minimum 2 the todo still has 2; removing it alone is 400, replacing it passes")
    void missingFileKeepsItsSlot() throws Exception {
        setUploadPolicy(PURPOSE, 1, MAX_SIZE, 2, 5);
        TestUser user = newUser();
        String listId = createList(user);
        String todoId = createTodo(user, listId, uploads(user, 2));
        String missing = attachmentIds(user, todoId).get(0);
        assertThat(sql("update media_files set status = 'MISSING' where id = ?::uuid", missing)).isEqualTo(1);

        getAs(user.accessToken(), TODOS + "/" + todoId)
                .andExpect(jsonPath("$.response.attachments", hasSize(2)))
                .andExpect(jsonPath("$.response.attachments[0].status").value("MISSING"))
                .andExpect(jsonPath("$.response.attachments[0].url").value(nullValue()));
        update(user, listId, todoId, 0, "With files", List.of(), List.of(missing))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UPLOAD_FILE_COUNT_OUT_OF_RANGE"));

        update(user, listId, todoId, 0, "With files", List.of(upload(user)), List.of(missing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.attachments", hasSize(2)));
        assertThat(statusOf(missing)).isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("Removing a file of another todo is 404 TODO_ATTACHMENT_NOT_FOUND, and it stays attached there")
    void removingAnotherTodosFileIs404() throws Exception {
        TestUser user = newUser();
        String listId = createList(user);
        String todoId = createTodo(user, listId, uploads(user, 1));
        String otherTodo = createTodo(user, listId, uploads(user, 1));
        String othersFile = attachmentIds(user, otherTodo).get(0);

        update(user, listId, todoId, 0, "With files", List.of(), List.of(othersFile))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_ATTACHMENT_NOT_FOUND"));
        assertThat(statusOf(othersFile)).isEqualTo("IN_REVIEW");
    }

    @Test
    @DisplayName("A change of the files alone increments the version, so the old version is then stale (409)")
    void filesOnlyChangeBumpsTheVersion() throws Exception {
        TestUser user = newUser();
        String listId = createList(user);
        String todoId = createTodo(user, listId, List.of());

        update(user, listId, todoId, 0, "With files", List.of(upload(user)), List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.version").value(1));
        update(user, listId, todoId, 0, "With files", List.of(), attachmentIds(user, todoId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK_CONFLICT"));
    }

    @Test
    @DisplayName("Concurrent removals that each keep the minimum but together break it: one applies, the others are 409")
    void concurrentRemovalsAreSerialisedByTheVersion() throws Exception {
        setUploadPolicy(PURPOSE, 1, MAX_SIZE, 2, 5);
        TestUser user = newUser();
        String listId = createList(user);
        String todoId = createTodo(user, listId, uploads(user, 5));
        List<String> files = attachmentIds(user, todoId);
        List<List<String>> removals = List.of(files.subList(0, 3), files.subList(2, 5));
        AtomicInteger racer = new AtomicInteger();

        List<Answer> answers = race(() -> update(user, listId, todoId, 0, "With files", List.of(),
                removals.get(racer.getAndIncrement() % 2)));

        assertThat(answers).filteredOn(answer -> answer.status() == 200).hasSize(1);
        assertThat(answers).filteredOn(answer -> answer.status() != 200).allMatch(answer -> answer.status() == 409);
        assertRefusalsAre(answers, 409, "OPTIMISTIC_LOCK_CONFLICT");
        assertThat(attachmentIds(user, todoId)).hasSize(2);
        Integer released = jdbc.queryForObject("select count(*) from media_files where status = 'RELEASED' and id in (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid)",
                Integer.class, files.toArray());
        assertThat(released).isEqualTo(3);
    }

    @Test
    @DisplayName("Concurrent replacements of a file (remove it, add another) with one version: one applies, the others are 409, never 503")
    void concurrentReplacementsWithOneVersionApplyOnce() throws Exception {
        TestUser user = newUser();
        String listId = createList(user);
        String todoId = createTodo(user, listId, uploads(user, 1));
        String original = attachmentIds(user, todoId).getFirst();
        List<String> replacements = uploads(user, RACERS);
        AtomicInteger racer = new AtomicInteger();

        List<Answer> answers = race(() -> update(user, listId, todoId, 0, "With files",
                List.of(replacements.get(racer.getAndIncrement())), List.of(original)));

        assertThat(answers).extracting(Answer::status).containsOnlyOnce(200).containsOnly(200, 409);
        assertRefusalsAre(answers, 409, "OPTIMISTIC_LOCK_CONFLICT");
        assertThat(jdbc.queryForObject("select version from todos where id = ?::uuid", Long.class, todoId)).isEqualTo(1L);
        assertThat(attachmentIds(user, todoId)).hasSize(1);
        assertThat(statusOf(original)).isEqualTo("RELEASED");
        List<String> statuses = replacements.stream().map(this::statusOfKey).toList();
        assertThat(statuses).containsOnlyOnce("IN_REVIEW").containsOnly("IN_REVIEW", "PENDING");
    }

    /** The tables (not their indexes) on which a session that waits for a lock already holds one. */
    private List<String> tablesLockedByWaitingSessions() {
        return jdbc.queryForList("""
                select distinct c.relname
                  from pg_locks l
                  join pg_class c on c.oid = l.relation
                  join pg_stat_activity a on a.pid = l.pid
                 where a.datname = current_database() and a.wait_event_type = 'Lock' and l.granted
                   and c.relkind = 'r'
                """, String.class);
    }

    /**
     * The guard of the replacement race above: todos has no unique column, so here two replacements
     * without the lock would still end in one 200 and 409s; an owner whose update changes a unique column
     * would deadlock instead (media.md). So the order is proven directly: held at the todo, the update
     * has touched nothing else yet.
     */
    @Test
    @DisplayName("An update of the files locks the todo before any other table: its list, its files, its attachment rows")
    void updateLocksTheTodoFirst() throws Exception {
        TestUser user = newUser();
        String listId = createList(user);
        String todoId = createTodo(user, listId, uploads(user, 1));
        String original = attachmentIds(user, todoId).getFirst();
        String replacement = upload(user);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Answer> replace;
        try (ExecutorService executor = newExecutor()) {
            Future<?> holder = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.queryForList("select id from todos where id = ?::uuid for no key update", todoId);
                locked.countDown();
                awaitQuietly(release);
            }));
            awaitLatch(locked);
            try {
                replace = executor.submit(() -> answerOf(update(user, listId, todoId, 0, "With files",
                        List.of(replacement), List.of(original))));
                awaitLockWaiters(1, replace);

                assertThat(tablesLockedByWaitingSessions()).containsExactly("todos");
            } finally {
                release.countDown();
            }
            within(holder);
        }

        assertThat(within(replace).status()).isEqualTo(200);
    }

    private int statementsOfGet(TestUser user, String todoId) throws Exception {
        statistics().clear();
        getAs(user.accessToken(), TODOS + "/" + todoId).andExpect(status().isOk());
        return (int) statistics().getPrepareStatementCount();
    }

    @Test
    @DisplayName("A todo's response costs the same statements with one file and with five")
    void responseStatementsDoNotGrowWithTheFiles() throws Exception {
        TestUser user = newUser();
        String listId = createList(user);
        String oneFile = createTodo(user, listId, uploads(user, 1));
        String fiveFiles = createTodo(user, listId, uploads(user, 5));

        assertThat(statementsOfGet(user, fiveFiles)).isEqualTo(statementsOfGet(user, oneFile));
    }

    private static List<String> randomKeys(int count) {
        return Stream.generate(() -> PURPOSE + "/" + UUID.randomUUID()).limit(count).toList();
    }

    private static List<String> randomIdList(int count) {
        return Stream.generate(() -> UUID.randomUUID().toString()).limit(count).toList();
    }

    @Test
    @DisplayName("An upload key of the configured length passes validation; one character more is 400 with the limit, on create and update")
    void uploadKeyLengthFollowsTheConfiguredLimit() throws Exception {
        TestUser user = newUser();
        String listId = createList(user);
        String todoId = createTodo(user, listId, List.of());
        String longest = PURPOSE + "/" + "k".repeat(100 - PURPOSE.length() - 1);
        String tooLong = longest + "k";

        Answer created = answerOf(create(user, listId, List.of(longest)));
        Answer updated = answerOf(update(user, listId, todoId, 0, "With files", List.of(longest), List.of()));

        assertThat(List.of(created.status(), updated.status())).containsOnly(409);
        assertRefusalsAre(List.of(created, updated), 409, "UPLOAD_NOT_AVAILABLE");
        create(user, listId, List.of(tooLong))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed", hasItem(allOf(startsWith("attachmentKeys"), endsWith(": must be at most 100 characters")))));
        update(user, listId, todoId, 0, "With files", List.of(tooLong), List.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed", hasItem(allOf(startsWith("addAttachmentKeys"), endsWith(": must be at most 100 characters")))));
    }

    @Test
    @DisplayName("50 keys or ids (upload-policy.max-files) pass validation; 51 are 400 with the limit, on create and on both update fields")
    void fileListsAreBoundedByTheConfiguredMaximum() throws Exception {
        TestUser user = newUser();
        String listId = createList(user);
        String todoId = createTodo(user, listId, List.of());

        create(user, listId, randomKeys(50))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UPLOAD_FILE_COUNT_OUT_OF_RANGE"));
        create(user, listId, randomKeys(51))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed", contains("attachmentKeys: must have at most 50 items")));
        update(user, listId, todoId, 0, "With files", randomKeys(50), List.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UPLOAD_FILE_COUNT_OUT_OF_RANGE"));
        update(user, listId, todoId, 0, "With files", randomKeys(51), List.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed", contains("addAttachmentKeys: must have at most 50 items")));
        update(user, listId, todoId, 0, "With files", List.of(), randomIdList(50))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_ATTACHMENT_NOT_FOUND"));
        update(user, listId, todoId, 0, "With files", List.of(), randomIdList(51))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed", contains("removeAttachmentIds: must have at most 50 items")));
    }

    @Test
    @DisplayName("A policy shrunk below a todo's files doesn't block other edits; the next change of its files must end inside it")
    void shrunkPolicyDoesNotBlockOtherEdits() throws Exception {
        TestUser user = newUser();
        String listId = createList(user);
        String todoId = createTodo(user, listId, uploads(user, 3));
        setUploadPolicy(PURPOSE, 1, MAX_SIZE, 0, 2);

        update(user, listId, todoId, 0, "Renamed", List.of(), List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.attachments", hasSize(3)));
        update(user, listId, todoId, 1, "Renamed", List.of(upload(user)), List.of(attachmentIds(user, todoId).get(0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UPLOAD_FILE_COUNT_OUT_OF_RANGE"));
    }

    @Test
    @DisplayName("Deleting a todo releases its files for the cleanup job")
    void deleteReleasesTheFiles() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user, createList(user), uploads(user, 2));
        List<String> files = attachmentIds(user, todoId);

        deleteAs(user.accessToken(), TODOS + "/" + todoId).andExpect(status().isNoContent());

        assertThat(statusOf(files.get(0))).isEqualTo("RELEASED");
        assertThat(statusOf(files.get(1))).isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("The API docs describe the attachment fields and their errors")
    void apiDocsDescribeAttachments() throws Exception {
        getAs(null, "/v3/api-docs")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/todos'].post.responses['409'].description", containsString("UPLOAD_NOT_AVAILABLE")))
                .andExpect(jsonPath("$.paths['/api/v1/todos/{id}'].put.responses['400'].description", containsString("UPLOAD_FILE_COUNT_OUT_OF_RANGE")))
                .andExpect(jsonPath("$.paths['/api/v1/todos/{id}'].put.responses['404'].description", containsString("TODO_ATTACHMENT_NOT_FOUND")))
                .andExpect(jsonPath("$.components.schemas.TodoUpdateRequest.properties.removeAttachmentIds").exists())
                .andExpect(jsonPath("$.components.schemas.TodoCreateRequest.properties.attachmentKeys.items.maxLength").value(100))
                .andExpect(jsonPath("$.components.schemas.TodoUpdateRequest.properties.addAttachmentKeys.items.maxLength").value(100))
                .andExpect(jsonPath("$.components.schemas.TodoCreateRequest.properties.attachmentKeys.maxItems").value(50))
                .andExpect(jsonPath("$.components.schemas.TodoResponse.properties.attachments").exists());
    }
}
