package com.example.app.todo;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A todo's tags (TodoTag rows in todo_tags): set on create, replaced as a whole through
 * PUT /todos/{id}/tags, shown in the detail and the list rows, filtered by tagId, and taken off every
 * todo when the tag is deleted. Todo's other behaviour is TodoIntegrationTest's.
 */
class TodoTagsIntegrationTest extends IntegrationTest {

    private static final String TODOS = "/api/v1/todos";
    private static final String LISTS = "/api/v1/todo-lists";
    private static final String TAGS = "/api/v1/tags";
    /** app.limits.todo.max-tags in config/limits.yml. */
    private static final int MAX_TAGS = 10;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private String createList(TestUser user, String name) throws Exception {
        return read(postAs(user.accessToken(), LISTS, """
                {"name": "%s"}
                """.formatted(name)).andExpect(status().isCreated()), "$.response.id");
    }

    /** Creates a tag as {@code user}; fails the test unless it answers 201. */
    private String createTag(TestUser user, String name) throws Exception {
        return read(postAs(user.accessToken(), TAGS, """
                {"name": "%s", "color": null}
                """.formatted(name)).andExpect(status().isCreated()), "$.response.id");
    }

    private List<String> createTags(TestUser user, int count) throws Exception {
        List<String> tags = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            tags.add(createTag(user, "Tag " + index));
        }
        return tags;
    }

    private static String idsJson(List<String> ids) {
        return ids.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(", ", "[", "]"));
    }

    private static String createPayload(String listId, String title, List<String> tagIds) {
        return """
                {"todoListId": "%s", "title": "%s", "priority": "LOW", "tagIds": %s}
                """.formatted(listId, title, idsJson(tagIds));
    }

    private static String tagsPayload(List<String> tagIds, long version) {
        return """
                {"tagIds": %s, "version": %d}
                """.formatted(idsJson(tagIds), version);
    }

    /** Creates a todo carrying {@code tagIds}; fails the test unless it answers 201. */
    private String createTodo(TestUser user, String listId, String title, List<String> tagIds) throws Exception {
        return read(postAs(user.accessToken(), TODOS, createPayload(listId, title, tagIds))
                .andExpect(status().isCreated()), "$.response.id");
    }

    private ResultActions putTags(TestUser user, String todoId, List<String> tagIds, long version) throws Exception {
        return putAs(user.accessToken(), TODOS + "/" + todoId + "/tags", tagsPayload(tagIds, version));
    }

    private Integer tagRows(String column, String id) {
        return jdbc.queryForObject("select count(*) from todo_tags where " + column + " = ?::uuid", Integer.class, id);
    }

    private Long versionOf(String todoId) {
        return jdbc.queryForObject("select version from todos where id = ?::uuid", Long.class, todoId);
    }

    @Test
    @DisplayName("Create with tagIds answers 201 with the tags (id and name) in creation order")
    void createWithTagsReturnsThem() throws Exception {
        TestUser user = newUser();
        String home = createTag(user, "Home");
        String errands = createTag(user, "Errands");

        postAs(user.accessToken(), TODOS, createPayload(createList(user, "Tagged"), "Shop", List.of(errands, home)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.tags[*].id", contains(home, errands)))
                .andExpect(jsonPath("$.response.tags[*].name", contains("Home", "Errands")))
                .andExpect(jsonPath("$.response.version").value(0));
    }

    @Test
    @DisplayName("Create without tagIds, or with null or [], has no tags")
    void createWithoutTagsHasNone() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Untagged");

        postAs(user.accessToken(), TODOS, """
                {"todoListId": "%s", "title": "Absent", "priority": "LOW"}
                """.formatted(listId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.tags", empty()));
        postAs(user.accessToken(), TODOS, """
                {"todoListId": "%s", "title": "Null", "priority": "LOW", "tagIds": null}
                """.formatted(listId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.tags", empty()));
        postAs(user.accessToken(), TODOS, createPayload(listId, "Empty", List.of()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.tags", empty()));
    }

    @Test
    @DisplayName("Get returns the todo's tags")
    void getReturnsTheTags() throws Exception {
        TestUser user = newUser();
        String tag = createTag(user, "Reading");
        String id = createTodo(user, createList(user, "Books"), "A novel", List.of(tag));

        getAs(user.accessToken(), TODOS + "/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.tags[*].id", contains(tag)))
                .andExpect(jsonPath("$.response.tags[*].name", contains("Reading")));
    }

    @Test
    @DisplayName("PUT tags replaces them as a whole and increments the version; the same set again changes nothing; [] removes them all")
    void replaceTagsReplacesTheSetAndBumpsTheVersion() throws Exception {
        TestUser user = newUser();
        String first = createTag(user, "First");
        String second = createTag(user, "Second");
        String third = createTag(user, "Third");
        String id = createTodo(user, createList(user, "Replace"), "Same title", List.of(first, second));
        assertThat(tagRows("tag_id", first)).isEqualTo(1);

        putTags(user, id, List.of(third, second), 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.tags[*].id", contains(second, third)))
                .andExpect(jsonPath("$.response.title").value("Same title"))
                .andExpect(jsonPath("$.response.version").value(1));
        assertThat(tagRows("todo_id", id)).isEqualTo(2);
        assertThat(tagRows("tag_id", first)).isZero();

        putTags(user, id, List.of(second, third), 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.version").value(1));
        putTags(user, id, List.of(), 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.tags", empty()))
                .andExpect(jsonPath("$.response.version").value(2));
        assertThat(tagRows("todo_id", id)).isZero();
    }

    @Test
    @DisplayName("The todo's PUT keeps its tags, and refuses a tagIds field: tags change only through PUT .../tags")
    void todoUpdateLeavesTheTagsAlone() throws Exception {
        TestUser user = newUser();
        String tag = createTag(user, "Kept");
        String listId = createList(user, "Keep");
        String id = createTodo(user, listId, "Tagged", List.of(tag));

        putAs(user.accessToken(), TODOS + "/" + id, """
                {"todoListId": "%s", "title": "Renamed", "priority": "LOW", "version": 0}
                """.formatted(listId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.title").value("Renamed"))
                .andExpect(jsonPath("$.response.tags[*].id", contains(tag)));
        putAs(user.accessToken(), TODOS + "/" + id, """
                {"todoListId": "%s", "title": "Renamed", "priority": "LOW", "tagIds": [], "version": 1}
                """.formatted(listId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"));
        assertThat(tagRows("todo_id", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("PUT tags with a stale version is 409 OPTIMISTIC_LOCK_CONFLICT and changes nothing")
    void replaceTagsWithAStaleVersionIs409() throws Exception {
        TestUser user = newUser();
        String kept = createTag(user, "Kept");
        String other = createTag(user, "Other");
        String id = createTodo(user, createList(user, "Stale"), "Tagged", List.of(kept));
        putTags(user, id, List.of(kept, other), 0).andExpect(status().isOk());

        putTags(user, id, List.of(), 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK_CONFLICT"));

        getAs(user.accessToken(), TODOS + "/" + id)
                .andExpect(jsonPath("$.response.tags[*].id", contains(kept, other)))
                .andExpect(jsonPath("$.response.version").value(1));
    }

    @Test
    @DisplayName("PUT tags without tagIds or version is 400 VALIDATION_FAILED naming each")
    void replaceTagsWithoutTagIdsOrVersionIs400() throws Exception {
        TestUser user = newUser();
        String id = createTodo(user, createList(user, "Required"), "Todo", List.of());

        putAs(user.accessToken(), TODOS + "/" + id + "/tags", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed", hasItem(startsWith("tagIds"))))
                .andExpect(jsonPath("$.failed", hasItem(startsWith("version"))));
    }

    @Test
    @DisplayName("Another user's tag is 404 TAG_NOT_FOUND on create and on PUT tags, and the todo is untouched")
    void anotherUsersTagIs404() throws Exception {
        TestUser user = newUser();
        String mine = createTag(user, "Mine");
        String theirs = createTag(newUser(), "Theirs");
        String listId = createList(user, "Own tags only");
        String id = createTodo(user, listId, "Mine only", List.of(mine));

        postAs(user.accessToken(), TODOS, createPayload(listId, "Sneaky", List.of(mine, theirs)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TAG_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("No tag was found with id %s.".formatted(theirs)));
        putTags(user, id, List.of(mine, theirs), 0)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TAG_NOT_FOUND"));

        getAs(user.accessToken(), TODOS + "/" + id)
                .andExpect(jsonPath("$.response.tags[*].id", contains(mine)))
                .andExpect(jsonPath("$.response.version").value(0));
        Integer sneaky = jdbc.queryForObject("select count(*) from todos where title = 'Sneaky' and owner_id = ?",
                Integer.class, user.id());
        assertThat(sneaky).isZero();
    }

    @Test
    @DisplayName("An unknown tag id is 404 TAG_NOT_FOUND naming it, on create and on PUT tags")
    void unknownTagIs404() throws Exception {
        TestUser user = newUser();
        String known = createTag(user, "Known");
        String unknown = UUID.randomUUID().toString();
        String listId = createList(user, "Unknown");
        String id = createTodo(user, listId, "Tagged", List.of(known));

        postAs(user.accessToken(), TODOS, createPayload(listId, "Ghost", List.of(unknown)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TAG_NOT_FOUND"));
        putTags(user, id, List.of(known, unknown), 0)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TAG_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("No tag was found with id %s.".formatted(unknown)));
        assertThat(tagRows("todo_id", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("PUT tags on an unknown todo, or another user's, is 404 TODO_NOT_FOUND, and their tags stay")
    void replaceTagsOnAnUnknownOrForeignTodoIs404() throws Exception {
        TestUser owner = newUser();
        String tag = createTag(owner, "Private");
        String id = createTodo(owner, createList(owner, "Owner's"), "Private", List.of(tag));
        TestUser stranger = newUser();
        String strangersTag = createTag(stranger, "Mine");

        putTags(stranger, id, List.of(strangersTag), 0)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
        putTags(owner, UUID.randomUUID().toString(), List.of(tag), 0)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
        assertThat(tagRows("todo_id", id)).isEqualTo(1);
        assertThat(versionOf(id)).isZero();
    }

    @Test
    @DisplayName("Exactly the configured maximum of tags is accepted; one more is 400 with the configured number, on create and on PUT tags")
    void tagCountFollowsTheConfiguredMaximum() throws Exception {
        TestUser user = newUser();
        List<String> tags = createTags(user, MAX_TAGS + 1);
        List<String> max = tags.subList(0, MAX_TAGS);
        String listId = createList(user, "Many tags");
        String full = createTodo(user, listId, "Full", max);
        String growing = createTodo(user, listId, "Growing", List.of());

        postAs(user.accessToken(), TODOS, createPayload(listId, "Overfull", tags))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value("tagIds: must have at most 10 items"));
        putTags(user, growing, max, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.tags.length()").value(MAX_TAGS));
        putTags(user, full, tags, 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value("tagIds: must have at most 10 items"));
        getAs(user.accessToken(), TODOS + "/" + full)
                .andExpect(jsonPath("$.response.tags.length()").value(MAX_TAGS))
                .andExpect(jsonPath("$.response.version").value(0));
    }

    @Test
    @DisplayName("A repeated tag id counts once: the maximum of distinct ids plus a repeat is accepted, and the tag is on the todo once")
    void aRepeatedTagIdCountsOnce() throws Exception {
        TestUser user = newUser();
        List<String> max = createTags(user, MAX_TAGS);
        List<String> maxWithARepeat = new ArrayList<>(max);
        maxWithARepeat.add(max.getFirst());
        String listId = createList(user, "Repeats");

        String id = read(postAs(user.accessToken(), TODOS, createPayload(listId, "Repeated", maxWithARepeat))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.tags.length()").value(MAX_TAGS)), "$.response.id");
        putTags(user, id, Collections.nCopies(MAX_TAGS + 1, max.getLast()), 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.tags[*].id", contains(max.getLast())));
        assertThat(tagRows("todo_id", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("A null tag id is 400 VALIDATION_FAILED naming tagIds; a malformed one is 400 REQUEST_UNREADABLE without internals")
    void badTagIdsAre400() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Bad ids");
        String id = createTodo(user, listId, "Todo", List.of());

        postAs(user.accessToken(), TODOS, """
                {"todoListId": "%s", "title": "Null id", "priority": "LOW", "tagIds": [null]}
                """.formatted(listId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed", hasItem(startsWith("tagIds"))));
        putAs(user.accessToken(), TODOS + "/" + id + "/tags", """
                {"tagIds": [null], "version": 0}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed", hasItem(startsWith("tagIds"))));
        String body = putAs(user.accessToken(), TODOS + "/" + id + "/tags", """
                {"tagIds": ["nope"], "version": 0}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("java.", "com.example", "Exception", "UUID");
        putTags(user, "nope", List.of(), 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"));
        assertThat(versionOf(id)).isZero();
    }

    @Test
    @DisplayName("PUT tags needs a token: 401 AUTHENTICATION_REQUIRED")
    void replaceTagsNeedsAToken() throws Exception {
        putAs(null, TODOS + "/" + UUID.randomUUID() + "/tags", tagsPayload(List.of(), 0))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("List rows carry each todo's tags, in creation order")
    void listRowsCarryTheTags() throws Exception {
        TestUser user = newUser();
        String work = createTag(user, "Work");
        String urgent = createTag(user, "Urgent");
        String listId = createList(user, "Rows");
        createTodo(user, listId, "Tagged", List.of(urgent, work));
        createTodo(user, listId, "Untagged", List.of());

        getAs(user.accessToken(), TODOS + "?sort=NEWEST")
                .andExpect(jsonPath("$.response[*].title", contains("Untagged", "Tagged")))
                .andExpect(jsonPath("$.response[0].tags", empty()))
                .andExpect(jsonPath("$.response[1].tags[*].id", contains(work, urgent)))
                .andExpect(jsonPath("$.response[1].tags[*].name", contains("Work", "Urgent")));
    }

    @Test
    @DisplayName("tagId returns only the todos carrying that tag; another user's tag returns none; a malformed one is 400")
    void filterByTagIdReturnsOnlyMatching() throws Exception {
        TestUser user = newUser();
        String wanted = createTag(user, "Wanted");
        String other = createTag(user, "Other");
        String listId = createList(user, "Filter");
        createTodo(user, listId, "Both", List.of(wanted, other));
        createTodo(user, listId, "Only other", List.of(other));
        createTodo(user, listId, "None", List.of());
        TestUser stranger = newUser();
        String strangersTag = createTag(stranger, "Wanted");
        createTodo(stranger, createList(stranger, "Theirs"), "Their todo", List.of(strangersTag));

        getAs(user.accessToken(), TODOS + "?sort=NEWEST")
                .andExpect(jsonPath("$.response[*].title", contains("None", "Only other", "Both")));
        getAs(user.accessToken(), TODOS + "?tagId=" + wanted)
                .andExpect(jsonPath("$.response[*].title", contains("Both")))
                .andExpect(jsonPath("$.response[0].tags[*].id", contains(wanted, other)));
        getAs(user.accessToken(), TODOS + "?tagId=" + strangersTag)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", empty()));
        getAs(user.accessToken(), TODOS + "?tagId=nope")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.failed", contains("tagId: invalid value")));
    }

    @Test
    @DisplayName("Under tagId, the cursor batches of both sorts walk every tagged todo once, in order")
    void tagFilterBatchesWalkTheWholeListInOrder() throws Exception {
        TestUser user = newUser();
        String tag = createTag(user, "Walked");
        String listId = createList(user, "Walk");
        // Due dates out of creation order, three on one day: batches of 2 split that group, so the id
        // tie-breaker carries the walk across the boundary.
        String[] dueDates = {"2026-12-03", "2026-12-01", "2026-12-01", "2026-12-01", "2026-12-05"};
        List<String> titles = new ArrayList<>();
        for (int index = 0; index < dueDates.length; index++) {
            String title = "T" + index;
            titles.add(title);
            postAs(user.accessToken(), TODOS, """
                    {"todoListId": "%s", "title": "%s", "priority": "LOW", "dueDate": "%s", "tagIds": ["%s"]}
                    """.formatted(listId, title, dueDates[index], tag)).andExpect(status().isCreated());
        }
        createTodo(user, listId, "Untagged", List.of());

        assertThat(walkTitles(user, TODOS + "?size=2&tagId=" + tag)).containsExactly("T1", "T2", "T3", "T0", "T4");
        assertThat(walkTitles(user, TODOS + "?size=2&sort=NEWEST&tagId=" + tag))
                .containsExactlyElementsOf(titles.reversed());
    }

    /** Follows nextCursor to the last batch (hasMore false, no cursor); returns every title in order. */
    private List<String> walkTitles(TestUser user, String firstBatch) throws Exception {
        List<String> titles = new ArrayList<>();
        String next = firstBatch;
        int batches = 0;
        while (Objects.nonNull(next)) {
            assertThat(batches++).isLessThan(20);
            ResultActions batch = getAs(user.accessToken(), next).andExpect(status().isOk());
            List<String> batchTitles = read(batch, "$.response[*].title");
            titles.addAll(batchTitles);
            if (Boolean.TRUE.equals(read(batch, "$.pageInfo.hasMore"))) {
                next = firstBatch + "&after=" + read(batch, "$.pageInfo.nextCursor");
            } else {
                batch.andExpect(jsonPath("$.pageInfo.nextCursor").doesNotExist());
                next = null;
            }
        }
        return titles;
    }

    /**
     * Two statements whatever the batch holds: the batch, then one query for the tags of all its rows
     * (two steps instead of a collection fetch, DATA-26). The tagId filter is an exists inside the first.
     */
    @Test
    @DisplayName("A batch of tagged todos is two statements, for 1 row and for a full batch, filtered by tag or not")
    void listWithTagsRunsAConstantNumberOfQueries() throws Exception {
        TestUser one = newUser();
        String oneTag = createTag(one, "Single");
        createTodo(one, createList(one, "Single"), "Only", List.of(oneTag));
        TestUser many = newUser();
        String first = createTag(many, "First");
        String second = createTag(many, "Second");
        String listId = createList(many, "Many");
        for (int index = 0; index < 21; index++) {
            createTodo(many, listId, "Todo " + index, List.of(first, second));
        }

        long forOne = statementsFor(one, TODOS);
        long forPage = statementsFor(many, TODOS);
        long forFilteredPage = statementsFor(many, TODOS + "?tagId=" + first);

        assertThat(forPage).isEqualTo(forOne).isEqualTo(forFilteredPage).isEqualTo(2);
    }

    private long statementsFor(TestUser user, String path) throws Exception {
        statistics().clear();
        getAs(user.accessToken(), path).andExpect(status().isOk());
        return statistics().getPrepareStatementCount();
    }

    @Test
    @DisplayName("Deleting a tag still on todos is 204: it is taken off them, and the todos, their other tags and their versions stay")
    void deletingATagRemovesItFromItsTodos() throws Exception {
        TestUser user = newUser();
        String doomed = createTag(user, "Doomed");
        String kept = createTag(user, "Kept");
        String listId = createList(user, "Label delete");
        String first = createTodo(user, listId, "First", List.of(doomed, kept));
        String second = createTodo(user, listId, "Second", List.of(doomed));
        assertThat(tagRows("tag_id", doomed)).isEqualTo(2);

        deleteAs(user.accessToken(), TAGS + "/" + doomed).andExpect(status().isNoContent());

        assertThat(tagRows("tag_id", doomed)).isZero();
        getAs(user.accessToken(), TODOS + "/" + first)
                .andExpect(jsonPath("$.response.tags[*].id", contains(kept)))
                .andExpect(jsonPath("$.response.version").value(0));
        getAs(user.accessToken(), TODOS + "/" + second)
                .andExpect(jsonPath("$.response.tags", empty()))
                .andExpect(jsonPath("$.response.version").value(0));
    }

    @Test
    @DisplayName("Deleting a todo takes its tag rows with it and keeps the tags")
    void deletingATodoKeepsItsTags() throws Exception {
        TestUser user = newUser();
        String tag = createTag(user, "Survivor");
        String id = createTodo(user, createList(user, "Todo delete"), "Doomed todo", List.of(tag));
        assertThat(tagRows("todo_id", id)).isEqualTo(1);

        deleteAs(user.accessToken(), TODOS + "/" + id).andExpect(status().isNoContent());

        assertThat(tagRows("todo_id", id)).isZero();
        getAs(user.accessToken(), TAGS + "/" + tag).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Todos tagged while the tag is deleted: the delete is 204, every create 404 TAG_GONE, and no todo or link is stored")
    void tagWritesRacingATagDeleteKeepTheInvariant() throws Exception {
        TestUser user = newUser();
        String tag = createTag(user, "Contested");
        String listId = createList(user, "Race");

        // Held at the todo's insert, before its link: nothing locks the tag for a create, so the delete
        // goes through, and the link's foreign key refuses each create after it, todo and all.
        List<Answer> creates = createsHeldAtTheirInsert("todos",
                () -> postAs(user.accessToken(), TODOS, createPayload(listId, "Racer", List.of(tag))),
                () -> deleteAs(user.accessToken(), TAGS + "/" + tag).andExpect(status().isNoContent()));

        assertThat(creates).extracting(Answer::status).containsOnly(404);
        assertRefusalsAre(creates, 404, "TAG_GONE");
        assertThat(tagRows("tag_id", tag)).isZero();
        Integer todos = jdbc.queryForObject("select count(*) from todos where todo_list_id = ?::uuid", Integer.class, listId);
        assertThat(todos).isZero();
    }

    @Test
    @DisplayName("Concurrent tag replacements with one version apply exactly once; the others are 409 OPTIMISTIC_LOCK_CONFLICT")
    void concurrentTagReplacementsWithOneVersionApplyOnce() throws Exception {
        TestUser user = newUser();
        List<String> tags = createTags(user, 3);
        String id = createTodo(user, createList(user, "Retag"), "Contested", List.of(tags.getFirst()));

        List<Answer> answers = race(() -> putTags(user, id, tags, 0));

        assertThat(answers).extracting(Answer::status).containsOnlyOnce(200).containsOnly(200, 409);
        assertRefusalsAre(answers, 409, "OPTIMISTIC_LOCK_CONFLICT");
        assertThat(versionOf(id)).isEqualTo(1L);
        assertThat(tagRows("todo_id", id)).isEqualTo(3);
    }

    /**
     * Runs {@code request} while a delete of {@code tagId}, cascading to its todo_tags rows, is held
     * uncommitted in the test's own transaction, then commits the delete once the request waits on it.
     */
    private Answer whileATagDeleteIsInFlight(String tagId, Callable<Answer> request) throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try (ExecutorService executor = newExecutor()) {
            Future<?> tagDelete = executor.submit(() -> transaction.executeWithoutResult(status -> {
                assertThat(jdbc.update("delete from tags where id = ?::uuid", tagId)).isOne();
                deleted.countDown();
                awaitQuietly(commit);
            }));
            awaitLatch(deleted);
            Future<Answer> answer = executor.submit(request);
            try {
                awaitLockWaiters(1, answer);
            } finally {
                commit.countDown();
            }
            within(tagDelete);
            return within(answer);
        }
    }

    @Test
    @DisplayName("A todo delete while one of its tags is being deleted answers 204, never 409: its tag rows go in one statement")
    void todoDeleteRacingATagDeleteSucceeds() throws Exception {
        TestUser user = newUser();
        String tag = createTag(user, "Going");
        String todo = createTodo(user, createList(user, "Racing"), "Tagged", List.of(tag));

        Answer answer = whileATagDeleteIsInFlight(tag, () -> answerOf(deleteAs(user.accessToken(), TODOS + "/" + todo)));

        assertThat(answer.status()).as(answer.body()).isEqualTo(204);
        assertThat(jdbc.queryForObject("select count(*) from todos where id = ?::uuid", Integer.class, todo)).isZero();
        assertThat(tagRows("todo_id", todo)).isZero();
    }

    @Test
    @DisplayName("A tag replace that drops a tag being deleted meanwhile is 409 OPTIMISTIC_LOCK_CONFLICT, as documented; the retry applies")
    void tagReplaceDroppingATagBeingDeletedIs409() throws Exception {
        TestUser user = newUser();
        String tag = createTag(user, "Going");
        String todo = createTodo(user, createList(user, "Racing"), "Tagged", List.of(tag));

        Answer answer = whileATagDeleteIsInFlight(tag, () -> answerOf(putTags(user, todo, List.of(), 0)));

        assertThat(answer.status()).isEqualTo(409);
        assertRefusalsAre(List.of(answer), 409, "OPTIMISTIC_LOCK_CONFLICT");
        putTags(user, todo, List.of(), versionOf(todo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.tags", empty()));
    }

    @Test
    @DisplayName("The database refuses a tag row whose todo and tag belong to different owners, whichever owner it names")
    void compositeForeignKeysRefuseATagOfAnotherOwner() throws Exception {
        TestUser owner = newUser();
        String todo = createTodo(owner, createList(owner, "Guarded"), "Guarded", List.of());
        String ownTag = createTag(owner, "Own");
        TestUser stranger = newUser();
        String foreignTag = createTag(stranger, "Foreign");
        String insert = """
                insert into todo_tags (id, todo_id, tag_id, owner_id, created_at, updated_at, version)
                values (gen_random_uuid(), ?::uuid, ?::uuid, ?, now(), now(), 0)
                """;

        assertThat(sql(insert, todo, ownTag, owner.id())).isEqualTo(1);
        assertThatThrownBy(() -> sql(insert, todo, foreignTag, owner.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_todo_tags_tag_id_owner_id");
        assertThatThrownBy(() -> sql(insert, todo, foreignTag, stranger.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_todo_tags_todo_id_owner_id");
        assertThat(tagRows("todo_id", todo)).isEqualTo(1);
    }

    @Test
    @DisplayName("The API docs show the configured maximum of tags as maxItems, and the todo's PUT has no tagIds")
    void apiDocsShowTheTagLimit() throws Exception {
        getAs(null, "/v3/api-docs")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.TodoCreateRequest.properties.tagIds.maxItems").value(MAX_TAGS))
                .andExpect(jsonPath("$.components.schemas.TodoTagsRequest.properties.tagIds.maxItems").value(MAX_TAGS))
                .andExpect(jsonPath("$.components.schemas.TodoUpdateRequest.properties.tagIds").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/todos/{id}/tags'].put.responses['409']").exists());
    }
}
