package com.example.app.todo;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TimeEntryIntegrationTest extends IntegrationTest {

    private static final String TODOS = "/api/v1/todos";
    private static final String LISTS = "/api/v1/todo-lists";
    /** Below the pool size (10) with the delete, the gate and the polling connection. */
    private static final int CREATORS = 4;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;
    private static String entries(String todoId) {
        return TODOS + "/" + todoId + "/time-entries";
    }

    private static String entry(String todoId, String id) {
        return entries(todoId) + "/" + id;
    }

    private static String createPayload(int minutes, String spentOn, boolean billable) {
        return """
                {"minutes": %d, "spentOn": "%s", "note": "Pairing", "billable": %b}
                """.formatted(minutes, spentOn, billable);
    }

    private static String noteUpdatePayload(String note, long version) {
        return """
                {"minutes": 10, "spentOn": "2026-11-01", "note": "%s", "billable": false, "version": %d}
                """.formatted(note, version);
    }

    private static String updatePayload(int minutes, String spentOn, boolean billable, long version) {
        return """
                {"minutes": %d, "spentOn": "%s", "note": null, "billable": %b, "version": %d}
                """.formatted(minutes, spentOn, billable, version);
    }

    /** A user with one list and one todo in it; fails the test unless every fixture answers 201. */
    private String createTodo(TestUser user) throws Exception {
        String listId = read(postAs(user.accessToken(), LISTS, """
                {"name": "List %s"}
                """.formatted(UUID.randomUUID())).andExpect(status().isCreated()), "$.response.id");
        return read(postAs(user.accessToken(), TODOS, """
                {"todoListId": "%s", "title": "Billable work", "priority": "LOW"}
                """.formatted(listId)).andExpect(status().isCreated()), "$.response.id");
    }

    private String createEntry(TestUser user, String todoId, int minutes, String spentOn, boolean billable) throws Exception {
        return read(postAs(user.accessToken(), entries(todoId), createPayload(minutes, spentOn, billable))
                .andExpect(status().isCreated()), "$.response.id");
    }

    /**
     * Follows {@code pageInfo.nextCursor} from {@code firstBatch} (a path with its query string) to
     * the last batch, which must say {@code hasMore} false with no cursor; returns each batch's ids.
     */
    private List<List<String>> walk(TestUser user, String firstBatch) throws Exception {
        List<List<String>> batches = new ArrayList<>();
        String next = firstBatch;
        while (Objects.nonNull(next)) {
            assertThat(batches).hasSizeLessThan(20);
            ResultActions batch = getAs(user.accessToken(), next).andExpect(status().isOk());
            batches.add(read(batch, "$.response[*].id"));
            if (Boolean.TRUE.equals(read(batch, "$.pageInfo.hasMore"))) {
                next = firstBatch + "&after=" + read(batch, "$.pageInfo.nextCursor");
            } else {
                batch.andExpect(jsonPath("$.pageInfo.nextCursor").doesNotExist());
                next = null;
            }
        }
        return batches;
    }

    @Test
    @DisplayName("Create answers 201 with every field, the todo, the owner, version 0 and the audit fields")
    void createReturns201WithTheSavedEntity() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user);

        postAs(user.accessToken(), entries(todoId), createPayload(45, "2026-11-02", true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.id").isNotEmpty())
                .andExpect(jsonPath("$.response.todoId").value(todoId))
                .andExpect(jsonPath("$.response.ownerId").value(user.id().toString()))
                .andExpect(jsonPath("$.response.minutes").value(45))
                .andExpect(jsonPath("$.response.spentOn").value("2026-11-02"))
                .andExpect(jsonPath("$.response.note").value("Pairing"))
                .andExpect(jsonPath("$.response.billable").value(true))
                .andExpect(jsonPath("$.response.version").value(0))
                .andExpect(jsonPath("$.response.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.response.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.response.createdBy").value(user.id().toString()))
                .andExpect(jsonPath("$.response.updatedBy").value(user.id().toString()));
    }

    @Test
    @DisplayName("Logging time on an unknown or someone else's todo is 404 TODO_NOT_FOUND and stores nothing")
    void createOnAnotherUsersTodoIs404() throws Exception {
        TestUser owner = newUser();
        String othersTodo = createTodo(owner);
        String ownersEntry = createEntry(owner, othersTodo, 30, "2026-11-02", false);
        TestUser user = newUser();
        getAs(owner.accessToken(), entries(othersTodo)).andExpect(jsonPath("$.response[*].id", contains(ownersEntry)));

        postAs(user.accessToken(), entries(othersTodo), createPayload(30, "2026-11-02", false))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
        postAs(user.accessToken(), entries(UUID.randomUUID().toString()), createPayload(30, "2026-11-02", false))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
        getAs(owner.accessToken(), entries(othersTodo)).andExpect(jsonPath("$.response[*].id", contains(ownersEntry)));
        Integer strangersEntries = jdbc.queryForObject("select count(*) from time_entries where owner_id = ?",
                Integer.class, user.id());
        assertThat(strangersEntries).isZero();
    }

    @Test
    @DisplayName("Get returns the time entry")
    void getReturnsTheEntity() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user);
        String id = createEntry(user, todoId, 90, "2026-11-03", false);

        getAs(user.accessToken(), entry(todoId, id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.id").value(id))
                .andExpect(jsonPath("$.response.minutes").value(90))
                .andExpect(jsonPath("$.response.billable").value(false));
    }

    @Test
    @DisplayName("Get of an unknown id is 404 TIME_ENTRY_NOT_FOUND; under an unknown todo 404 TODO_NOT_FOUND")
    void getUnknownIdIs404() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user);

        getAs(user.accessToken(), entry(todoId, UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TIME_ENTRY_NOT_FOUND"));
        getAs(user.accessToken(), entry(UUID.randomUUID().toString(), UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
    }

    @Test
    @DisplayName("An entry is reachable only through its own todo: under another todo of yours it is 404")
    void entryUnderAnotherTodoIs404() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user);
        String otherTodo = createTodo(user);
        String id = createEntry(user, todoId, 10, "2026-11-03", false);
        getAs(user.accessToken(), entry(todoId, id)).andExpect(status().isOk());

        getAs(user.accessToken(), entry(otherTodo, id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TIME_ENTRY_NOT_FOUND"));
        putAs(user.accessToken(), entry(otherTodo, id), updatePayload(20, "2026-11-03", false, 0))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TIME_ENTRY_NOT_FOUND"));
        deleteAs(user.accessToken(), entry(otherTodo, id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TIME_ENTRY_NOT_FOUND"));
        getAs(user.accessToken(), entry(todoId, id)).andExpect(jsonPath("$.response.minutes").value(10));
    }

    @Test
    @DisplayName("Update edits the entry and increments the version; the same values again change nothing")
    void updateChangesTheFieldsAndBumpsTheVersion() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user);
        String id = createEntry(user, todoId, 30, "2026-11-01", true);

        putAs(user.accessToken(), entry(todoId, id), updatePayload(75, "2026-11-04", false, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.minutes").value(75))
                .andExpect(jsonPath("$.response.spentOn").value("2026-11-04"))
                .andExpect(jsonPath("$.response.note").value(nullValue()))
                .andExpect(jsonPath("$.response.billable").value(false))
                .andExpect(jsonPath("$.response.todoId").value(todoId))
                .andExpect(jsonPath("$.response.version").value(1));
        putAs(user.accessToken(), entry(todoId, id), updatePayload(75, "2026-11-04", false, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.version").value(1));
    }

    @Test
    @DisplayName("Update with a stale version is 409 OPTIMISTIC_LOCK_CONFLICT and changes nothing")
    void updateWithAStaleVersionIs409() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user);
        String id = createEntry(user, todoId, 30, "2026-11-01", true);
        putAs(user.accessToken(), entry(todoId, id), updatePayload(40, "2026-11-01", true, 0)).andExpect(status().isOk());

        putAs(user.accessToken(), entry(todoId, id), updatePayload(50, "2026-11-01", true, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK_CONFLICT"));
        getAs(user.accessToken(), entry(todoId, id))
                .andExpect(jsonPath("$.response.minutes").value(40))
                .andExpect(jsonPath("$.response.version").value(1));
    }

    @Test
    @DisplayName("PUT and DELETE on an unknown id are 404 TIME_ENTRY_NOT_FOUND")
    void writesToAnUnknownIdAre404() throws Exception {
        TestUser user = newUser();
        String unknown = entry(createTodo(user), UUID.randomUUID().toString());

        putAs(user.accessToken(), unknown, updatePayload(10, "2026-11-01", false, 0))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TIME_ENTRY_NOT_FOUND"));
        deleteAs(user.accessToken(), unknown)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TIME_ENTRY_NOT_FOUND"));
    }

    @Test
    @DisplayName("Delete answers 204 and the entry is gone")
    void deleteReturns204AndTheEntityIsGone() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user);
        String id = createEntry(user, todoId, 15, "2026-11-01", false);
        getAs(user.accessToken(), entry(todoId, id)).andExpect(status().isOk());

        deleteAs(user.accessToken(), entry(todoId, id)).andExpect(status().isNoContent());
        getAs(user.accessToken(), entry(todoId, id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TIME_ENTRY_NOT_FOUND"));
    }

    @Test
    @DisplayName("Deleting a todo with time entries is 409 TODO_IN_USE without internals; once they are deleted it goes")
    void deleteWhileReferencedIs409() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user);
        String id = createEntry(user, todoId, 60, "2026-11-01", true);

        String body = deleteAs(user.accessToken(), TODOS + "/" + todoId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TODO_IN_USE"))
                .andReturn().getResponse().getContentAsString();
        assertNoInternals(body);
        getAs(user.accessToken(), entry(todoId, id)).andExpect(status().isOk());
        deleteAs(user.accessToken(), entry(todoId, id)).andExpect(status().isNoContent());
        deleteAs(user.accessToken(), TODOS + "/" + todoId).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Entries racing their todo's delete: the creates that read the todo first all land, and the delete is 409 TODO_IN_USE")
    void childWritesRacingAParentDeleteKeepTheInvariant() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch gateClosed = new CountDownLatch(1);
        CountDownLatch openGate = new CountDownLatch(1);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Answer>> creates = new ArrayList<>();
        Future<Answer> delete;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Holds every entry insert until the delete has run, so the delete lands between each
            // create's read of the todo and its insert: the interleaving the FOR SHARE lock is for.
            Future<?> gate = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.execute("lock table time_entries in share mode");
                gateClosed.countDown();
                awaitQuietly(openGate);
            }));
            gateClosed.await();
            try {
                for (int creator = 0; creator < CREATORS; creator++) {
                    Callable<Answer> create = () -> {
                        start.await();
                        return answerOf(postAs(user.accessToken(), entries(todoId), createPayload(5, "2026-11-01", false)));
                    };
                    creates.add(executor.submit(create));
                }
                start.countDown();
                awaitLockWaiters(CREATORS, null);
                delete = executor.submit(() -> answerOf(deleteAs(user.accessToken(), TODOS + "/" + todoId)));
                awaitLockWaiters(CREATORS + 1, delete);
            } finally {
                openGate.countDown();
            }
            gate.get();
        }
        List<Answer> createAnswers = new ArrayList<>();
        for (Future<Answer> create : creates) {
            createAnswers.add(create.get());
        }
        Answer deleteAnswer = delete.get();

        // Every create held its FOR SHARE lock on the todo before the delete came, so the delete waited
        // for their inserts and then found them: without the lock it would delete the todo under them.
        assertThat(createAnswers).extracting(Answer::status).containsOnly(201);
        assertThat(deleteAnswer.status()).isEqualTo(409);
        assertRefusalsAre(List.of(deleteAnswer), 409, "TODO_IN_USE");
        Integer todos = jdbc.queryForObject("select count(*) from todos where id = ?::uuid", Integer.class, todoId);
        Integer entries = jdbc.queryForObject("select count(*) from time_entries where todo_id = ?::uuid", Integer.class, todoId);
        assertThat(todos).isOne();
        assertThat(entries).isEqualTo(CREATORS);
    }
    @Test
    @DisplayName("Create without minutes, spentOn or billable is 400 naming each field")
    void createWithoutRequiredFieldsIs400() throws Exception {
        TestUser user = newUser();

        postAs(user.accessToken(), entries(createTodo(user)), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed", hasItem(containsString("minutes"))))
                .andExpect(jsonPath("$.failed", hasItem(containsString("spentOn"))))
                .andExpect(jsonPath("$.failed", hasItem(containsString("billable"))));
    }

    @Test
    @DisplayName("An update without version is 400 VALIDATION_FAILED naming version")
    void writesWithoutAVersionAre400() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user);
        String id = createEntry(user, todoId, 30, "2026-11-01", false);

        putAs(user.accessToken(), entry(todoId, id), createPayload(30, "2026-11-01", false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value(containsString("version")));
    }

    @Test
    @DisplayName("minutes from 1 to the configured 1440 are accepted; 0 and 1441 are 400, on create and update")
    void minutesFollowTheConfiguredRange() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user);
        createEntry(user, todoId, 1, "2026-11-01", false);
        String id = createEntry(user, todoId, 1440, "2026-11-01", false);
        putAs(user.accessToken(), entry(todoId, id), updatePayload(1, "2026-11-01", false, 0))
                .andExpect(status().isOk());
        putAs(user.accessToken(), entry(todoId, id), updatePayload(0, "2026-11-01", false, 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value("minutes: must be greater than or equal to 1"));

        postAs(user.accessToken(), entries(todoId), createPayload(0, "2026-11-01", false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value("minutes: must be greater than or equal to 1"));
        postAs(user.accessToken(), entries(todoId), createPayload(1441, "2026-11-01", false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value("minutes: must be at most 1440"));
        putAs(user.accessToken(), entry(todoId, id), updatePayload(1441, "2026-11-01", false, 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value("minutes: must be at most 1440"));
    }

    @Test
    @DisplayName("spentOn up to UTC today plus the configured day is accepted (any zone's today); the day after is 400, on create and update")
    void spentOnIsNotInTheFuture() throws Exception {
        // Before any request, so every request of the test compares with the same day.
        LocalDate today = LocalDate.now(clock);
        String latest = today.plusDays(1).toString();
        String future = today.plusDays(2).toString();
        TestUser user = newUser();
        String todoId = createTodo(user);
        createEntry(user, todoId, 30, today.toString(), false);
        String id = createEntry(user, todoId, 30, latest, false);

        postAs(user.accessToken(), entries(todoId), createPayload(30, future, false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value("spentOn: must not be in the future"));
        putAs(user.accessToken(), entry(todoId, id), updatePayload(30, today.toString(), false, 0))
                .andExpect(status().isOk());
        putAs(user.accessToken(), entry(todoId, id), updatePayload(30, latest, false, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.spentOn").value(latest));
        putAs(user.accessToken(), entry(todoId, id), updatePayload(30, future, false, 2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value("spentOn: must not be in the future"));
    }

    @Test
    @DisplayName("A note of 500 Gurmukhi letters is accepted; 501 characters is 400 with the configured limit")
    void noteLengthFollowsTheConfiguredLimit() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user);
        String notePayload = """
                {"minutes": 10, "spentOn": "2026-11-01", "note": "%s", "billable": false}
                """;

        postAs(user.accessToken(), entries(todoId), notePayload.formatted("ਨ".repeat(500)))
                .andExpect(status().isCreated());
        postAs(user.accessToken(), entries(todoId), notePayload.formatted("n".repeat(501)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value("note: must be at most 500 characters"));
        String id = createEntry(user, todoId, 10, "2026-11-01", false);
        putAs(user.accessToken(), entry(todoId, id), noteUpdatePayload("ਨ".repeat(500), 0))
                .andExpect(status().isOk());
        putAs(user.accessToken(), entry(todoId, id), noteUpdatePayload("n".repeat(501), 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value("note: must be at most 500 characters"));
    }

    @Test
    @DisplayName("A blank note is stored as none (null), on create and update, so \"no note\" has one representation")
    void blankNoteIsStoredAsNone() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user);
        String id = read(postAs(user.accessToken(), entries(todoId), createPayload(10, "2026-11-01", false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.note").value("Pairing")), "$.response.id");

        postAs(user.accessToken(), entries(todoId), """
                {"minutes": 10, "spentOn": "2026-11-01", "note": "  ", "billable": false}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.note").value(nullValue()));
        putAs(user.accessToken(), entry(todoId, id), noteUpdatePayload("", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.note").value(nullValue()));
        Integer blankNotes = jdbc.queryForObject("select count(*) from time_entries where todo_id = ?::uuid and trim(note) = ''",
                Integer.class, todoId);
        assertThat(blankNotes).isZero();
    }

    @Test
    @DisplayName("Markup in a note is stored and returned as text (escape-on-output policy)")
    void createWithAScriptTagStoresItAsText() throws Exception {
        TestUser user = newUser();
        String hostile = "<img src=x onerror=alert(1)>";

        postAs(user.accessToken(), entries(createTodo(user)), """
                {"minutes": 10, "spentOn": "2026-11-01", "note": "%s", "billable": false}
                """.formatted(hostile))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.note").value(hostile));
    }

    static Stream<Arguments> endpoints() {
        String someTodo = UUID.randomUUID().toString();
        String someId = UUID.randomUUID().toString();
        return Stream.of(
                Arguments.of("GET", entries(someTodo)),
                Arguments.of("GET", entry(someTodo, someId)),
                Arguments.of("POST", entries(someTodo)),
                Arguments.of("PUT", entry(someTodo, someId)),
                Arguments.of("DELETE", entry(someTodo, someId)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("endpoints")
    @DisplayName("Every endpoint answers 401 AUTHENTICATION_REQUIRED without a token")
    void everyEndpointNeedsAToken(String method, String path) throws Exception {
        ResultActions result = switch (method) {
            case "GET" -> getAs(null, path);
            case "POST" -> postAs(null, path, createPayload(10, "2026-11-01", false));
            case "PUT" -> putAs(null, path, updatePayload(10, "2026-11-01", false, 0));
            case "DELETE" -> deleteAs(null, path);
            default -> throw new IllegalArgumentException(method);
        };
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("Another user's entries are 404 TODO_NOT_FOUND on list, get, update and delete, and stay untouched")
    void anotherUsersRecordIs404() throws Exception {
        TestUser owner = newUser();
        TestUser stranger = newUser();
        String todoId = createTodo(owner);
        String id = createEntry(owner, todoId, 30, "2026-11-01", true);
        getAs(owner.accessToken(), entry(todoId, id)).andExpect(status().isOk());

        getAs(stranger.accessToken(), entries(todoId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
        getAs(stranger.accessToken(), entry(todoId, id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
        putAs(stranger.accessToken(), entry(todoId, id), updatePayload(999, "2026-11-01", false, 0))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
        deleteAs(stranger.accessToken(), entry(todoId, id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
        getAs(owner.accessToken(), entry(todoId, id))
                .andExpect(jsonPath("$.response.minutes").value(30))
                .andExpect(jsonPath("$.response.version").value(0));
    }

    @Test
    @DisplayName("The list holds only this todo's entries, in one last batch")
    void listShowsOnlyThisTodosEntries() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user);
        String mine = createEntry(user, todoId, 30, "2026-11-01", false);
        String otherTodosEntry = createEntry(user, createTodo(user), 30, "2026-11-01", false);

        getAs(user.accessToken(), entries(todoId))
                .andExpect(jsonPath("$.response[*].id", contains(mine)))
                .andExpect(jsonPath("$.response[*].id", not(hasItem(otherTodosEntry))))
                .andExpect(jsonPath("$.pageInfo.hasMore").value(false))
                .andExpect(jsonPath("$.pageInfo.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("SPENT_ON batches (the default) walk every entry once, the most recent day first, ties newest first")
    void cursorBatchesWalkTheWholeListInOrder() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user);
        String day3 = createEntry(user, todoId, 10, "2026-11-03", false);
        String day1 = createEntry(user, todoId, 20, "2026-11-01", false);
        String day5 = createEntry(user, todoId, 30, "2026-11-05", false);
        String day2a = createEntry(user, todoId, 40, "2026-11-02", false);
        String day4 = createEntry(user, todoId, 50, "2026-11-04", false);
        String day2b = createEntry(user, todoId, 60, "2026-11-02", false);

        assertThat(walk(user, entries(todoId) + "?size=4")).containsExactly(
                List.of(day5, day4, day3, day2b),
                List.of(day2a, day1));
        assertThat(walk(user, entries(todoId) + "?sort=SPENT_ON&size=2")).containsExactly(
                List.of(day5, day4), List.of(day3, day2b), List.of(day2a, day1));
    }

    @Test
    @DisplayName("MINUTES batches walk every entry once, the longest first, ties newest first")
    void minutesBatchesWalkToTheEnd() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user);
        String sixty = createEntry(user, todoId, 60, "2026-11-01", false);
        String ten = createEntry(user, todoId, 10, "2026-11-02", false);
        String ninety = createEntry(user, todoId, 90, "2026-11-03", false);
        String sixtyLater = createEntry(user, todoId, 60, "2026-11-04", false);
        String thirty = createEntry(user, todoId, 30, "2026-11-05", false);

        assertThat(walk(user, entries(todoId) + "?sort=MINUTES&size=2")).containsExactly(
                List.of(ninety, sixtyLater), List.of(sixty, thirty), List.of(ten));
    }

    @Test
    @DisplayName("The configured maximum size is accepted; one more is 400 VALIDATION_FAILED naming size")
    void sizeOverTheMaximumIs400() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user);
        String only = createEntry(user, todoId, 10, "2026-11-01", false);

        getAs(user.accessToken(), entries(todoId) + "?size=100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].id", contains(only)));
        getAs(user.accessToken(), entries(todoId) + "?size=101")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value("size: must be at most 100"));
    }

    @Test
    @DisplayName("A cursor from one sort is refused under another")
    void cursorFromAnotherSortIsRefused() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user);
        createEntry(user, todoId, 10, "2026-11-01", false);
        createEntry(user, todoId, 20, "2026-11-02", false);
        String minutesCursor = read(getAs(user.accessToken(), entries(todoId) + "?sort=MINUTES&size=1"), "$.pageInfo.nextCursor");

        getAs(user.accessToken(), entries(todoId) + "?sort=SPENT_ON&size=1&after=" + minutesCursor)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.parameter").value("after"));
    }

    @Test
    @DisplayName("A tampered cursor is 400 REQUEST_PARAMETER_INVALID naming after")
    void tamperedCursorIsRefused() throws Exception {
        TestUser user = newUser();

        getAs(user.accessToken(), entries(createTodo(user)) + "?after=not-a-cursor")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.parameter").value("after"));
    }

    @Test
    @DisplayName("spentOnFrom and spentOnTo include their boundary days and exclude the days beyond")
    void filterBySpentOnRangeReturnsOnlyMatching() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user);
        String before = createEntry(user, todoId, 10, "2026-10-31", false);
        String first = createEntry(user, todoId, 10, "2026-11-01", false);
        String last = createEntry(user, todoId, 10, "2026-11-07", false);
        String after = createEntry(user, todoId, 10, "2026-11-08", false);

        getAs(user.accessToken(), entries(todoId) + "?spentOnFrom=2026-11-01")
                .andExpect(jsonPath("$.response[*].id", contains(after, last, first)));
        getAs(user.accessToken(), entries(todoId) + "?spentOnTo=2026-11-07")
                .andExpect(jsonPath("$.response[*].id", contains(last, first, before)));
        getAs(user.accessToken(), entries(todoId) + "?spentOnFrom=2026-11-01&spentOnTo=2026-11-07&sort=MINUTES")
                .andExpect(jsonPath("$.response[*].id", containsInAnyOrder(first, last)));
    }

    @Test
    @DisplayName("minMinutes and maxMinutes include their boundary values and exclude the values beyond")
    void filterByMinutesRangeReturnsOnlyMatching() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user);
        String below = createEntry(user, todoId, 29, "2026-11-01", false);
        String low = createEntry(user, todoId, 30, "2026-11-01", false);
        String high = createEntry(user, todoId, 120, "2026-11-01", false);
        String above = createEntry(user, todoId, 121, "2026-11-01", false);

        getAs(user.accessToken(), entries(todoId) + "?sort=MINUTES&minMinutes=30")
                .andExpect(jsonPath("$.response[*].id", contains(above, high, low)));
        getAs(user.accessToken(), entries(todoId) + "?sort=MINUTES&maxMinutes=120")
                .andExpect(jsonPath("$.response[*].id", contains(high, low, below)));
        getAs(user.accessToken(), entries(todoId) + "?minMinutes=30&maxMinutes=120")
                .andExpect(jsonPath("$.response[*].id", containsInAnyOrder(low, high)));
    }

    @Test
    @DisplayName("minMinutes and maxMinutes take the values an entry can have: 1 and 1440 accepted, 0 and 1441 are 400")
    void minutesRangeIsBounded() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user);
        String entry = createEntry(user, todoId, 60, "2026-11-01", false);

        getAs(user.accessToken(), entries(todoId) + "?minMinutes=1&maxMinutes=1440")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].id", contains(entry)));
        getAs(user.accessToken(), entries(todoId) + "?minMinutes=0")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed", contains("minMinutes: must be greater than or equal to 1")));
        getAs(user.accessToken(), entries(todoId) + "?maxMinutes=1441")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed", contains("maxMinutes: must be at most 1440")));
    }

    @Test
    @DisplayName("An inverted range is 400 VALIDATION_FAILED naming its lower bound; equal bounds are a one-value range")
    void invertedRangesAre400() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user);
        String entry = createEntry(user, todoId, 30, "2026-11-01", false);

        getAs(user.accessToken(), entries(todoId) + "?spentOnFrom=2026-11-01&spentOnTo=2026-11-01&minMinutes=30&maxMinutes=30")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].id", contains(entry)));
        getAs(user.accessToken(), entries(todoId) + "?spentOnFrom=2026-11-02&spentOnTo=2026-11-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed", contains("spentOnFrom: must not be after spentOnTo")));
        getAs(user.accessToken(), entries(todoId) + "?minMinutes=31&maxMinutes=30")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed", contains("minMinutes: must not be after maxMinutes")));
    }

    @Test
    @DisplayName("billable=true lists only billable entries, false only the others")
    void filterByBillableReturnsOnlyMatching() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user);
        String billable = createEntry(user, todoId, 30, "2026-11-01", true);
        String internal = createEntry(user, todoId, 30, "2026-11-02", false);

        getAs(user.accessToken(), entries(todoId) + "?billable=true")
                .andExpect(jsonPath("$.response[*].id", hasItem(billable)))
                .andExpect(jsonPath("$.response[*].id", not(hasItem(internal))));
        getAs(user.accessToken(), entries(todoId) + "?billable=false")
                .andExpect(jsonPath("$.response[*].id", hasItem(internal)))
                .andExpect(jsonPath("$.response[*].id", not(hasItem(billable))));
    }

    @Test
    @DisplayName("Unconvertible filter values are 400 REQUEST_PARAMETER_INVALID, naming the fields and no Java types")
    void unconvertibleFilterValuesAre400() throws Exception {
        TestUser user = newUser();

        String body = getAs(user.accessToken(), entries(createTodo(user))
                + "?spentOnFrom=01-11-2026&spentOnTo=yesterday&minMinutes=few&maxMinutes=lots&billable=maybe&sort=LONGEST&size=many")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.failed", containsInAnyOrder(
                        "spentOnFrom: invalid value", "spentOnTo: invalid value", "minMinutes: invalid value",
                        "maxMinutes: invalid value", "billable: invalid value", "sort: invalid value", "size: invalid value")))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("java.", "com.example", "Exception");
    }

    @Test
    @DisplayName("A malformed todo id in the path is 400 REQUEST_PARAMETER_INVALID")
    void malformedTodoIdIs400() throws Exception {
        getAs(newUser().accessToken(), entries("not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"));
    }

    @Test
    @DisplayName("An unknown JSON field is 400 REQUEST_UNREADABLE")
    void unknownJsonFieldIs400() throws Exception {
        TestUser user = newUser();

        postAs(user.accessToken(), entries(createTodo(user)), """
                {"minutes": 10, "spentOn": "2026-11-01", "billable": false, "todoId": "%s"}
                """.formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"));
    }

    @Test
    @DisplayName("Concurrent updates with one version apply exactly once; the others are 409 OPTIMISTIC_LOCK_CONFLICT")
    void concurrentUpdatesWithOneVersionApplyOnce() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user);
        String id = createEntry(user, todoId, 30, "2026-11-01", false);

        List<Answer> answers = race(() -> putAs(user.accessToken(), entry(todoId, id), updatePayload(45, "2026-11-01", true, 0)));

        assertThat(answers).extracting(Answer::status).containsOnlyOnce(200).containsOnly(200, 409);
        assertRefusalsAre(answers, 409, "OPTIMISTIC_LOCK_CONFLICT");
        Long version = jdbc.queryForObject("select version from time_entries where id = ?::uuid", Long.class, id);
        assertThat(version).isEqualTo(1L);
    }

    @Test
    @DisplayName("A batch costs two statements (the todo's ownership check and the batch), for 1 row and for a full batch")
    void listRunsAConstantNumberOfQueries() throws Exception {
        TestUser user = newUser();
        String single = createTodo(user);
        createEntry(user, single, 10, "2026-11-01", false);
        String many = createTodo(user);
        for (int index = 0; index < 21; index++) {
            createEntry(user, many, 10 + index, "2026-11-01", false);
        }
        getAs(user.accessToken(), entries(many) + "?size=20")
                .andExpect(jsonPath("$.response.length()").value(20))
                .andExpect(jsonPath("$.pageInfo.hasMore").value(true));

        long forOne = statementsFor(user, entries(single) + "?size=20");
        long forBatch = statementsFor(user, entries(many) + "?size=20");

        // Two, not a root list's one: an unknown or foreign todo must answer 404, not an empty batch,
        // so the todo is read first. It stays two however many entries there are.
        assertThat(forOne).isEqualTo(2);
        assertThat(forBatch).isEqualTo(2);
    }

    /** Statements run by one list request: a cursor list never counts. */
    private long statementsFor(TestUser user, String path) throws Exception {
        statistics().clear();
        getAs(user.accessToken(), path).andExpect(status().isOk());
        return statistics().getPrepareStatementCount();
    }
}
