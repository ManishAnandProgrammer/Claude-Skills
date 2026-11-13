package com.example.app.todo;

import com.example.app.support.IntegrationTest;
import com.example.app.todo.domain.TodoPriority;
import com.example.app.todo.domain.TodoStatus;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TodoIntegrationTest extends IntegrationTest {

    private static final String TODOS = "/api/v1/todos";
    private static final String LISTS = "/api/v1/todo-lists";

    @Autowired
    private PlatformTransactionManager transactionManager;

    private String createList(TestUser user, String name) throws Exception {
        return read(postAs(user.accessToken(), LISTS, """
                {"name": "%s"}
                """.formatted(name)).andExpect(status().isCreated()), "$.response.id");
    }

    private static String todoPayload(String listId, String title, String priority, String dueDate) {
        String due = dueDate == null ? "null" : "\"" + dueDate + "\"";
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

    @Test
    @DisplayName("Create answers 201 with the todo OPEN, version 0 and the audit fields")
    void createReturns201WithTheSavedTodo() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Home");

        postAs(user.accessToken(), TODOS, todoPayload(listId, "  Water the plants  ", "LOW", "2026-11-20"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.title").value("Water the plants"))
                .andExpect(jsonPath("$.response.todoListId").value(listId))
                .andExpect(jsonPath("$.response.ownerId").value(user.id().toString()))
                .andExpect(jsonPath("$.response.status").value("OPEN"))
                .andExpect(jsonPath("$.response.priority").value("LOW"))
                .andExpect(jsonPath("$.response.dueDate").value("2026-11-20"))
                .andExpect(jsonPath("$.response.completedAt").value(nullValue()))
                .andExpect(jsonPath("$.response.version").value(0))
                .andExpect(jsonPath("$.response.createdBy").value(user.id().toString()));
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
    @DisplayName("Get returns the todo; an unknown id is 404 TODO_NOT_FOUND")
    void getReturnsTheTodo() throws Exception {
        TestUser user = newUser();
        String id = createTodo(user, createList(user, "Read"), "A book", "MEDIUM", null);

        getAs(user.accessToken(), TODOS + "/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.title").value("A book"));
        getAs(user.accessToken(), TODOS + "/" + UUID.randomUUID())
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
        getAs(user.accessToken(), TODOS + "/" + id).andExpect(jsonPath("$.response.title").value("First edit"));
    }

    @Test
    @DisplayName("Moving a todo into someone else's list is 404 TODO_LIST_NOT_FOUND")
    void moveIntoAnotherUsersListIs404() throws Exception {
        TestUser user = newUser();
        String id = createTodo(user, createList(user, "Mine"), "Stay home", "LOW", null);
        String othersList = createList(newUser(), "Theirs");

        putAs(user.accessToken(), TODOS + "/" + id, updatePayload(othersList, "Escape", 0))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_LIST_NOT_FOUND"));
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
    @DisplayName("A status change with a stale version is 409; an unknown status is 400")
    void statusChangeRefusals() throws Exception {
        TestUser user = newUser();
        String id = createTodo(user, createList(user, "Refusals"), "Task", "LOW", null);
        patchAs(user.accessToken(), TODOS + "/" + id + "/status", statusPayload("DONE", 0)).andExpect(status().isOk());

        patchAs(user.accessToken(), TODOS + "/" + id + "/status", statusPayload("OPEN", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK_CONFLICT"));
        patchAs(user.accessToken(), TODOS + "/" + id + "/status", statusPayload("ARCHIVED", 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"));
    }

    @Test
    @DisplayName("Delete answers 204 and the todo is gone; an unknown id is 404")
    void deleteReturns204AndTheTodoIsGone() throws Exception {
        TestUser user = newUser();
        String id = createTodo(user, createList(user, "Bin"), "Throw away", "LOW", null);

        deleteAs(user.accessToken(), TODOS + "/" + id).andExpect(status().isNoContent());
        getAs(user.accessToken(), TODOS + "/" + id).andExpect(status().isNotFound());
        deleteAs(user.accessToken(), TODOS + "/" + id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
    }

    @Test
    @DisplayName("Deleting a list that still has todos is 409 TODO_LIST_IN_USE, without constraint names")
    void deleteListWithTodosIs409() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Busy");
        createTodo(user, listId, "Keep me", "LOW", null);

        deleteAs(user.accessToken(), LISTS + "/" + listId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TODO_LIST_IN_USE"))
                .andExpect(jsonPath("$.detail").value(not(containsString("fk_todos"))));
        getAs(user.accessToken(), LISTS + "/" + listId).andExpect(status().isOk());
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
    @DisplayName("A title of 201 characters, or notes of 10,001, is 400 naming the field")
    void createWithFieldsOverTheLimitIs400() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Limits");

        createTodo(user, listId, "t".repeat(200), "LOW", null);
        postAs(user.accessToken(), TODOS, todoPayload(listId, "t".repeat(201), "LOW", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failed[0]").value(containsString("title")));
        postAs(user.accessToken(), TODOS, """
                {"todoListId": "%s", "title": "Long notes", "notes": "%s", "priority": "LOW"}
                """.formatted(listId, "n".repeat(10_001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failed[0]").value(containsString("notes")));
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
    void anotherUsersTodoIs404() throws Exception {
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
                .andExpect(jsonPath("$.response.status").value("OPEN"));
    }

    @Test
    @DisplayName("Walking the cursor batches returns every todo once, in due-date order with undated last")
    void cursorBatchesWalkTheWholeListInOrder() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Walk");
        List<String> expected = new ArrayList<>();
        for (int day = 1; day <= 6; day++) {
            createTodo(user, listId, "Due " + day, "LOW", "2026-12-%02d".formatted(day));
            expected.add("Due " + day);
        }
        for (int undated = 1; undated <= 3; undated++) {
            createTodo(user, listId, "Undated " + undated, "LOW", null);
        }

        List<String> walked = new ArrayList<>();
        String after = null;
        int batches = 0;
        do {
            ResultActions batch = getAs(user.accessToken(), TODOS + "?size=4" + (Objects.isNull(after) ? "" : "&after=" + after))
                    .andExpect(status().isOk());
            List<String> titles = read(batch, "$.response[*].title");
            walked.addAll(titles);
            after = Boolean.TRUE.equals(read(batch, "$.pageInfo.hasMore")) ? read(batch, "$.pageInfo.nextCursor") : null;
            batches++;
        } while (after != null);

        assertThat(batches).isEqualTo(3);
        assertThat(walked).hasSize(9).doesNotHaveDuplicates();
        assertThat(walked.subList(0, 6)).isEqualTo(expected);
        assertThat(walked.subList(6, 9)).containsExactlyInAnyOrder("Undated 1", "Undated 2", "Undated 3");
    }

    @Test
    @DisplayName("The last batch says hasMore false with no cursor; a size over the configured maximum is 400")
    void lastBatchAndSizeLimit() throws Exception {
        TestUser user = newUser();
        createTodo(user, createList(user, "Small"), "Only", "LOW", null);

        getAs(user.accessToken(), TODOS)
                .andExpect(jsonPath("$.response.length()").value(1))
                .andExpect(jsonPath("$.pageInfo.hasMore").value(false))
                .andExpect(jsonPath("$.pageInfo.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.response[0].notes").doesNotExist());
        getAs(user.accessToken(), TODOS + "?size=101")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failed[0]").value("size: must be at most 100"));
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
    @DisplayName("todoListId returns only that list's todos")
    void filterByListIdReturnsOnlyMatching() throws Exception {
        TestUser user = newUser();
        String work = createList(user, "Work");
        createTodo(user, work, "Report", "LOW", null);
        createTodo(user, createList(user, "Home"), "Laundry", "LOW", null);

        getAs(user.accessToken(), TODOS + "?todoListId=" + work)
                .andExpect(jsonPath("$.response[*].title", hasItem("Report")))
                .andExpect(jsonPath("$.response[*].title", not(hasItem("Laundry"))));
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
                .andExpect(jsonPath("$.response.length()").value(1))
                .andExpect(jsonPath("$.response[0].title").value("Task " + priority));
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
                .andExpect(jsonPath("$.response.length()").value(1))
                .andExpect(jsonPath("$.response[0].title").value(expected));
    }

    @Test
    @DisplayName("dueOnOrBefore includes the day itself, not later or undated todos")
    void filterByDueDateReturnsOnlyMatching() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Due");
        createTodo(user, listId, "This week", "LOW", "2026-11-18");
        createTodo(user, listId, "On the day", "LOW", "2026-11-30");
        createTodo(user, listId, "Day after", "LOW", "2026-12-01");
        createTodo(user, listId, "Someday", "LOW", null);

        getAs(user.accessToken(), TODOS + "?dueOnOrBefore=2026-11-30")
                .andExpect(jsonPath("$.response[*].title", contains("This week", "On the day")));
    }

    @Test
    @DisplayName("Unconvertible filter values are 400 REQUEST_PARAMETER_INVALID, naming the field and no Java types")
    void unconvertibleFilterValuesAre400() throws Exception {
        TestUser user = newUser();

        String body = getAs(user.accessToken(), TODOS + "?status=FOO&dueOnOrBefore=30-11-2026&todoListId=nope")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.failed", containsInAnyOrder(
                        "status: invalid value", "dueOnOrBefore: invalid value", "todoListId: invalid value")))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("java.", "com.example", "Exception");
    }

    @Test
    @DisplayName("query matches part of the title, ignoring case")
    void filterByQueryReturnsOnlyMatching() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Search");
        createTodo(user, listId, "Call the Dentist", "LOW", null);
        createTodo(user, listId, "Buy bread", "LOW", null);

        getAs(user.accessToken(), TODOS + "?query=dent")
                .andExpect(jsonPath("$.response.length()").value(1))
                .andExpect(jsonPath("$.response[0].title").value("Call the Dentist"));
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
    @DisplayName("PUT and PATCH on an unknown id are 404 TODO_NOT_FOUND")
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
    }

    @Test
    @DisplayName("An update or status change without version is 400 naming version")
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
                .andExpect(jsonPath("$.failed[0]").value(containsString("version")));
    }

    @Test
    @DisplayName("An unknown priority in the body is 400 REQUEST_UNREADABLE")
    void unknownPriorityInTheBodyIs400() throws Exception {
        TestUser user = newUser();

        postAs(user.accessToken(), TODOS, todoPayload(createList(user, "Enums"), "x", "URGENT", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"));
    }

    @Test
    @DisplayName("Concurrent status changes with one version apply exactly once")
    void concurrentStatusChangesWithOneVersionApplyOnce() throws Exception {
        TestUser user = newUser();
        String id = createTodo(user, createList(user, "Race"), "Contested", "LOW", null);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> statuses = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int attempt = 0; attempt < 6; attempt++) {
                Callable<Integer> completeOnce = () -> {
                    start.await();
                    return patchAs(user.accessToken(), TODOS + "/" + id + "/status", statusPayload("DONE", 0))
                            .andReturn().getResponse().getStatus();
                };
                statuses.add(executor.submit(completeOnce));
            }
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> status : statuses) {
                results.add(status.get());
            }
            assertThat(results).containsOnlyOnce(200).containsOnly(200, 409);
        }
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
}
