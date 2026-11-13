package com.example.app.todo;

import com.example.app.support.IntegrationTest;
import com.example.app.todo.domain.TodoPriority;
import com.example.app.todo.domain.TodoStatus;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TodoIntegrationTest extends IntegrationTest {

    private static final String TODOS = "/api/v1/todos";
    private static final String LISTS = "/api/v1/todo-lists";
    private static final int RACERS = 6;
    /** Below the pool size (10) with the delete, the gate and the polling connection. */
    private static final int CREATORS = 4;
    /** Under lock_timeout (3 s), so a request held by a test lock never times out first. */
    private static final Duration LOCK_WAIT_DEADLINE = Duration.ofSeconds(2);

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** One answer of a raced request: its status and body, so every refusal's body can be checked. */
    private record Answer(int status, String body) {

        String errorCode() {
            return JsonPath.read(body, "$.errorCode");
        }
    }

    private String createList(TestUser user, String name) throws Exception {
        return read(postAs(user.accessToken(), LISTS, """
                {"name": "%s"}
                """.formatted(name)).andExpect(status().isCreated()), "$.response.id");
    }

    private static String todoPayload(String listId, String title, String priority, String dueDate) {
        String due = Objects.isNull(dueDate) ? "null" : "\"" + dueDate + "\"";
        return """
                {"todoListId": "%s", "title": "%s", "notes": "Some notes", "priority": "%s", "dueDate": %s}
                """.formatted(listId, title, priority, due);
    }

    private static String updatePayload(String listId, String title, long version) {
        return """
                {"todoListId": "%s", "title": "%s", "notes": null, "priority": "HIGH", "dueDate": "2026-12-01", "version": %d}
                """.formatted(listId, title, version);
    }

    private static String statusPayload(String status, long version) {
        return """
                {"status": "%s", "version": %d}
                """.formatted(status, version);
    }

    /** Creates a todo as {@code user} in {@code listId}; fails the test unless it answers 201. */
    private String createTodo(TestUser user, String listId, String title, String priority, String dueDate) throws Exception {
        return read(postAs(user.accessToken(), TODOS, todoPayload(listId, title, priority, dueDate))
                .andExpect(status().isCreated()), "$.response.id");
    }

    private static Answer answerOf(ResultActions result) throws Exception {
        MockHttpServletResponse response = result.andReturn().getResponse();
        return new Answer(response.getStatus(), response.getContentAsString());
    }

    /** A refusal names no constraint, SQL or Java internals anywhere in its body. */
    private static void assertNoInternals(String body) {
        assertThat(body).doesNotContain("uq_", "fk_", "SQL", "Exception", "java.");
    }

    private static void assertRefusalsAre(List<Answer> answers, int status, String errorCode) {
        for (Answer answer : answers) {
            if (answer.status() == status) {
                assertThat(answer.errorCode()).isEqualTo(errorCode);
                assertNoInternals(answer.body());
            }
        }
    }

    /** Fires {@code request} from {@link #RACERS} threads released together; returns every answer. */
    private static List<Answer> race(Callable<ResultActions> request) throws Exception {
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

    /**
     * Follows {@code pageInfo.nextCursor} from {@code firstBatch} (a path with its query string) to
     * the last batch, which must say {@code hasMore} false with no cursor; returns each batch's titles.
     */
    private List<List<String>> walk(TestUser user, String firstBatch) throws Exception {
        List<List<String>> batches = new ArrayList<>();
        String next = firstBatch;
        while (Objects.nonNull(next)) {
            assertThat(batches).hasSizeLessThan(20);
            ResultActions batch = getAs(user.accessToken(), next).andExpect(status().isOk());
            batches.add(read(batch, "$.response[*].title"));
            if (Boolean.TRUE.equals(read(batch, "$.pageInfo.hasMore"))) {
                next = firstBatch + "&after=" + read(batch, "$.pageInfo.nextCursor");
            } else {
                batch.andExpect(jsonPath("$.pageInfo.nextCursor").doesNotExist());
                next = null;
            }
        }
        return batches;
    }

    private ResultActions getWithQuery(TestUser user, String query) throws Exception {
        return mvc.perform(get(TODOS).param("query", query).header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()));
    }

    @Test
    @DisplayName("Create answers 201 with every field, OPEN, version 0 and the audit fields")
    void createReturns201WithTheSavedEntity() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Home");

        postAs(user.accessToken(), TODOS, todoPayload(listId, "  Water the plants  ", "LOW", "2026-11-20"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.id").isNotEmpty())
                .andExpect(jsonPath("$.response.title").value("Water the plants"))
                .andExpect(jsonPath("$.response.notes").value("Some notes"))
                .andExpect(jsonPath("$.response.todoListId").value(listId))
                .andExpect(jsonPath("$.response.ownerId").value(user.id().toString()))
                .andExpect(jsonPath("$.response.status").value("OPEN"))
                .andExpect(jsonPath("$.response.priority").value("LOW"))
                .andExpect(jsonPath("$.response.dueDate").value("2026-11-20"))
                .andExpect(jsonPath("$.response.completedAt").value(nullValue()))
                .andExpect(jsonPath("$.response.version").value(0))
                .andExpect(jsonPath("$.response.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.response.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.response.createdBy").value(user.id().toString()))
                .andExpect(jsonPath("$.response.updatedBy").value(user.id().toString()));
    }

    @Test
    @DisplayName("Creating a todo in someone else's list is 404 TODO_LIST_NOT_FOUND")
    void createInAnotherUsersListIs404() throws Exception {
        TestUser owner = newUser();
        String othersList = createList(owner, "Not yours");

        postAs(newUser().accessToken(), TODOS, todoPayload(othersList, "Sneak in", "LOW", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_LIST_NOT_FOUND"));
    }

    @Test
    @DisplayName("Get returns the todo with its notes")
    void getReturnsTheEntity() throws Exception {
        TestUser user = newUser();
        String id = createTodo(user, createList(user, "Read"), "A book", "MEDIUM", null);

        getAs(user.accessToken(), TODOS + "/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.id").value(id))
                .andExpect(jsonPath("$.response.title").value("A book"))
                .andExpect(jsonPath("$.response.notes").value("Some notes"));
    }

    @Test
    @DisplayName("Get of an unknown id is 404 TODO_NOT_FOUND")
    void getUnknownIdIs404() throws Exception {
        getAs(newUser().accessToken(), TODOS + "/" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
    }

    @Test
    @DisplayName("Update edits the todo, can move it to another list, and increments the version")
    void updateChangesTheFieldsAndBumpsTheVersion() throws Exception {
        TestUser user = newUser();
        String id = createTodo(user, createList(user, "First"), "Draft", "LOW", null);
        String secondList = createList(user, "Second");

        putAs(user.accessToken(), TODOS + "/" + id, updatePayload(secondList, "Final", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.title").value("Final"))
                .andExpect(jsonPath("$.response.todoListId").value(secondList))
                .andExpect(jsonPath("$.response.priority").value("HIGH"))
                .andExpect(jsonPath("$.response.dueDate").value("2026-12-01"))
                .andExpect(jsonPath("$.response.notes").value(nullValue()))
                .andExpect(jsonPath("$.response.status").value("OPEN"))
                .andExpect(jsonPath("$.response.version").value(1));
    }

    @Test
    @DisplayName("Update with a stale version is 409 OPTIMISTIC_LOCK_CONFLICT and changes nothing")
    void updateWithAStaleVersionIs409() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Stale");
        String id = createTodo(user, listId, "Original", "LOW", null);
        putAs(user.accessToken(), TODOS + "/" + id, updatePayload(listId, "First edit", 0)).andExpect(status().isOk());

        putAs(user.accessToken(), TODOS + "/" + id, updatePayload(listId, "Stale edit", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK_CONFLICT"));
        getAs(user.accessToken(), TODOS + "/" + id)
                .andExpect(jsonPath("$.response.title").value("First edit"))
                .andExpect(jsonPath("$.response.version").value(1));
    }

    @Test
    @DisplayName("Moving a todo into someone else's list is 404 TODO_LIST_NOT_FOUND")
    void moveIntoAnotherUsersListIs404() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Mine");
        String id = createTodo(user, listId, "Stay home", "LOW", null);
        String othersList = createList(newUser(), "Theirs");

        putAs(user.accessToken(), TODOS + "/" + id, updatePayload(othersList, "Escape", 0))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_LIST_NOT_FOUND"));
        getAs(user.accessToken(), TODOS + "/" + id).andExpect(jsonPath("$.response.todoListId").value(listId));
    }

    @Test
    @DisplayName("Marking a todo DONE sets completedAt; reopening clears it")
    void statusChangeKeepsCompletedAtInStep() throws Exception {
        TestUser user = newUser();
        String id = createTodo(user, createList(user, "Chores"), "Dishes", "MEDIUM", null);

        patchAs(user.accessToken(), TODOS + "/" + id + "/status", statusPayload("DONE", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("DONE"))
                .andExpect(jsonPath("$.response.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.response.version").value(1));
        patchAs(user.accessToken(), TODOS + "/" + id + "/status", statusPayload("OPEN", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("OPEN"))
                .andExpect(jsonPath("$.response.completedAt").value(nullValue()))
                .andExpect(jsonPath("$.response.version").value(2));
    }

    @Test
    @DisplayName("Asking for the status a todo already has changes nothing")
    void sameStatusIsANoOp() throws Exception {
        TestUser user = newUser();
        String id = createTodo(user, createList(user, "Idle"), "Nothing", "LOW", null);

        patchAs(user.accessToken(), TODOS + "/" + id + "/status", statusPayload("OPEN", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.version").value(0));
    }

    @Test
    @DisplayName("A status change with a stale version is 409 OPTIMISTIC_LOCK_CONFLICT and changes nothing")
    void statusChangeWithAStaleVersionIs409() throws Exception {
        TestUser user = newUser();
        String id = createTodo(user, createList(user, "Refusals"), "Task", "LOW", null);
        patchAs(user.accessToken(), TODOS + "/" + id + "/status", statusPayload("DONE", 0)).andExpect(status().isOk());

        patchAs(user.accessToken(), TODOS + "/" + id + "/status", statusPayload("OPEN", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK_CONFLICT"));
        getAs(user.accessToken(), TODOS + "/" + id)
                .andExpect(jsonPath("$.response.status").value("DONE"))
                .andExpect(jsonPath("$.response.version").value(1));
    }

    @Test
    @DisplayName("An unknown status or priority in the body is 400 REQUEST_UNREADABLE")
    void unknownEnumInTheBodyIs400() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Enums");
        String id = createTodo(user, listId, "Task", "LOW", null);

        patchAs(user.accessToken(), TODOS + "/" + id + "/status", statusPayload("ARCHIVED", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"));
        postAs(user.accessToken(), TODOS, todoPayload(listId, "x", "URGENT", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"));
    }

    @Test
    @DisplayName("PUT, PATCH status and DELETE on an unknown id are 404 TODO_NOT_FOUND")
    void writesToAnUnknownIdAre404() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Unknown");
        String unknown = TODOS + "/" + UUID.randomUUID();

        putAs(user.accessToken(), unknown, updatePayload(listId, "Ghost", 0))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
        patchAs(user.accessToken(), unknown + "/status", statusPayload("DONE", 0))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
        deleteAs(user.accessToken(), unknown)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
    }

    @Test
    @DisplayName("Delete answers 204 and the todo is gone")
    void deleteReturns204AndTheEntityIsGone() throws Exception {
        TestUser user = newUser();
        String id = createTodo(user, createList(user, "Bin"), "Throw away", "LOW", null);
        getAs(user.accessToken(), TODOS + "/" + id).andExpect(status().isOk());

        deleteAs(user.accessToken(), TODOS + "/" + id).andExpect(status().isNoContent());
        getAs(user.accessToken(), TODOS + "/" + id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
    }

    @Test
    @DisplayName("Deleting a list that still has todos is 409 TODO_LIST_IN_USE, without internals in the body")
    void deleteWhileReferencedIs409() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Busy");
        createTodo(user, listId, "Keep me", "LOW", null);

        String body = deleteAs(user.accessToken(), LISTS + "/" + listId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TODO_LIST_IN_USE"))
                .andReturn().getResponse().getContentAsString();
        assertNoInternals(body);
        getAs(user.accessToken(), LISTS + "/" + listId).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Todo creates racing their list's delete: creates are 201 or 404, the delete 204 or 409, no todo outlives its list")
    void createsRacingTheListDeleteNeverOrphanATodo() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Contested");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch gateClosed = new CountDownLatch(1);
        CountDownLatch openGate = new CountDownLatch(1);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Answer>> creates = new ArrayList<>();
        Future<Answer> delete;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Holds every todo insert until the delete has run, so the delete lands between each
            // create's read of the list and its insert: the interleaving the FOR SHARE lock is for.
            Future<?> gate = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.execute("lock table todos in share mode");
                gateClosed.countDown();
                awaitQuietly(openGate);
            }));
            gateClosed.await();
            try {
                for (int creator = 0; creator < CREATORS; creator++) {
                    Callable<Answer> create = () -> {
                        start.await();
                        return answerOf(postAs(user.accessToken(), TODOS, todoPayload(listId, "Racer", "LOW", null)));
                    };
                    creates.add(executor.submit(create));
                }
                start.countDown();
                awaitLockWaiters(CREATORS, null);
                delete = executor.submit(() -> answerOf(deleteAs(user.accessToken(), LISTS + "/" + listId)));
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

        assertThat(createAnswers).extracting(Answer::status).isSubsetOf(201, 404);
        assertRefusalsAre(createAnswers, 404, "TODO_LIST_NOT_FOUND");
        assertThat(deleteAnswer.status()).isIn(204, 409);
        assertRefusalsAre(List.of(deleteAnswer), 409, "TODO_LIST_IN_USE");
        long created = createAnswers.stream().filter(answer -> answer.status() == 201).count();
        Integer lists = jdbc.queryForObject("select count(*) from todo_lists where id = ?::uuid", Integer.class, listId);
        Integer todos = jdbc.queryForObject("select count(*) from todos where todo_list_id = ?::uuid", Integer.class, listId);
        if (lists == 0) {
            assertThat(deleteAnswer.status()).isEqualTo(204);
            assertThat(created).isZero();
            assertThat(todos).isZero();
        } else {
            assertThat(deleteAnswer.status()).isEqualTo(409);
            assertThat(todos).isEqualTo((int) created);
        }
    }

    /**
     * Waits until {@code sessions} sessions of this database wait on a lock, or {@code done} has
     * finished; fails past {@link #LOCK_WAIT_DEADLINE}.
     */
    private void awaitLockWaiters(int sessions, Future<?> done) throws InterruptedException {
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

    @Test
    @DisplayName("Create without a list, title or priority is 400 naming each field")
    void createWithoutRequiredFieldsIs400() throws Exception {
        postAs(newUser().accessToken(), TODOS, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed", hasItem(containsString("todoListId"))))
                .andExpect(jsonPath("$.failed", hasItem(containsString("title"))))
                .andExpect(jsonPath("$.failed", hasItem(containsString("priority"))));
    }

    @Test
    @DisplayName("A blank title is 400 VALIDATION_FAILED naming title")
    void createWithABlankTitleIs400() throws Exception {
        TestUser user = newUser();

        postAs(user.accessToken(), TODOS, todoPayload(createList(user, "Blank"), "   ", "LOW", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value(containsString("title")));
    }

    @Test
    @DisplayName("An update or status change without version is 400 VALIDATION_FAILED naming version")
    void writesWithoutAVersionAre400() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Versionless");
        String id = createTodo(user, listId, "Task", "LOW", null);

        putAs(user.accessToken(), TODOS + "/" + id, """
                {"todoListId": "%s", "title": "x", "priority": "LOW"}
                """.formatted(listId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value(containsString("version")));
        patchAs(user.accessToken(), TODOS + "/" + id + "/status", """
                {"status": "DONE"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value(containsString("version")));
    }

    @Test
    @DisplayName("A title of 200 Gurmukhi letters is accepted; 201 characters is 400 with the configured limit")
    void titleLengthFollowsTheConfiguredLimit() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Title limits");

        createTodo(user, listId, "ਪ".repeat(200), "LOW", null);
        postAs(user.accessToken(), TODOS, todoPayload(listId, "t".repeat(201), "LOW", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value("title: must be at most 200 characters"));
    }

    @Test
    @DisplayName("Notes of 10,000 Gurmukhi letters are accepted; 10,001 characters is 400 with the configured limit")
    void notesLengthFollowsTheConfiguredLimit() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Notes limits");
        String notesPayload = """
                {"todoListId": "%s", "title": "Long notes", "notes": "%s", "priority": "LOW"}
                """;

        postAs(user.accessToken(), TODOS, notesPayload.formatted(listId, "ਨ".repeat(10_000)))
                .andExpect(status().isCreated());
        postAs(user.accessToken(), TODOS, notesPayload.formatted(listId, "n".repeat(10_001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value("notes: must be at most 10000 characters"));
    }

    @Test
    @DisplayName("Blank notes are stored as none (null), so \"no notes\" has one representation")
    void blankNotesAreStoredAsNone() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Blank notes");
        String notesPayload = """
                {"todoListId": "%s", "title": "Blank notes", "notes": "%s", "priority": "LOW"}
                """;

        postAs(user.accessToken(), TODOS, notesPayload.formatted(listId, "Kept"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.notes").value("Kept"));
        postAs(user.accessToken(), TODOS, notesPayload.formatted(listId, "   "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.notes").value(nullValue()));
        postAs(user.accessToken(), TODOS, notesPayload.formatted(listId, ""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.notes").value(nullValue()));
    }

    @Test
    @DisplayName("A due date on the configured latest date is accepted; the day after is 400, on create and update")
    void dueDateFollowsTheConfiguredLatestDate() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Far future");
        String id = createTodo(user, listId, "Last day", "LOW", "2999-12-31");

        postAs(user.accessToken(), TODOS, todoPayload(listId, "Too late", "LOW", "3000-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value("dueDate: must be on or before 2999-12-31"));
        putAs(user.accessToken(), TODOS + "/" + id, """
                {"todoListId": "%s", "title": "Too late", "priority": "LOW", "dueDate": "9999-12-31", "version": 0}
                """.formatted(listId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value("dueDate: must be on or before 2999-12-31"));
    }

    @Test
    @DisplayName("Markup in a title is stored and returned as text (escape-on-output policy)")
    void createWithAScriptTagStoresItAsText() throws Exception {
        TestUser user = newUser();
        String hostile = "<img src=x onerror=alert(1)>";

        postAs(user.accessToken(), TODOS, todoPayload(createList(user, "Hostile"), hostile, "LOW", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.title").value(hostile));
    }

    static Stream<Arguments> endpoints() {
        String someId = UUID.randomUUID().toString();
        return Stream.of(
                Arguments.of("GET", TODOS),
                Arguments.of("GET", TODOS + "/" + someId),
                Arguments.of("POST", TODOS),
                Arguments.of("PUT", TODOS + "/" + someId),
                Arguments.of("PATCH", TODOS + "/" + someId + "/status"),
                Arguments.of("DELETE", TODOS + "/" + someId));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("endpoints")
    @DisplayName("Every endpoint answers 401 AUTHENTICATION_REQUIRED without a token")
    void everyEndpointNeedsAToken(String method, String path) throws Exception {
        String anyList = UUID.randomUUID().toString();
        ResultActions result = switch (method) {
            case "GET" -> getAs(null, path);
            case "POST" -> postAs(null, path, todoPayload(anyList, "x", "LOW", null));
            case "PUT" -> putAs(null, path, updatePayload(anyList, "x", 0));
            case "PATCH" -> patchAs(null, path, statusPayload("DONE", 0));
            case "DELETE" -> deleteAs(null, path);
            default -> throw new IllegalArgumentException(method);
        };
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("Another user's todo is 404 on get, update, status and delete, and stays untouched")
    void anotherUsersRecordIs404() throws Exception {
        TestUser owner = newUser();
        TestUser stranger = newUser();
        String listId = createList(owner, "Owner's");
        String id = createTodo(owner, listId, "Private", "LOW", null);
        getAs(owner.accessToken(), TODOS + "/" + id).andExpect(status().isOk());

        getAs(stranger.accessToken(), TODOS + "/" + id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
        putAs(stranger.accessToken(), TODOS + "/" + id, updatePayload(createList(stranger, "Stranger's"), "Hijacked", 0))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
        patchAs(stranger.accessToken(), TODOS + "/" + id + "/status", statusPayload("DONE", 0))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
        deleteAs(stranger.accessToken(), TODOS + "/" + id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
        getAs(owner.accessToken(), TODOS + "/" + id)
                .andExpect(jsonPath("$.response.title").value("Private"))
                .andExpect(jsonPath("$.response.status").value("OPEN"))
                .andExpect(jsonPath("$.response.version").value(0));
    }

    @Test
    @DisplayName("Your batch holds only your todos, and is the last one")
    void listShowsOnlyYourTodos() throws Exception {
        TestUser user = newUser();
        createTodo(user, createList(user, "Mine"), "My todo", "LOW", null);
        TestUser other = newUser();
        createTodo(other, createList(other, "Theirs"), "Their todo", "LOW", null);

        getAs(user.accessToken(), TODOS)
                .andExpect(jsonPath("$.response[*].title", contains("My todo")))
                .andExpect(jsonPath("$.pageInfo.hasMore").value(false))
                .andExpect(jsonPath("$.pageInfo.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("Walking the cursor batches returns every todo once, by due date, then the undated ones in creation order")
    void cursorBatchesWalkTheWholeListInOrder() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Walk");
        String[][] fixtures = {
                {"Due 04", "2026-12-04"}, {"Due 01", "2026-12-01"}, {"Undated 1", null}, {"Due 06", "2026-12-06"},
                {"Due 02", "2026-12-02"}, {"Undated 2", null}, {"Due 05", "2026-12-05"}, {"Due 03", "2026-12-03"},
                {"Undated 3", null}};
        for (String[] fixture : fixtures) {
            createTodo(user, listId, fixture[0], "LOW", fixture[1]);
        }

        assertThat(walk(user, TODOS + "?size=4")).containsExactly(
                List.of("Due 01", "Due 02", "Due 03", "Due 04"),
                List.of("Due 05", "Due 06", "Undated 1", "Undated 2"),
                List.of("Undated 3"));
    }

    @Test
    @DisplayName("NEWEST batches walk to the end, newest first")
    void newestBatchesWalkToTheEnd() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Newest");
        createTodo(user, listId, "First", "LOW", "2026-12-03");
        createTodo(user, listId, "Second", "LOW", null);
        createTodo(user, listId, "Third", "LOW", "2026-12-01");
        createTodo(user, listId, "Fourth", "LOW", "2026-12-05");
        createTodo(user, listId, "Fifth", "LOW", "2026-12-02");

        assertThat(walk(user, TODOS + "?sort=NEWEST&size=2")).containsExactly(
                List.of("Fifth", "Fourth"), List.of("Third", "Second"), List.of("First"));
    }

    @Test
    @DisplayName("A size over the configured maximum is 400 VALIDATION_FAILED naming size")
    void sizeOverTheMaximumIs400() throws Exception {
        getAs(newUser().accessToken(), TODOS + "?size=101")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value("size: must be at most 100"));
    }

    @Test
    @DisplayName("List rows leave out the notes")
    void listRowsLeaveOutTheNotes() throws Exception {
        TestUser user = newUser();
        createTodo(user, createList(user, "Summary"), "Only", "LOW", null);

        getAs(user.accessToken(), TODOS)
                .andExpect(jsonPath("$.response[0].title").value("Only"))
                .andExpect(jsonPath("$.response[0].notes").doesNotExist());
    }

    @Test
    @DisplayName("A cursor from one sort is refused under another")
    void cursorFromAnotherSortIsRefused() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Sorts");
        createTodo(user, listId, "One", "LOW", null);
        createTodo(user, listId, "Two", "LOW", null);
        String newestCursor = read(getAs(user.accessToken(), TODOS + "?sort=NEWEST&size=1"), "$.pageInfo.nextCursor");

        getAs(user.accessToken(), TODOS + "?sort=DUE_DATE&size=1&after=" + newestCursor)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.parameter").value("after"));
    }

    @Test
    @DisplayName("A tampered cursor is 400 REQUEST_PARAMETER_INVALID naming after")
    void tamperedCursorIsRefused() throws Exception {
        getAs(newUser().accessToken(), TODOS + "?after=not-a-cursor")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.parameter").value("after"));
    }

    @Test
    @DisplayName("todoListId returns only that list's todos; another user's list id returns none of theirs")
    void filterByTodoListIdReturnsOnlyMatching() throws Exception {
        TestUser user = newUser();
        String work = createList(user, "Work");
        createTodo(user, work, "Report", "LOW", null);
        createTodo(user, createList(user, "Home"), "Laundry", "LOW", null);

        getAs(user.accessToken(), TODOS + "?todoListId=" + work)
                .andExpect(jsonPath("$.response[*].title", hasItem("Report")))
                .andExpect(jsonPath("$.response[*].title", not(hasItem("Laundry"))));
        getAs(newUser().accessToken(), TODOS + "?todoListId=" + work)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.length()").value(0));
    }

    @ParameterizedTest
    @EnumSource(TodoPriority.class)
    @DisplayName("priority returns only todos of that priority, for every priority")
    void filterByPriorityReturnsOnlyMatching(TodoPriority priority) throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Priorities");
        for (TodoPriority each : TodoPriority.values()) {
            createTodo(user, listId, "Task " + each, each.name(), null);
        }

        getAs(user.accessToken(), TODOS + "?priority=" + priority)
                .andExpect(jsonPath("$.response[*].title", contains("Task " + priority)));
    }

    @ParameterizedTest
    @EnumSource(TodoStatus.class)
    @DisplayName("status returns only todos in that status, for every status")
    void filterByStatusReturnsOnlyMatching(TodoStatus wanted) throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Statuses");
        createTodo(user, listId, "Still open", "LOW", null);
        String done = createTodo(user, listId, "Finished", "LOW", null);
        patchAs(user.accessToken(), TODOS + "/" + done + "/status", statusPayload("DONE", 0)).andExpect(status().isOk());
        String expected = wanted == TodoStatus.DONE ? "Finished" : "Still open";

        getAs(user.accessToken(), TODOS + "?status=" + wanted)
                .andExpect(jsonPath("$.response[*].title", contains(expected)));
    }

    @Test
    @DisplayName("dueOnOrBefore includes the day itself, not later or undated todos, under both sorts")
    void filterByDueOnOrBeforeReturnsOnlyMatching() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Due");
        createTodo(user, listId, "This week", "LOW", "2026-11-18");
        createTodo(user, listId, "Someday", "LOW", null);
        createTodo(user, listId, "On the day", "LOW", "2026-11-30");
        createTodo(user, listId, "Day after", "LOW", "2026-12-01");

        getAs(user.accessToken(), TODOS + "?dueOnOrBefore=2026-11-30")
                .andExpect(jsonPath("$.response[*].title", contains("This week", "On the day")));
        getAs(user.accessToken(), TODOS + "?dueOnOrBefore=2026-11-30&sort=NEWEST")
                .andExpect(jsonPath("$.response[*].title", contains("On the day", "This week")));
    }

    @Test
    @DisplayName("Unconvertible filter values are 400 REQUEST_PARAMETER_INVALID, naming the fields and no Java types")
    void unconvertibleFilterValuesAre400() throws Exception {
        TestUser user = newUser();

        String body = getAs(user.accessToken(),
                TODOS + "?status=FOO&priority=URGENT&sort=OLDEST&dueOnOrBefore=30-11-2026&todoListId=nope&size=many")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.failed", containsInAnyOrder(
                        "status: invalid value", "priority: invalid value", "sort: invalid value",
                        "dueOnOrBefore: invalid value", "todoListId: invalid value", "size: invalid value")))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("java.", "com.example", "Exception");
    }

    @Test
    @DisplayName("query matches part of the title, ignoring case; % in it matches only a %")
    void filterByQueryReturnsOnlyMatching() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Search");
        createTodo(user, listId, "Call the Dentist", "LOW", null);
        createTodo(user, listId, "Buy bread", "LOW", null);
        createTodo(user, listId, "100% sure", "LOW", null);

        getAs(user.accessToken(), TODOS + "?query=dent")
                .andExpect(jsonPath("$.response[*].title", contains("Call the Dentist")));
        getWithQuery(user, "%")
                .andExpect(jsonPath("$.response[*].title", contains("100% sure")));
    }

    @Test
    @DisplayName("A query of 100 characters is accepted; 101 is 400 with the configured limit")
    void queryLengthFollowsTheConfiguredLimit() throws Exception {
        TestUser user = newUser();
        createTodo(user, createList(user, "Long query"), "ਪ".repeat(100), "LOW", null);

        getWithQuery(user, "ਪ".repeat(100))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.length()").value(1));
        getWithQuery(user, "q".repeat(101))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value("query: must be at most 100 characters"));
    }

    @Test
    @DisplayName("The default order is by due date with undated todos last; NEWEST is newest first")
    void sortOrdersThePage() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Order");
        createTodo(user, listId, "Sooner", "LOW", "2026-11-14");
        createTodo(user, listId, "Undated", "LOW", null);
        createTodo(user, listId, "Later", "LOW", "2026-12-24");

        getAs(user.accessToken(), TODOS)
                .andExpect(jsonPath("$.response[*].title", contains("Sooner", "Later", "Undated")));
        getAs(user.accessToken(), TODOS + "?sort=NEWEST")
                .andExpect(jsonPath("$.response[*].title", contains("Later", "Undated", "Sooner")));
    }

    @Test
    @DisplayName("An unknown JSON field is 400 REQUEST_UNREADABLE")
    void unknownJsonFieldIs400() throws Exception {
        TestUser user = newUser();

        postAs(user.accessToken(), TODOS, """
                {"todoListId": "%s", "title": "x", "priority": "LOW", "status": "DONE"}
                """.formatted(createList(user, "Strict")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"));
    }

    @Test
    @DisplayName("Concurrent status changes with one version apply exactly once; the others are 409 OPTIMISTIC_LOCK_CONFLICT")
    void concurrentStatusChangesWithOneVersionApplyOnce() throws Exception {
        TestUser user = newUser();
        String id = createTodo(user, createList(user, "Race"), "Contested", "LOW", null);

        List<Answer> answers = race(() -> patchAs(user.accessToken(), TODOS + "/" + id + "/status", statusPayload("DONE", 0)));

        assertThat(answers).extracting(Answer::status).containsOnlyOnce(200).containsOnly(200, 409);
        assertRefusalsAre(answers, 409, "OPTIMISTIC_LOCK_CONFLICT");
        Long version = jdbc.queryForObject("select version from todos where id = ?::uuid", Long.class, id);
        assertThat(version).isEqualTo(1L);
    }

    @Test
    @DisplayName("Concurrent updates with one version apply exactly once; the others are 409 OPTIMISTIC_LOCK_CONFLICT")
    void concurrentUpdatesWithOneVersionApplyOnce() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Edits");
        String id = createTodo(user, listId, "Contested", "LOW", null);

        List<Answer> answers = race(() -> putAs(user.accessToken(), TODOS + "/" + id, updatePayload(listId, "Edited", 0)));

        assertThat(answers).extracting(Answer::status).containsOnlyOnce(200).containsOnly(200, 409);
        assertRefusalsAre(answers, 409, "OPTIMISTIC_LOCK_CONFLICT");
        Long version = jdbc.queryForObject("select version from todos where id = ?::uuid", Long.class, id);
        assertThat(version).isEqualTo(1L);
    }

    @Test
    @DisplayName("A batch of todos is one statement, for 1 row and for a full batch")
    void listRunsAConstantNumberOfQueries() throws Exception {
        TestUser one = newUser();
        createTodo(one, createList(one, "Single"), "Only", "LOW", null);
        TestUser many = newUser();
        for (int index = 0; index < 21; index++) {
            createTodo(many, createList(many, "List " + index), "Todo " + index, "LOW", null);
        }

        long forOne = statementsFor(one, TODOS);
        long forPage = statementsFor(many, TODOS);

        assertThat(forPage).isEqualTo(forOne).isEqualTo(1);
    }

    /** Statements run by one list request: a cursor list never counts, so always one. */
    private long statementsFor(TestUser user, String path) throws Exception {
        statistics().clear();
        getAs(user.accessToken(), path).andExpect(status().isOk());
        return statistics().getPrepareStatementCount();
    }

    @Test
    @DisplayName("A write that waits past lock_timeout answers 503 DATABASE_CONTENTION with Retry-After")
    void lockedRowAnswers503() throws Exception {
        TestUser user = newUser();
        String id = createTodo(user, createList(user, "Locked"), "Busy", "LOW", null);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> holder = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.queryForList("select id from todos where id = ?::uuid for update", id);
                locked.countDown();
                awaitQuietly(release);
            }));
            locked.await();
            try {
                patchAs(user.accessToken(), TODOS + "/" + id + "/status", statusPayload("DONE", 0))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.errorCode").value("DATABASE_CONTENTION"))
                        .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
            } finally {
                release.countDown();
            }
            holder.get();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("The API docs describe errors as application/problem+json")
    void apiDocsDescribeErrorsAsProblemJson() throws Exception {
        getAs(null, "/v3/api-docs")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/todos'].get.responses['400'].content['application/problem+json']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/todos/{id}'].put.responses['409'].content['application/problem+json']").exists());
    }
}
