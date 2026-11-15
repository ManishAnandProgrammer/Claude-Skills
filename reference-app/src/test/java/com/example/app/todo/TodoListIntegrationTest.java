package com.example.app.todo;

import com.example.app.common.dropdown.DropdownStatus;
import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TodoListIntegrationTest extends IntegrationTest {

    private static final String LISTS = "/api/v1/todo-lists";

    @Autowired
    private PlatformTransactionManager transactionManager;
    private static final String DROPDOWN = LISTS + "/dropdown";

    private static String namePayload(String name) {
        return """
                {"name": "%s"}
                """.formatted(name);
    }

    private static String updatePayload(String name, long version) {
        return """
                {"name": "%s", "version": %d}
                """.formatted(name, version);
    }

    /** Creates a list as {@code user} and returns its id; fails the test unless it answers 201. */
    private String createList(TestUser user, String name) throws Exception {
        return read(postAs(user.accessToken(), LISTS, namePayload(name)).andExpect(status().isCreated()), "$.response.id");
    }

    /**
     * Follows {@code pageInfo.nextCursor} from {@code firstBatch} (a path with its query string) to
     * the last batch, which must say {@code hasMore} false with no cursor; returns each batch's labels.
     */
    private List<List<String>> walk(TestUser user, String firstBatch) throws Exception {
        List<List<String>> batches = new ArrayList<>();
        String next = firstBatch;
        while (Objects.nonNull(next)) {
            assertThat(batches).hasSizeLessThan(20);
            ResultActions batch = getAs(user.accessToken(), next).andExpect(status().isOk());
            batches.add(read(batch, "$.response[*].label"));
            if (Boolean.TRUE.equals(read(batch, "$.pageInfo.hasMore"))) {
                next = firstBatch + "&after=" + read(batch, "$.pageInfo.nextCursor");
            } else {
                batch.andExpect(jsonPath("$.pageInfo.nextCursor").doesNotExist());
                next = null;
            }
        }
        return batches;
    }

    private ResultActions getWithQuery(TestUser user, String path, String query) throws Exception {
        return mvc.perform(get(path).param("query", query).header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()));
    }

    @Test
    @DisplayName("Create answers 201 with the trimmed name, the owner, version 0 and the audit fields")
    void createReturns201WithTheSavedEntity() throws Exception {
        TestUser user = newUser();

        postAs(user.accessToken(), LISTS, namePayload("  Groceries  "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.id").isNotEmpty())
                .andExpect(jsonPath("$.response.name").value("Groceries"))
                .andExpect(jsonPath("$.response.ownerId").value(user.id().toString()))
                .andExpect(jsonPath("$.response.version").value(0))
                .andExpect(jsonPath("$.response.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.response.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.response.createdBy").value(user.id().toString()))
                .andExpect(jsonPath("$.response.updatedBy").value(user.id().toString()));
    }

    @Test
    @DisplayName("The moments a write answers are the ones a later read returns (the clock ticks in the column's microseconds)")
    void writeMomentsMatchALaterRead() throws Exception {
        TestUser user = newUser();
        ResultActions created = postAs(user.accessToken(), LISTS, namePayload("Moments")).andExpect(status().isCreated());
        String id = read(created, "$.response.id");
        ResultActions updated = putAs(user.accessToken(), LISTS + "/" + id, updatePayload("Moments renamed", 0))
                .andExpect(status().isOk());

        getAs(user.accessToken(), LISTS + "/" + id)
                .andExpect(jsonPath("$.response.createdAt").value(read(created, "$.response.createdAt").toString()))
                .andExpect(jsonPath("$.response.updatedAt").value(read(updated, "$.response.updatedAt").toString()));
    }

    @Test
    @DisplayName("A name with a NUL character is 400 VALIDATION_FAILED naming it, not a database error")
    void nulCharacterIs400() throws Exception {
        postAs(newUser().accessToken(), LISTS, """
                {"name": "a\\u0000b"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].pointer", contains("#/name")))
                .andExpect(jsonPath("$.errors[*].detail", contains("must not contain the NUL character")));
    }

    @Test
    @DisplayName("Get returns the list")
    void getReturnsTheEntity() throws Exception {
        TestUser user = newUser();
        String id = createList(user, "Work");

        getAs(user.accessToken(), LISTS + "/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.id").value(id))
                .andExpect(jsonPath("$.response.name").value("Work"))
                .andExpect(jsonPath("$.response.ownerId").value(user.id().toString()));
    }

    @Test
    @DisplayName("Get of an unknown id is 404 TODO_LIST_NOT_FOUND")
    void getUnknownIdIs404() throws Exception {
        getAs(newUser().accessToken(), LISTS + "/" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_LIST_NOT_FOUND"));
    }

    @Test
    @DisplayName("A non-UUID id in the path is 400 REQUEST_PARAMETER_INVALID")
    void malformedIdIs400() throws Exception {
        getAs(newUser().accessToken(), LISTS + "/not-a-uuid")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"));
    }

    @Test
    @DisplayName("Update renames the list and increments the version; the same name again changes nothing")
    void updateChangesTheFieldsAndBumpsTheVersion() throws Exception {
        TestUser user = newUser();
        String id = createList(user, "Old name");

        putAs(user.accessToken(), LISTS + "/" + id, updatePayload(" New name ", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.name").value("New name"))
                .andExpect(jsonPath("$.response.version").value(1));
        putAs(user.accessToken(), LISTS + "/" + id, updatePayload("New name", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.version").value(1));
        getAs(user.accessToken(), LISTS + "/" + id).andExpect(jsonPath("$.response.name").value("New name"));
    }

    @Test
    @DisplayName("Update with a stale version is 409 OPTIMISTIC_LOCK_CONFLICT and changes nothing")
    void updateWithAStaleVersionIs409() throws Exception {
        TestUser user = newUser();
        String id = createList(user, "Original");
        putAs(user.accessToken(), LISTS + "/" + id, updatePayload("First edit", 0)).andExpect(status().isOk());

        putAs(user.accessToken(), LISTS + "/" + id, updatePayload("Stale edit", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK_CONFLICT"));
        getAs(user.accessToken(), LISTS + "/" + id)
                .andExpect(jsonPath("$.response.name").value("First edit"))
                .andExpect(jsonPath("$.response.version").value(1));
    }

    @Test
    @DisplayName("PUT and DELETE on an unknown id are 404 TODO_LIST_NOT_FOUND")
    void writesToAnUnknownIdAre404() throws Exception {
        TestUser user = newUser();
        String unknown = LISTS + "/" + UUID.randomUUID();

        putAs(user.accessToken(), unknown, updatePayload("Ghost", 0))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_LIST_NOT_FOUND"));
        deleteAs(user.accessToken(), unknown)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_LIST_NOT_FOUND"));
    }

    @Test
    @DisplayName("A delete that loses the race to a rename is 409 OPTIMISTIC_LOCK_CONFLICT, and the renamed list stays")
    void deleteRacingARenameIs409() throws Exception {
        TestUser user = newUser();
        String id = createList(user, "Before");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch renamed = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);

        try (ExecutorService executor = newExecutor()) {
            // The rename holds the row uncommitted: the delete reads the old version, then waits at its DELETE.
            Future<?> rename = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.update("update todo_lists set name = 'After', version = version + 1 where id = ?::uuid", id);
                renamed.countDown();
                awaitQuietly(commit);
            }));
            awaitLatch(renamed);
            Future<Answer> delete;
            try {
                delete = executor.submit(() -> answerOf(deleteAs(user.accessToken(), LISTS + "/" + id)));
                awaitLockWaiters(1, delete);
            } finally {
                commit.countDown();
            }
            within(rename);

            Answer answer = within(delete);
            assertThat(answer.status()).isEqualTo(409);
            assertThat(answer.errorCode()).isEqualTo("OPTIMISTIC_LOCK_CONFLICT");
            assertNoInternals(answer.body());
        }
        getAs(user.accessToken(), LISTS + "/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.name").value("After"));
    }

    @Test
    @DisplayName("Delete answers 204 and the list is gone")
    void deleteReturns204AndTheEntityIsGone() throws Exception {
        TestUser user = newUser();
        String id = createList(user, "Temporary");
        getAs(user.accessToken(), LISTS + "/" + id).andExpect(status().isOk());

        deleteAs(user.accessToken(), LISTS + "/" + id).andExpect(status().isNoContent());
        getAs(user.accessToken(), LISTS + "/" + id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_LIST_NOT_FOUND"));
    }

    @Test
    @DisplayName("Create without a name, or with a blank one, is 400 VALIDATION_FAILED naming the field")
    void createWithoutRequiredFieldsIs400() throws Exception {
        TestUser user = newUser();

        postAs(user.accessToken(), LISTS, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].pointer").value("#/name"));
        postAs(user.accessToken(), LISTS, namePayload("   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].pointer").value("#/name"));
    }

    @Test
    @DisplayName("Update without a version is 400 VALIDATION_FAILED naming version")
    void writesWithoutAVersionAre400() throws Exception {
        TestUser user = newUser();
        String id = createList(user, "Versionless");

        putAs(user.accessToken(), LISTS + "/" + id, namePayload("No version"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].pointer").value("#/version"));
    }

    @Test
    @DisplayName("A name of 100 Gurmukhi letters is accepted; 101 characters is 400 with the configured limit")
    void nameLengthFollowsTheConfiguredLimit() throws Exception {
        TestUser user = newUser();

        postAs(user.accessToken(), LISTS, namePayload("ਪ".repeat(100))).andExpect(status().isCreated());
        postAs(user.accessToken(), LISTS, namePayload("y".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].pointer").value("#/name"))
                .andExpect(jsonPath("$.errors[0].detail").value("must be at most 100 characters"));
    }

    @Test
    @DisplayName("Markup in a name is stored and returned as text (escape-on-output policy)")
    void createWithAScriptTagStoresItAsText() throws Exception {
        TestUser user = newUser();
        String hostile = "<script>alert(1)</script>";

        postAs(user.accessToken(), LISTS, namePayload(hostile))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.name").value(hostile));
    }

    @Test
    @DisplayName("A name you already use is 409 TODO_LIST_NAME_TAKEN without internals in the body; another user may use it")
    void createWithATakenNameIs409() throws Exception {
        TestUser user = newUser();
        createList(user, "Shopping");

        String body = postAs(user.accessToken(), LISTS, namePayload("Shopping"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TODO_LIST_NAME_TAKEN"))
                .andReturn().getResponse().getContentAsString();
        assertNoInternals(body);
        Integer lists = jdbc.queryForObject("select count(*) from todo_lists where owner_id = ? and name = 'Shopping'",
                Integer.class, user.id());
        assertThat(lists).isOne();
        createList(newUser(), "Shopping");
    }

    @Test
    @DisplayName("Names are unique per owner ignoring case and surrounding spaces")
    void nameUniquenessIgnoresCaseAndSpaces() throws Exception {
        TestUser user = newUser();
        createList(user, "Groceries");

        postAs(user.accessToken(), LISTS, namePayload("  GROCERIES "))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TODO_LIST_NAME_TAKEN"));
    }

    @Test
    @DisplayName("Renaming to a name you already use, in another case, is 409 TODO_LIST_NAME_TAKEN")
    void updateToATakenNameIs409() throws Exception {
        TestUser user = newUser();
        createList(user, "Taken");
        String id = createList(user, "Free");

        String body = putAs(user.accessToken(), LISTS + "/" + id, updatePayload(" taken", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TODO_LIST_NAME_TAKEN"))
                .andReturn().getResponse().getContentAsString();
        assertNoInternals(body);
        getAs(user.accessToken(), LISTS + "/" + id).andExpect(jsonPath("$.response.name").value("Free"));
    }

    @Test
    @DisplayName("Concurrent creates with one name store exactly one list; the others are 409 TODO_LIST_NAME_TAKEN")
    void concurrentCreatesWithTheSameNameStoreOne() throws Exception {
        TestUser user = newUser();

        List<Answer> answers = race(() -> postAs(user.accessToken(), LISTS, namePayload("Race")));

        assertThat(answers).extracting(Answer::status).containsOnlyOnce(201).containsOnly(201, 409);
        assertRefusalsAre(answers, 409, "TODO_LIST_NAME_TAKEN");
        Integer rows = jdbc.queryForObject("select count(*) from todo_lists where owner_id = ? and name = 'Race'",
                Integer.class, user.id());
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrent renames with one version apply exactly once; the others are 409 OPTIMISTIC_LOCK_CONFLICT")
    void concurrentUpdatesWithOneVersionApplyOnce() throws Exception {
        TestUser user = newUser();
        String id = createList(user, "Contested");

        List<Answer> answers = race(() -> putAs(user.accessToken(), LISTS + "/" + id, updatePayload("Renamed", 0)));

        assertThat(answers).extracting(Answer::status).containsOnlyOnce(200).containsOnly(200, 409);
        assertRefusalsAre(answers, 409, "OPTIMISTIC_LOCK_CONFLICT");
        Long version = jdbc.queryForObject("select version from todo_lists where id = ?::uuid", Long.class, id);
        assertThat(version).isEqualTo(1L);
    }

    static Stream<Arguments> endpoints() {
        String someId = UUID.randomUUID().toString();
        return Stream.of(
                Arguments.of("GET", LISTS),
                Arguments.of("GET", LISTS + "/" + someId),
                Arguments.of("POST", LISTS),
                Arguments.of("PUT", LISTS + "/" + someId),
                Arguments.of("DELETE", LISTS + "/" + someId),
                Arguments.of("GET", DROPDOWN));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("endpoints")
    @DisplayName("Every endpoint answers 401 AUTHENTICATION_REQUIRED without a token")
    void everyEndpointNeedsAToken(String method, String path) throws Exception {
        ResultActions result = switch (method) {
            case "GET" -> getAs(null, path);
            case "POST" -> postAs(null, path, namePayload("x"));
            case "PUT" -> putAs(null, path, updatePayload("x", 0));
            case "DELETE" -> deleteAs(null, path);
            default -> throw new IllegalArgumentException(method);
        };
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("Another user's list is 404 on get, update and delete, and stays untouched")
    void anotherUsersRecordIs404() throws Exception {
        TestUser owner = newUser();
        TestUser stranger = newUser();
        String id = createList(owner, "Private");
        getAs(owner.accessToken(), LISTS + "/" + id).andExpect(status().isOk());

        getAs(stranger.accessToken(), LISTS + "/" + id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_LIST_NOT_FOUND"));
        putAs(stranger.accessToken(), LISTS + "/" + id, updatePayload("Hijacked", 0))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_LIST_NOT_FOUND"));
        deleteAs(stranger.accessToken(), LISTS + "/" + id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_LIST_NOT_FOUND"));
        getAs(owner.accessToken(), LISTS + "/" + id)
                .andExpect(jsonPath("$.response.name").value("Private"))
                .andExpect(jsonPath("$.response.version").value(0));
    }

    @Test
    @DisplayName("Your page holds only your lists, and its pageInfo counts only them")
    void listShowsOnlyYourLists() throws Exception {
        TestUser user = newUser();
        createList(user, "Mine");
        createList(newUser(), "Theirs");

        getAs(user.accessToken(), LISTS)
                .andExpect(jsonPath("$.response[*].name", contains("Mine")))
                .andExpect(jsonPath("$.pageInfo.totalElements").value(1))
                .andExpect(jsonPath("$.pageInfo.totalPages").value(1))
                .andExpect(jsonPath("$.pageInfo.hasNext").value(false));
    }

    @Test
    @DisplayName("The list is offset-paged: pageInfo describes each page; a size over the configured maximum is 400")
    void listIsOffsetPaged() throws Exception {
        TestUser user = newUser();
        for (String name : new String[] {"Paged C", "Paged A", "Paged B"}) {
            createList(user, name);
        }

        getAs(user.accessToken(), LISTS + "?size=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].name", contains("Paged A", "Paged B")))
                .andExpect(jsonPath("$.pageInfo.number").value(0))
                .andExpect(jsonPath("$.pageInfo.size").value(2))
                .andExpect(jsonPath("$.pageInfo.totalElements").value(3))
                .andExpect(jsonPath("$.pageInfo.totalPages").value(2))
                .andExpect(jsonPath("$.pageInfo.hasNext").value(true))
                .andExpect(jsonPath("$.pageInfo.hasPrevious").value(false));
        getAs(user.accessToken(), LISTS + "?size=2&page=1")
                .andExpect(jsonPath("$.response[*].name", contains("Paged C")))
                .andExpect(jsonPath("$.pageInfo.number").value(1))
                .andExpect(jsonPath("$.pageInfo.hasNext").value(false))
                .andExpect(jsonPath("$.pageInfo.hasPrevious").value(true));
        getAs(user.accessToken(), LISTS + "?size=101")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].parameter").value("size"))
                .andExpect(jsonPath("$.errors[0].detail").value("must be at most 100"));
    }

    @Test
    @DisplayName("A page past the last is empty but says where it is")
    void listPastTheLastPageIsEmptyButNavigable() throws Exception {
        TestUser user = newUser();
        createList(user, "Only one");
        getAs(user.accessToken(), LISTS).andExpect(jsonPath("$.response.length()").value(1));

        getAs(user.accessToken(), LISTS + "?page=5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.length()").value(0))
                .andExpect(jsonPath("$.pageInfo.number").value(5))
                .andExpect(jsonPath("$.pageInfo.totalElements").value(1))
                .andExpect(jsonPath("$.pageInfo.hasNext").value(false))
                .andExpect(jsonPath("$.pageInfo.hasPrevious").value(true));
    }

    @Test
    @DisplayName("The configured maximum page index is accepted (an empty page); one more is 400 VALIDATION_FAILED naming page")
    void pageIndexIsBounded() throws Exception {
        getAs(newUser().accessToken(), LISTS + "?page=100000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", empty()));
        getAs(newUser().accessToken(), LISTS + "?page=100001")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].parameter").value("page"))
                .andExpect(jsonPath("$.errors[0].detail").value("must be at most 100000"));
    }

    @Test
    @DisplayName("query matches part of the name, ignoring case; % in it matches only a %")
    void filterByQueryReturnsOnlyMatching() throws Exception {
        TestUser user = newUser();
        createList(user, "Holiday packing");
        createList(user, "Work");
        createList(user, "100% done");

        getAs(user.accessToken(), LISTS + "?query=PACK")
                .andExpect(jsonPath("$.response[*].name", hasItem("Holiday packing")))
                .andExpect(jsonPath("$.response[*].name", not(hasItem("Work"))));
        getWithQuery(user, LISTS, "%")
                .andExpect(jsonPath("$.response[*].name", contains("100% done")));
    }

    @Test
    @DisplayName("ids returns only those lists, in the page's order; unknown and another user's ids are simply absent")
    void filterByIdsReturnsOnlyMatching() throws Exception {
        TestUser user = newUser();
        String charlie = createList(user, "Charlie");
        String alpha = createList(user, "Alpha");
        createList(user, "Bravo");
        TestUser other = newUser();
        String theirs = createList(other, "Theirs");
        getAs(user.accessToken(), LISTS).andExpect(jsonPath("$.response[*].name", hasItem("Bravo")));

        getAs(user.accessToken(), LISTS + "?ids=" + String.join(",", charlie, alpha, theirs, UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].name", contains("Alpha", "Charlie")))
                .andExpect(jsonPath("$.pageInfo.totalElements").value(2));
        getAs(other.accessToken(), LISTS + "?ids=" + theirs)
                .andExpect(jsonPath("$.response[*].name", contains("Theirs")));
    }

    @Test
    @DisplayName("ids takes up to the maximum page size; one more is 400 VALIDATION_FAILED, a malformed id 400 REQUEST_PARAMETER_INVALID")
    void idsAreBounded() throws Exception {
        TestUser user = newUser();

        getAs(user.accessToken(), LISTS + "?ids=" + randomIds(100))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.length()").value(0));
        getAs(user.accessToken(), LISTS + "?ids=" + randomIds(101))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].parameter", contains("ids")))
                .andExpect(jsonPath("$.errors[*].detail", contains("must have at most 100 items")));
        getAs(user.accessToken(), LISTS + "?ids=nope," + UUID.randomUUID())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.errors[*].parameter", contains("ids")))
                .andExpect(jsonPath("$.errors[*].detail", contains("invalid value")));
    }

    @Test
    @DisplayName("A query of 100 characters is accepted; 101 is 400 with the configured limit")
    void queryLengthFollowsTheConfiguredLimit() throws Exception {
        TestUser user = newUser();
        createList(user, "ਪ".repeat(100));

        getWithQuery(user, LISTS, "ਪ".repeat(100))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.length()").value(1));
        getWithQuery(user, LISTS, "q".repeat(101))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].parameter").value("query"))
                .andExpect(jsonPath("$.errors[0].detail").value("must be at most 100 characters"));
    }

    @Test
    @DisplayName("sort=NAME (the default) is alphabetical; NEWEST is newest first")
    void sortOrdersThePage() throws Exception {
        TestUser user = newUser();
        createList(user, "Alpha");
        createList(user, "Charlie");
        createList(user, "Bravo");

        getAs(user.accessToken(), LISTS)
                .andExpect(jsonPath("$.response[*].name", contains("Alpha", "Bravo", "Charlie")));
        getAs(user.accessToken(), LISTS + "?sort=NAME")
                .andExpect(jsonPath("$.response[*].name", contains("Alpha", "Bravo", "Charlie")));
        getAs(user.accessToken(), LISTS + "?sort=NEWEST")
                .andExpect(jsonPath("$.response[*].name", contains("Bravo", "Charlie", "Alpha")));
    }

    @Test
    @DisplayName("Unconvertible filter values are 400 REQUEST_PARAMETER_INVALID naming only the fields")
    void unconvertibleFilterValuesAre400() throws Exception {
        String body = getAs(newUser().accessToken(), LISTS + "?sort=OLDEST_FIRST&page=first&size=many")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.errors[*].parameter", containsInAnyOrder("sort", "page", "size")))
                .andExpect(jsonPath("$.errors[*].detail", containsInAnyOrder("invalid value", "invalid value", "invalid value")))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("java.", "com.example", "Exception");
    }

    @Test
    @DisplayName("An unknown JSON field is 400 REQUEST_UNREADABLE")
    void unknownJsonFieldIs400() throws Exception {
        postAs(newUser().accessToken(), LISTS, """
                {"name": "Sneaky", "ownerId": "%s"}
                """.formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"));
    }

    @Test
    @DisplayName("A page costs one statement while partial and two (with the count) when full, whatever the total")
    void listRunsAConstantNumberOfQueries() throws Exception {
        TestUser one = newUser();
        createList(one, "Single");
        TestUser many = newUser();
        for (int index = 0; index < 25; index++) {
            createList(many, "List " + index);
        }

        long forOne = statementsFor(one, LISTS + "?size=20");
        long forFullPage = statementsFor(many, LISTS + "?size=20");
        long forFullPageOfMore = statementsFor(many, LISTS + "?size=5");

        assertThat(forOne).isEqualTo(1);
        assertThat(forFullPage).isEqualTo(2);
        assertThat(forFullPageOfMore).isEqualTo(forFullPage);
    }

    @Test
    @DisplayName("A partial page found by ids is one statement, for 1 id and for many")
    void filterByIdsRunsAConstantNumberOfQueries() throws Exception {
        TestUser user = newUser();
        String first = createList(user, "Counted 1");
        String second = createList(user, "Counted 2");

        long forOne = statementsFor(user, LISTS + "?ids=" + first);
        long forMany = statementsFor(user, LISTS + "?ids=" + String.join(",", first, second, randomIds(50)));

        assertThat(forMany).isEqualTo(forOne).isEqualTo(1);
    }

    /** Statements run by one request: for a page, plus the count only when the page is full. */
    private long statementsFor(TestUser user, String path) throws Exception {
        statistics().clear();
        getAs(user.accessToken(), path).andExpect(status().isOk());
        return statistics().getPrepareStatementCount();
    }

    @Test
    @DisplayName("A dropdown batch costs exactly one statement, for one option and for a full batch")
    void dropdownRunsOneStatement() throws Exception {
        TestUser one = newUser();
        createList(one, "Single");
        TestUser many = newUser();
        for (int index = 0; index < 21; index++) {
            createList(many, "Option " + index);
        }
        getAs(many.accessToken(), DROPDOWN + "?size=20")
                .andExpect(jsonPath("$.response.length()").value(20))
                .andExpect(jsonPath("$.pageInfo.hasMore").value(true));

        assertThat(statementsFor(one, DROPDOWN + "?size=20")).isEqualTo(1);
        assertThat(statementsFor(many, DROPDOWN + "?size=20")).isEqualTo(1);
    }

    @Test
    @DisplayName("The dropdown lists only your lists, as exactly {value, label}, ordered by name")
    void dropdownListsYourOptionsInDefaultOrder() throws Exception {
        TestUser user = newUser();
        String alpha = createList(user, "Alpha");
        createList(user, "Charlie");
        createList(user, "Bravo");
        createList(newUser(), "Aardvark of someone else");

        getAs(user.accessToken(), DROPDOWN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].label", contains("Alpha", "Bravo", "Charlie")))
                .andExpect(jsonPath("$.response[0].value").value(alpha))
                .andExpect(jsonPath("$.response[0].length()").value(2))
                .andExpect(jsonPath("$.pageInfo.hasMore").value(false));
    }

    @Test
    @DisplayName("sortDirection=DESC reverses the dropdown's name order")
    void dropdownSortDirectionReversesTheOrder() throws Exception {
        TestUser user = newUser();
        createList(user, "Alpha");
        createList(user, "Charlie");
        createList(user, "Bravo");

        getAs(user.accessToken(), DROPDOWN + "?sortDirection=ASC")
                .andExpect(jsonPath("$.response[*].label", contains("Alpha", "Bravo", "Charlie")));
        getAs(user.accessToken(), DROPDOWN + "?sortDirection=DESC")
                .andExpect(jsonPath("$.response[*].label", contains("Charlie", "Bravo", "Alpha")));
    }

    @Test
    @DisplayName("The default dropdown status ACTIVE lists your lists")
    void dropdownActiveStatusListsYourOptions() throws Exception {
        TestUser user = newUser();
        createList(user, "Active one");

        getAs(user.accessToken(), DROPDOWN + "?status=ACTIVE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].label", contains("Active one")));
    }

    @ParameterizedTest
    @EnumSource(value = DropdownStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("A dropdown status other than ACTIVE is 400 REQUEST_PARAMETER_INVALID: lists have no active flag")
    void dropdownStatusIsRefusedWithoutAnActiveFlag(DropdownStatus refused) throws Exception {
        getAs(newUser().accessToken(), DROPDOWN + "?status=" + refused)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.errors[0].parameter").value("status"));
    }

    @Test
    @DisplayName("The dropdown's query matches part of the label, ignoring case")
    void dropdownQueryMatchesPartOfTheLabelIgnoringCase() throws Exception {
        TestUser user = newUser();
        createList(user, "Garden work");
        createList(user, "Reading");

        getAs(user.accessToken(), DROPDOWN + "?query=GARD")
                .andExpect(jsonPath("$.response[*].label", contains("Garden work")));
    }

    @Test
    @DisplayName("A dropdown query of 100 characters is accepted; 101 is 400 with the configured limit")
    void dropdownQueryLengthFollowsTheConfiguredLimit() throws Exception {
        TestUser user = newUser();
        createList(user, "ਬ".repeat(100));

        getWithQuery(user, DROPDOWN, "ਬ".repeat(100))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.length()").value(1));
        getWithQuery(user, DROPDOWN, "q".repeat(101))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].parameter").value("query"))
                .andExpect(jsonPath("$.errors[0].detail").value("must be at most 100 characters"));
    }

    @Test
    @DisplayName("The dropdown comes in batches: nextCursor continues by name to the last batch")
    void dropdownComesInBatches() throws Exception {
        TestUser user = newUser();
        for (String name : new String[] {"Delta", "Alpha", "Echo", "Charlie", "Bravo"}) {
            createList(user, name);
        }

        assertThat(walk(user, DROPDOWN + "?size=2"))
                .containsExactly(List.of("Alpha", "Bravo"), List.of("Charlie", "Delta"), List.of("Echo"));
    }

    @Test
    @DisplayName("Descending dropdown batches walk to the end in reverse name order")
    void dropdownDescendingBatchesWalkToTheEnd() throws Exception {
        TestUser user = newUser();
        for (String name : new String[] {"Delta", "Alpha", "Echo", "Charlie", "Bravo"}) {
            createList(user, name);
        }

        assertThat(walk(user, DROPDOWN + "?size=2&sortDirection=DESC"))
                .containsExactly(List.of("Echo", "Delta"), List.of("Charlie", "Bravo"), List.of("Alpha"));
    }

    @Test
    @DisplayName("A dropdown cursor taken after a name at its limit, of control characters and 4-byte letters, continues the walk")
    void dropdownCursorOfTheLongestNameIsAccepted() throws Exception {
        TestUser user = newUser();
        // 100 characters. The cursor's JSON escapes U+0001 in six bytes and writes 𐌰 in four: the longest cursor
        // a name can give (app.limits.text.cursor is sized for it).
        String longest = read(postAs(user.accessToken(), LISTS, namePayload("A" + "\\u0001\uD800\uDF30".repeat(49) + "Z"))
                .andExpect(status().isCreated()), "$.response.name");
        createList(user, "B");

        assertThat(longest.codePointCount(0, longest.length())).isEqualTo(100);
        assertThat(walk(user, DROPDOWN + "?size=1")).containsExactly(List.of(longest), List.of("B"));
    }

    @Test
    @DisplayName("A dropdown query that matches none of your lists answers an empty batch, hasMore false")
    void dropdownWithNoMatchIsEmpty() throws Exception {
        TestUser user = newUser();
        createList(user, "Groceries");

        getAs(user.accessToken(), DROPDOWN + "?query=nothing-matches-" + UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.length()").value(0))
                .andExpect(jsonPath("$.pageInfo.hasMore").value(false))
                .andExpect(jsonPath("$.pageInfo.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("An unknown dropdown status is 400 REQUEST_PARAMETER_INVALID naming status")
    void dropdownUnknownStatusIs400() throws Exception {
        getAs(newUser().accessToken(), DROPDOWN + "?status=ARCHIVED")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.errors[*].parameter", contains("status")))
                .andExpect(jsonPath("$.errors[*].detail", contains("invalid value")));
    }

    @Test
    @DisplayName("A dropdown cursor taken in one direction is refused in the other")
    void dropdownCursorFromAnotherDirectionIsRefused() throws Exception {
        TestUser user = newUser();
        createList(user, "Alpha");
        createList(user, "Bravo");
        String ascendingCursor = read(getAs(user.accessToken(), DROPDOWN + "?size=1"), "$.pageInfo.nextCursor");

        getAs(user.accessToken(), DROPDOWN + "?size=1&sortDirection=DESC&after=" + ascendingCursor)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.errors[0].parameter").value("after"));
    }

    @Test
    @DisplayName("A tampered dropdown cursor is 400 REQUEST_PARAMETER_INVALID naming after")
    void dropdownTamperedCursorIsRefused() throws Exception {
        getAs(newUser().accessToken(), DROPDOWN + "?after=not-a-cursor")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.errors[0].parameter").value("after"));
    }

    @Test
    @DisplayName("A dropdown batch of the configured maximum is accepted, one more is 400 naming size")
    void dropdownSizeFollowsTheConfiguredMaximum() throws Exception {
        String token = newUser().accessToken();
        getAs(token, DROPDOWN + "?size=100").andExpect(status().isOk());
        getAs(token, DROPDOWN + "?size=101")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].parameter").value("size"))
                .andExpect(jsonPath("$.errors[0].detail").value("must be at most 100"));
    }

    @Test
    @DisplayName("The API docs show the configured limits, and errors as application/problem+json")
    void apiDocsShowTheConfiguredLimits() throws Exception {
        getAs(null, "/v3/api-docs")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.TodoListCreateRequest.properties.name.maxLength").value(100))
                .andExpect(jsonPath("$.paths['/api/v1/todo-lists'].get.parameters[?(@.name == 'size')].schema.maximum").value(100))
                .andExpect(jsonPath("$.paths['/api/v1/todo-lists/dropdown'].get.parameters[?(@.name == 'query')].schema.maxLength").value(100))
                .andExpect(jsonPath("$.paths['/api/v1/todo-lists/{id}'].delete.responses['409'].content['application/problem+json']").exists());
    }
}
