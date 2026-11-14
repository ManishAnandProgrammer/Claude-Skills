package com.example.app.todo;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A todo's files (the media piece in the example module): attached on create and update by upload key,
 * removed by id, the count kept within the purpose's range, the claim refusing every key that isn't
 * the caller's to use. Bootstrap copies this class with the example and media pieces together.
 */
@ResourceLock(TodoAttachmentsIntegrationTest.POLICY_LOCK)
class TodoAttachmentsIntegrationTest extends IntegrationTest {

    static final String POLICY_LOCK = "TODO_ATTACHMENT_POLICY";
    private static final String PURPOSE = "TODO_ATTACHMENT";
    private static final long MAX_SIZE = 10_485_760;
    private static final String TODOS = "/api/v1/todos";
    private static final byte[] PDF = "%PDF-1.7 a small test file".getBytes(StandardCharsets.US_ASCII);

    @AfterEach
    void restoreThePolicy() {
        setUploadPolicy(PURPOSE, 1, MAX_SIZE, 0, 5);
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

        create(user, listId, List.of(goodKey, refusedKey))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("UPLOAD_NOT_AVAILABLE"));

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
                .andExpect(jsonPath("$.components.schemas.TodoResponse.properties.attachments").exists());
    }
}
