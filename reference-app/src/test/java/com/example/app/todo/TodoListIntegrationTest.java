package com.example.app.todo;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TodoListIntegrationTest extends IntegrationTest {

    private static final String LISTS = "/api/v1/todo-lists";

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

    @Test
    @DisplayName("Create answers 201 with the list, version 0 and the audit fields")
    void createReturns201WithTheSavedList() throws Exception {
        TestUser user = newUser();

        postAs(user.accessToken(), LISTS, namePayload("  Groceries  "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.name").value("Groceries"))
                .andExpect(jsonPath("$.response.ownerId").value(user.id().toString()))
                .andExpect(jsonPath("$.response.version").value(0))
                .andExpect(jsonPath("$.response.createdBy").value(user.id().toString()))
                .andExpect(jsonPath("$.response.createdAt").isNotEmpty());
    }

    @Test
    @DisplayName("Get returns the list; an unknown id is 404 TODO_LIST_NOT_FOUND")
    void getReturnsTheList() throws Exception {
        TestUser user = newUser();
        String id = createList(user, "Work");

        getAs(user.accessToken(), LISTS + "/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.id").value(id))
                .andExpect(jsonPath("$.response.name").value("Work"));
        getAs(user.accessToken(), LISTS + "/" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_LIST_NOT_FOUND"));
    }

    @Test
    @DisplayName("Update renames the list and increments the version")
    void updateChangesTheNameAndBumpsTheVersion() throws Exception {
        TestUser user = newUser();
        String id = createList(user, "Old name");

        putAs(user.accessToken(), LISTS + "/" + id, updatePayload("New name", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.name").value("New name"))
                .andExpect(jsonPath("$.response.version").value(1));
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
        getAs(user.accessToken(), LISTS + "/" + id).andExpect(jsonPath("$.response.name").value("First edit"));
    }

    @Test
    @DisplayName("Delete answers 204 and the list is gone")
    void deleteReturns204AndTheListIsGone() throws Exception {
        TestUser user = newUser();
        String id = createList(user, "Temporary");

        deleteAs(user.accessToken(), LISTS + "/" + id).andExpect(status().isNoContent());
        getAs(user.accessToken(), LISTS + "/" + id).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Delete of an unknown id is 404 TODO_LIST_NOT_FOUND")
    void deleteUnknownIdIs404() throws Exception {
        deleteAs(newUser().accessToken(), LISTS + "/" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_LIST_NOT_FOUND"));
    }

    @Test
    @DisplayName("Create without a name, or with a blank one, is 400 naming the field")
    void createWithoutANameIs400() throws Exception {
        TestUser user = newUser();

        postAs(user.accessToken(), LISTS, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value(containsString("name")));
        postAs(user.accessToken(), LISTS, namePayload("   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failed[0]").value(containsString("name")));
    }

    @Test
    @DisplayName("A name of 101 characters is 400 with the configured limit; 100 Gurmukhi letters are accepted")
    void createWithANameOverTheLimitIs400() throws Exception {
        TestUser user = newUser();

        postAs(user.accessToken(), LISTS, namePayload("ਪ".repeat(100))).andExpect(status().isCreated());
        postAs(user.accessToken(), LISTS, namePayload("y".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failed[0]").value("name: must be at most 100 characters"));
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
    @DisplayName("A name you already use is 409 TODO_LIST_NAME_TAKEN, without constraint names; another user may use it")
    void createWithATakenNameIs409() throws Exception {
        TestUser user = newUser();
        createList(user, "Shopping");

        postAs(user.accessToken(), LISTS, namePayload("Shopping"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TODO_LIST_NAME_TAKEN"))
                .andExpect(jsonPath("$.detail").value(not(containsString("uq_todo_lists"))));
        createList(newUser(), "Shopping");
    }

    @Test
    @DisplayName("Concurrent creates with one name store exactly one list")
    void concurrentCreatesWithTheSameNameStoreOne() throws Exception {
        TestUser user = newUser();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> statuses = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int attempt = 0; attempt < 6; attempt++) {
                Callable<Integer> createOnce = () -> {
                    start.await();
                    return postAs(user.accessToken(), LISTS, namePayload("Race")).andReturn().getResponse().getStatus();
                };
                statuses.add(executor.submit(createOnce));
            }
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> status : statuses) {
                results.add(status.get());
            }
            assertThat(results).containsOnlyOnce(201).containsOnly(201, 409);
        }
        Integer rows = jdbc.queryForObject("select count(*) from todo_lists where owner_id = ? and name = 'Race'",
                Integer.class, user.id());
        assertThat(rows).isEqualTo(1);
    }

    static Stream<Arguments> endpoints() {
        String someId = UUID.randomUUID().toString();
        return Stream.of(
                Arguments.of("GET", LISTS),
                Arguments.of("GET", LISTS + "/" + someId),
                Arguments.of("POST", LISTS),
                Arguments.of("PUT", LISTS + "/" + someId),
                Arguments.of("DELETE", LISTS + "/" + someId),
                Arguments.of("GET", LISTS + "/dropdown"));
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
    void anotherUsersListIs404() throws Exception {
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
        getAs(owner.accessToken(), LISTS + "/" + id).andExpect(jsonPath("$.response.name").value("Private"));
    }

    @Test
    @DisplayName("The list is offset-paged: pageInfo describes the page; a size over the configured maximum is 400")
    void listIsPagedAndCapped() throws Exception {
        TestUser user = newUser();
        for (int index = 0; index < 3; index++) {
            createList(user, "Paged " + index);
        }

        getAs(user.accessToken(), LISTS + "?size=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.length()").value(2))
                .andExpect(jsonPath("$.pageInfo.number").value(0))
                .andExpect(jsonPath("$.pageInfo.totalElements").value(3))
                .andExpect(jsonPath("$.pageInfo.hasNext").value(true))
                .andExpect(jsonPath("$.pageInfo.hasPrevious").value(false));
        getAs(user.accessToken(), LISTS + "?size=2&page=1")
                .andExpect(jsonPath("$.response.length()").value(1));
        getAs(user.accessToken(), LISTS + "?size=101")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value("size: must be at most 100"));
    }

    @Test
    @DisplayName("A page past the last is empty but says where it is")
    void listPastTheLastPageIsEmptyButNavigable() throws Exception {
        TestUser user = newUser();
        createList(user, "Only one");

        getAs(user.accessToken(), LISTS + "?page=5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.length()").value(0))
                .andExpect(jsonPath("$.pageInfo.totalElements").value(1))
                .andExpect(jsonPath("$.pageInfo.hasNext").value(false))
                .andExpect(jsonPath("$.pageInfo.hasPrevious").value(true));
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
        mvc.perform(get(LISTS).param("query", "%").header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(jsonPath("$.response.length()").value(1))
                .andExpect(jsonPath("$.response[0].name").value("100% done"));
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
        getAs(user.accessToken(), LISTS + "?sort=NEWEST")
                .andExpect(jsonPath("$.response[*].name", contains("Bravo", "Charlie", "Alpha")));
    }

    @Test
    @DisplayName("An unknown sort is 400 REQUEST_PARAMETER_INVALID naming only the field")
    void unknownSortIs400() throws Exception {
        getAs(newUser().accessToken(), LISTS + "?sort=OLDEST_FIRST")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.failed[0]").value("sort: invalid value"));
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
    @DisplayName("The dropdown lists only your lists, as {value, label}, ordered by name")
    void dropdownListsYourOptionsInDefaultOrder() throws Exception {
        TestUser user = newUser();
        String alpha = createList(user, "Alpha");
        createList(user, "Charlie");
        createList(user, "Bravo");
        createList(newUser(), "Someone else's");

        getAs(user.accessToken(), LISTS + "/dropdown")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].label", contains("Alpha", "Bravo", "Charlie")))
                .andExpect(jsonPath("$.response[0].value").value(alpha))
                .andExpect(jsonPath("$.response[0].length()").value(2));
    }

    @Test
    @DisplayName("sortDirection=DESC reverses the dropdown's name order")
    void dropdownSortDirectionReversesTheOrder() throws Exception {
        TestUser user = newUser();
        createList(user, "Alpha");
        createList(user, "Charlie");
        createList(user, "Bravo");

        getAs(user.accessToken(), LISTS + "/dropdown?sortDirection=DESC")
                .andExpect(jsonPath("$.response[*].label", contains("Charlie", "Bravo", "Alpha")));
    }

    @Test
    @DisplayName("A dropdown status other than ACTIVE is refused: lists have no active flag")
    void dropdownStatusIsRefusedWithoutAnActiveFlag() throws Exception {
        getAs(newUser().accessToken(), LISTS + "/dropdown?status=INACTIVE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.parameter").value("status"));
    }

    @Test
    @DisplayName("The dropdown's query matches part of the label, ignoring case")
    void dropdownQueryMatchesPartOfTheLabelIgnoringCase() throws Exception {
        TestUser user = newUser();
        createList(user, "Garden work");
        createList(user, "Reading");

        getAs(user.accessToken(), LISTS + "/dropdown?query=GARD")
                .andExpect(jsonPath("$.response.length()").value(1))
                .andExpect(jsonPath("$.response[0].label").value("Garden work"));
    }

    @Test
    @DisplayName("The dropdown comes in batches: nextCursor continues by name until hasMore is false")
    void dropdownComesInBatches() throws Exception {
        TestUser user = newUser();
        for (String name : new String[] {"Delta", "Alpha", "Echo", "Charlie", "Bravo"}) {
            createList(user, name);
        }

        ResultActions first = getAs(user.accessToken(), LISTS + "/dropdown?size=2")
                .andExpect(jsonPath("$.response[*].label", contains("Alpha", "Bravo")))
                .andExpect(jsonPath("$.pageInfo.hasMore").value(true));
        ResultActions second = getAs(user.accessToken(), LISTS + "/dropdown?size=2&after=" + read(first, "$.pageInfo.nextCursor"))
                .andExpect(jsonPath("$.response[*].label", contains("Charlie", "Delta")));
        getAs(user.accessToken(), LISTS + "/dropdown?size=2&after=" + read(second, "$.pageInfo.nextCursor"))
                .andExpect(jsonPath("$.response[*].label", contains("Echo")))
                .andExpect(jsonPath("$.pageInfo.hasMore").value(false))
                .andExpect(jsonPath("$.pageInfo.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("A cursor taken in one direction is refused in the other, and a tampered one is refused")
    void dropdownRefusesForeignOrTamperedCursors() throws Exception {
        TestUser user = newUser();
        createList(user, "Alpha");
        createList(user, "Bravo");
        String ascendingCursor = read(getAs(user.accessToken(), LISTS + "/dropdown?size=1"), "$.pageInfo.nextCursor");

        getAs(user.accessToken(), LISTS + "/dropdown?size=1&sortDirection=DESC&after=" + ascendingCursor)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.parameter").value("after"));
        getAs(user.accessToken(), LISTS + "/dropdown?after=not-a-cursor")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"));
    }

    @Test
    @DisplayName("A dropdown batch over the configured maximum is 400 naming size")
    void dropdownSizeOverTheMaximumIs400() throws Exception {
        getAs(newUser().accessToken(), LISTS + "/dropdown?size=101")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value("size: must be at most 100"));
    }

    @Test
    @DisplayName("List names are unique per owner ignoring case and surrounding spaces")
    void nameUniquenessIgnoresCaseAndSpaces() throws Exception {
        TestUser user = newUser();
        createList(user, "Groceries");

        postAs(user.accessToken(), LISTS, namePayload("  GROCERIES "))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TODO_LIST_NAME_TAKEN"));
    }

    @Test
    @DisplayName("Renaming to a name you already use is 409 TODO_LIST_NAME_TAKEN")
    void renameToATakenNameIs409() throws Exception {
        TestUser user = newUser();
        createList(user, "Taken");
        String id = createList(user, "Free");

        putAs(user.accessToken(), LISTS + "/" + id, updatePayload("Taken", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TODO_LIST_NAME_TAKEN"));
    }

    @Test
    @DisplayName("PUT on an unknown id is 404 TODO_LIST_NOT_FOUND")
    void updateUnknownIdIs404() throws Exception {
        putAs(newUser().accessToken(), LISTS + "/" + UUID.randomUUID(), updatePayload("Ghost", 0))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_LIST_NOT_FOUND"));
    }

    @Test
    @DisplayName("A page index over the configured maximum is 400 VALIDATION_FAILED naming page")
    void pageIndexIsBounded() throws Exception {
        getAs(newUser().accessToken(), LISTS + "?page=100001")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value("page: must be at most 100000"));
    }

    @Test
    @DisplayName("The API docs show the configured limits on body fields and query parameters")
    void apiDocsShowTheConfiguredLimits() throws Exception {
        getAs(null, "/v3/api-docs")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.TodoListCreateRequest.properties.name.maxLength").value(100))
                .andExpect(jsonPath("$.paths['/api/v1/todo-lists'].get.parameters[?(@.name == 'size')].schema.maximum").value(100))
                .andExpect(jsonPath("$.paths['/api/v1/todo-lists/dropdown'].get.parameters[?(@.name == 'query')].schema.maxLength").value(100));
    }
}
