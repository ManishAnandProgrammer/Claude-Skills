package com.example.app.todo;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Merging one tag into another (TagMergeManager): a use case that writes two aggregates in one transaction,
 * the todos' tag rows and the tags. Each test's user is fresh, so its tags and todos are its own.
 */
class TagMergeIntegrationTest extends IntegrationTest {

    private static final String TODOS = "/api/v1/todos";
    private static final String TAGS = "/api/v1/tags";

    @Autowired
    private PlatformTransactionManager transactionManager;

    private String createList(TestUser user) throws Exception {
        return read(postAs(user.accessToken(), "/api/v1/todo-lists", """
                {"name": "Merged %s"}
                """.formatted(UUID.randomUUID())).andExpect(status().isCreated()), "$.response.id");
    }

    private String createTag(TestUser user, String name) throws Exception {
        return read(postAs(user.accessToken(), TAGS, """
                {"name": "%s", "color": null}
                """.formatted(name)).andExpect(status().isCreated()), "$.response.id");
    }

    private static String idsJson(List<String> ids) {
        return ids.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(", ", "[", "]"));
    }

    private String createTodo(TestUser user, String listId, List<String> tagIds) throws Exception {
        return read(postAs(user.accessToken(), TODOS, """
                {"todoListId": "%s", "title": "Tagged", "priority": "LOW", "tagIds": %s}
                """.formatted(listId, idsJson(tagIds))).andExpect(status().isCreated()), "$.response.id");
    }

    /** Merges {@code sourceId} into {@code targetId}. */
    private ResultActions merge(TestUser user, String sourceId, String targetId) throws Exception {
        return postAs(user.accessToken(), TAGS + "/" + targetId + "/merge", """
                {"sourceTagId": "%s"}
                """.formatted(sourceId));
    }

    private ResultActions putTags(TestUser user, String todoId, List<String> tagIds, long version) throws Exception {
        return putAs(user.accessToken(), TODOS + "/" + todoId + "/tags", """
                {"tagIds": %s, "version": %d}
                """.formatted(idsJson(tagIds), version));
    }

    /** The todo's tags, in id order, read from the table. */
    private List<String> tagsOf(String todoId) {
        return jdbc.queryForList("select tag_id::text from todo_tags where todo_id = ?::uuid order by tag_id", String.class, todoId);
    }

    private long versionOf(String todoId) {
        return jdbc.queryForObject("select version from todos where id = ?::uuid", Long.class, todoId);
    }

    private boolean tagExists(String tagId) {
        return jdbc.queryForObject("select count(*) from tags where id = ?::uuid", Integer.class, tagId) == 1;
    }

    @Test
    @DisplayName("""
            A merge moves the source's todos to the target (once, when a todo carried both), bumps their versions, \
            deletes the source and answers 200 with the target unchanged; other todos and tags stay as they were""")
    void mergeMovesTheSourcesTodosAndDeletesIt() throws Exception {
        TestUser user = newUser();
        String listId = createList(user);
        String source = createTag(user, "Source");
        String target = createTag(user, "Target");
        String other = createTag(user, "Other");
        String sourceOnly = createTodo(user, listId, List.of(source));
        String both = createTodo(user, listId, List.of(source, target));
        String targetOnly = createTodo(user, listId, List.of(target));
        String untouched = createTodo(user, listId, List.of(other));

        merge(user, source, target)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.id").value(target))
                .andExpect(jsonPath("$.response.name").value("Target"))
                .andExpect(jsonPath("$.response.version").value(0));

        assertThat(tagsOf(sourceOnly)).containsExactly(target);
        assertThat(tagsOf(both)).containsExactly(target);
        assertThat(tagsOf(targetOnly)).containsExactly(target);
        assertThat(tagsOf(untouched)).containsExactly(other);
        assertThat(versionOf(sourceOnly)).isOne();
        assertThat(versionOf(both)).isOne();
        assertThat(versionOf(targetOnly)).isZero();
        assertThat(versionOf(untouched)).isZero();
        getAs(user.accessToken(), TODOS + "/" + sourceOnly)
                .andExpect(jsonPath("$.response.tags[*].name", contains("Target")))
                .andExpect(jsonPath("$.response.version").value(1));
        getAs(user.accessToken(), TAGS + "/" + source)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TAG_NOT_FOUND"));
        // A client holding a todo's copy from before the merge holds a stale version.
        putTags(user, sourceOnly, List.of(source), 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK_CONFLICT"));
    }

    @Test
    @DisplayName("""
            A source or target that is missing or another user's is 404 TAG_NOT_FOUND naming it; nothing changes, \
            on either side""")
    void anUnknownOrForeignTagIs404AndChangesNothing() throws Exception {
        TestUser user = newUser();
        TestUser stranger = newUser();
        String listId = createList(user);
        String source = createTag(user, "Source");
        String target = createTag(user, "Target");
        String foreign = createTag(stranger, "Foreign");
        String todo = createTodo(user, listId, List.of(source));
        String foreignTodo = createTodo(stranger, createList(stranger), List.of(foreign));
        String unknown = UUID.randomUUID().toString();

        // Each case: the source, the target, and the one of them the answer names.
        List<List<String>> cases = List.of(
                List.of(source, unknown, unknown),
                List.of(unknown, target, unknown),
                List.of(source, foreign, foreign),
                List.of(foreign, target, foreign));
        for (List<String> refused : cases) {
            merge(user, refused.get(0), refused.get(1))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("TAG_NOT_FOUND"))
                    .andExpect(jsonPath("$.detail", containsString(refused.get(2))));
        }
        merge(stranger, source, target)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TAG_NOT_FOUND"));

        assertThat(tagsOf(todo)).containsExactly(source);
        assertThat(versionOf(todo)).isZero();
        assertThat(tagsOf(foreignTodo)).containsExactly(foreign);
        assertThat(tagExists(source)).isTrue();
        assertThat(tagExists(target)).isTrue();
        assertThat(tagExists(foreign)).isTrue();
    }

    @Test
    @DisplayName("A tag merged into itself is 400 TAG_MERGE_INTO_ITSELF naming it; the tag and its todos stay")
    void aTagMergedIntoItselfIs400() throws Exception {
        TestUser user = newUser();
        String tag = createTag(user, "Alone");
        String todo = createTodo(user, createList(user), List.of(tag));

        merge(user, tag, tag)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("TAG_MERGE_INTO_ITSELF"))
                .andExpect(jsonPath("$.detail").value("Tag %s can't be merged into itself: name another tag as the source.".formatted(tag)));

        assertThat(tagsOf(todo)).containsExactly(tag);
        assertThat(tagExists(tag)).isTrue();
    }

    @Test
    @DisplayName("Without sourceTagId a merge is 400 VALIDATION_FAILED naming it; an unknown field or a malformed id is 400 REQUEST_UNREADABLE")
    void aMergeNeedsItsSourceInItsForm() throws Exception {
        TestUser user = newUser();
        String target = createTag(user, "Target");

        postAs(user.accessToken(), TAGS + "/" + target + "/merge", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].pointer", contains("#/sourceTagId")));
        postAs(user.accessToken(), TAGS + "/" + target + "/merge", """
                {"sourceTagId": "%s", "keepSource": true}
                """.formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"));
        postAs(user.accessToken(), TAGS + "/" + target + "/merge", """
                {"sourceTagId": "not-a-uuid"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"));
    }

    /**
     * Runs {@code merge} while the test holds the tags table in SHARE mode: the merge's locks on the tags pass
     * (row locks), its todo half runs, and its delete of the source waits. Once it waits there, runs
     * {@code meanwhile} on its own thread and waits until that finishes or waits on a lock too; then opens the
     * gate. Returns the merge's answer, then {@code meanwhile}'s.
     */
    private List<Answer> mergeHeldAtTheSourcesDelete(Callable<ResultActions> merge, Callable<ResultActions> meanwhile)
            throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch gateClosed = new CountDownLatch(1);
        CountDownLatch openGate = new CountDownLatch(1);
        Future<Answer> merged;
        Future<Answer> other = null;
        try (ExecutorService executor = newExecutor()) {
            Future<?> gate = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.execute("lock table tags in share mode");
                gateClosed.countDown();
                awaitQuietly(openGate);
            }));
            awaitLatch(gateClosed);
            try {
                long deadline = lockWaitDeadline();
                Callable<Answer> mergeOnce = () -> answerOf(merge.call());
                merged = executor.submit(mergeOnce);
                awaitLockWaiters(1, merged, deadline);
                if (meanwhile != null) {
                    Callable<Answer> meanwhileOnce = () -> answerOf(meanwhile.call());
                    other = executor.submit(meanwhileOnce);
                    awaitLockWaiters(2, other, deadline);
                } else {
                    within(merged);
                }
            } finally {
                openGate.countDown();
            }
            within(gate);
            List<Answer> answers = new ArrayList<>();
            answers.add(within(merged));
            if (other != null) {
                answers.add(within(other));
            }
            return answers;
        }
    }

    /**
     * The Manager's transaction is the only one: when the merge fails after its todo half ran (here the source's
     * delete waits past the lock timeout), the todos' rows and versions roll back with the tags. With the todo
     * half in a transaction of its own, the links would stay moved to the target while the source stays too.
     */
    @Test
    @DisplayName("""
            A merge that fails at the source's delete (503 DATABASE_CONTENTION after the lock timeout) rolls back \
            the todos too: links, versions and both tags as before""")
    void aMergeFailingAfterItsTodoHalfRollsBackBoth() throws Exception {
        TestUser user = newUser();
        String listId = createList(user);
        String source = createTag(user, "Source");
        String target = createTag(user, "Target");
        String todo = createTodo(user, listId, List.of(source));

        List<Answer> answers = mergeHeldAtTheSourcesDelete(() -> merge(user, source, target), null);

        assertThat(answers.getFirst().status()).isEqualTo(503);
        assertRefusalsAre(answers, 503, "DATABASE_CONTENTION");
        assertThat(tagsOf(todo)).containsExactly(source);
        assertThat(versionOf(todo)).isZero();
        assertThat(tagExists(source)).isTrue();
        assertThat(tagExists(target)).isTrue();
        merge(user, source, target).andExpect(status().isOk());
        assertThat(tagsOf(todo)).containsExactly(target);
    }

    /**
     * The source's key lock: a todo tagged with the source while the merge runs waits at its link's foreign key
     * check, and fails on the key once the source is deleted, 404 TAG_GONE. With the plain write lock the link
     * is stored at once, after the merge moved the links it saw, and the source's delete takes it away: the
     * todo would end with neither tag, and its request answered 200.
     */
    @Test
    @DisplayName("A todo tagged with the source while the merge runs waits for it, then is 404 TAG_GONE: no link is lost unmoved")
    void aTodoTaggedWithTheSourceDuringTheMergeIsRefused() throws Exception {
        TestUser user = newUser();
        String listId = createList(user);
        String source = createTag(user, "Source");
        String target = createTag(user, "Target");
        String moved = createTodo(user, listId, List.of(source));
        String late = createTodo(user, listId, List.of());

        List<Answer> answers = mergeHeldAtTheSourcesDelete(() -> merge(user, source, target),
                () -> putTags(user, late, List.of(source), 0));

        assertThat(answers.get(0).status()).isEqualTo(200);
        assertThat(answers.get(1).status()).isEqualTo(404);
        assertRefusalsAre(answers.subList(1, 2), 404, "TAG_GONE");
        assertThat(tagsOf(moved)).containsExactly(target);
        assertThat(tagsOf(late)).isEmpty();
        assertThat(versionOf(late)).isZero();
    }

    /**
     * Merges of three tags into each other, every direction at once: each locks both of its tags, the lower id
     * first, so two merges of one pair queue (the second finds a tag gone, 404) instead of deadlocking (503).
     * Whatever wins, every todo keeps a tag: all of its tags merged into the ones still there.
     */
    @Test
    @DisplayName("""
            Concurrent merges of three tags into each other, in every direction: each 200 or 404 TAG_NOT_FOUND, \
            never 503 or 500; every todo still carries a tag, only tags still there""")
    void concurrentMergesLoseNoLinkAndNeverDeadlock() throws Exception {
        TestUser user = newUser();
        String listId = createList(user);
        List<String> tags = List.of(createTag(user, "A"), createTag(user, "B"), createTag(user, "C"));
        List<String> todos = new ArrayList<>();
        for (List<Integer> carried : List.of(List.of(0), List.of(1), List.of(2), List.of(0, 1), List.of(1, 2), List.of(0, 1, 2))) {
            List<String> tagIds = carried.stream().map(tags::get).toList();
            todos.add(createTodo(user, listId, tagIds));
        }
        // Each racer one ordered pair (source, target): A->B, B->A, B->C, C->B, A->C, C->A.
        List<List<Integer>> pairs = List.of(List.of(0, 1), List.of(1, 0), List.of(1, 2), List.of(2, 1), List.of(0, 2), List.of(2, 0));
        AtomicInteger racer = new AtomicInteger();

        List<Answer> answers = race(() -> {
            List<Integer> pair = pairs.get(racer.getAndIncrement() % pairs.size());
            return merge(user, tags.get(pair.get(0)), tags.get(pair.get(1)));
        });

        assertThat(answers).extracting(Answer::status).contains(200).isSubsetOf(200, 404);
        assertRefusalsAre(answers, 404, "TAG_NOT_FOUND");
        long merged = answers.stream().filter(answer -> answer.status() == 200).count();
        List<String> left = tags.stream().filter(this::tagExists).toList();
        assertThat(left).hasSize(3 - (int) merged);
        for (String todo : todos) {
            assertThat(tagsOf(todo)).as("tags of todo %s", todo).isNotEmpty().isSubsetOf(left);
        }
    }

    /**
     * Merges of different tags whose todos are the same: each locks those todos in id order, so they queue and
     * all succeed. Every todo ends with the targets only.
     */
    @Test
    @DisplayName("Concurrent merges of different tags carried by the same todos all answer 200, never 503: they lock the todos in one order")
    void concurrentMergesSharingTodosAllApply() throws Exception {
        TestUser user = newUser();
        String listId = createList(user);
        List<String> sources = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        for (int index = 0; index < RACERS; index++) {
            sources.add(createTag(user, "Source " + index));
            targets.add(createTag(user, "Target " + index));
        }
        List<String> todos = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            todos.add(createTodo(user, listId, sources));
        }
        AtomicInteger racer = new AtomicInteger();

        List<Answer> answers = race(() -> {
            int index = racer.getAndIncrement();
            return merge(user, sources.get(index), targets.get(index));
        });

        assertThat(answers).extracting(Answer::status).containsOnly(200);
        List<String> expected = targets.stream().sorted().toList();
        for (String todo : todos) {
            assertThat(tagsOf(todo)).containsExactlyElementsOf(expected);
            assertThat(versionOf(todo)).isEqualTo(RACERS);
        }
    }

    private long statementsForMerge(TestUser user, String source, String target) throws Exception {
        statistics().clear();
        merge(user, source, target).andExpect(status().isOk());
        return statistics().getPrepareStatementCount();
    }

    @Test
    @DisplayName("A merge costs the same statements for one todo as for twenty: it never loads the todos")
    void aMergeRunsAConstantNumberOfStatements() throws Exception {
        TestUser one = newUser();
        String oneSource = createTag(one, "Source");
        createTodo(one, createList(one), List.of(oneSource));
        TestUser many = newUser();
        String manySource = createTag(many, "Source");
        String manyList = createList(many);
        for (int index = 0; index < 20; index++) {
            createTodo(many, manyList, List.of(manySource));
        }

        long forOne = statementsForMerge(one, oneSource, createTag(one, "Target"));
        long forMany = statementsForMerge(many, manySource, createTag(many, "Target"));

        // Both tags' locks, the todos' lock, their versions, the new rows, the old rows, the source's delete.
        assertThat(forOne).isEqualTo(7);
        assertThat(forMany).isEqualTo(forOne);
    }

    @Test
    @DisplayName("The API docs describe the merge's refusals")
    void apiDocsDescribeTheMerge() throws Exception {
        getAs(null, "/v3/api-docs")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/tags/{id}/merge'].post.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/tags/{id}/merge'].post.responses['400'].description",
                        containsString("TAG_MERGE_INTO_ITSELF")))
                .andExpect(jsonPath("$.paths['/api/v1/tags/{id}/merge'].post.responses['404'].description",
                        containsString("TAG_NOT_FOUND")))
                .andExpect(jsonPath("$.paths['/api/v1/tags/{id}/merge'].post.description", containsString("TAG_GONE")));
    }
}
