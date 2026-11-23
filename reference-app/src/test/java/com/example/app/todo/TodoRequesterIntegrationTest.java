package com.example.app.todo;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TodoRequesterIntegrationTest extends IntegrationTest {

    private static final String TODOS = "/api/v1/todos";

    @Autowired
    private PlatformTransactionManager transactionManager;

    private record Asked(TestUser owner, TestUser requester, String listId, String todoId, String tagId, String entryId) {
    }

    private Asked askedTodo() throws Exception {
        TestUser owner = newUser();
        TestUser requester = newUser();
        String listId = read(postAs(owner.accessToken(), "/api/v1/todo-lists", """
                {"name": "Asked %s"}
                """.formatted(UUID.randomUUID())).andExpect(status().isCreated()), "$.response.id");
        String tagId = read(postAs(owner.accessToken(), "/api/v1/tags", """
                {"name": "Asked", "color": null}
                """).andExpect(status().isCreated()), "$.response.id");
        String todoId = read(postAs(owner.accessToken(), TODOS, """
                {"todoListId": "%s", "title": "Asked for", "priority": "LOW", "requesterId": "%s", "tagIds": ["%s"]}
                """.formatted(listId, requester.id(), tagId)).andExpect(status().isCreated()), "$.response.id");
        String entryId = read(postAs(owner.accessToken(), TODOS + "/" + todoId + "/time-entries", """
                {"minutes": 30, "spentOn": "2026-11-02", "note": "Started", "billable": false}
                """).andExpect(status().isCreated()), "$.response.id");
        return new Asked(owner, requester, listId, todoId, tagId, entryId);
    }

    private ResultActions putTodo(TestUser caller, Asked asked, String requesterId, long version) throws Exception {
        String requester = requesterId == null ? "null" : "\"" + requesterId + "\"";
        return putAs(caller.accessToken(), TODOS + "/" + asked.todoId(), """
                {"todoListId": "%s", "title": "Changed", "priority": "HIGH", "requesterId": %s, "version": %d}
                """.formatted(asked.listId(), requester, version));
    }

    private enum OwnersCall {
        UPDATE, REPLACE_TAGS, CHANGE_STATUS, DELETE, LIST_TIME_ENTRIES, GET_TIME_ENTRY, CREATE_TIME_ENTRY,
        UPDATE_TIME_ENTRY, DELETE_TIME_ENTRY, CREATE_PLAN
    }

    private ResultActions send(OwnersCall call, TestUser caller, Asked asked) throws Exception {
        String todo = TODOS + "/" + asked.todoId();
        String entry = todo + "/time-entries/" + asked.entryId();
        return switch (call) {
            case UPDATE -> putTodo(caller, asked, caller.id().toString(), 0);
            case REPLACE_TAGS -> putAs(caller.accessToken(), todo + "/tags", """
                    {"tagIds": [], "version": 0}
                    """);
            case CHANGE_STATUS -> patchAs(caller.accessToken(), todo + "/status", """
                    {"status": "DONE", "version": 0}
                    """);
            case DELETE -> deleteAs(caller.accessToken(), todo);
            case LIST_TIME_ENTRIES -> getAs(caller.accessToken(), todo + "/time-entries");
            case GET_TIME_ENTRY -> getAs(caller.accessToken(), entry);
            case CREATE_TIME_ENTRY -> postAs(caller.accessToken(), todo + "/time-entries", """
                    {"minutes": 15, "spentOn": "2026-11-03", "note": null, "billable": false}
                    """);
            case UPDATE_TIME_ENTRY -> putAs(caller.accessToken(), entry, """
                    {"minutes": 45, "spentOn": "2026-11-02", "note": null, "billable": true, "version": 0}
                    """);
            case DELETE_TIME_ENTRY -> deleteAs(caller.accessToken(), entry);
            case CREATE_PLAN -> postAs(caller.accessToken(), "/api/v1/plans", """
                    {"todoId": "%s", "startsOn": "2026-12-01", "endsOn": "2026-12-02"}
                    """.formatted(asked.todoId()));
        };
    }

    // A piece marker can't sit inside a chain of matchers.
    // tenancy:begin
    // private static final String REQUESTER_ID = "$.response.requester.id";
    // tenancy:else
    private static final String REQUESTER_ID = "$.response.requesterId";
    // tenancy:end

    private void assertUnchanged(Asked asked) throws Exception {
        getAs(asked.owner().accessToken(), TODOS + "/" + asked.todoId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.title").value("Asked for"))
                .andExpect(jsonPath("$.response.status").value("OPEN"))
                .andExpect(jsonPath(REQUESTER_ID).value(asked.requester().id().toString()))
                .andExpect(jsonPath("$.response.tags[*].id", hasItem(asked.tagId())))
                .andExpect(jsonPath("$.response.version").value(0));
        getAs(asked.owner().accessToken(), TODOS + "/" + asked.todoId() + "/time-entries/" + asked.entryId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.minutes").value(30))
                .andExpect(jsonPath("$.response.version").value(0));
        assertThat(jdbc.queryForObject("select count(*) from time_entries where todo_id = ?::uuid", Integer.class, asked.todoId())).isOne();
        assertThat(jdbc.queryForObject("select count(*) from plans where todo_id = ?::uuid", Integer.class, asked.todoId())).isZero();
    }

    @Test
    @DisplayName("""
            The requester reads the todo (200, as its owner sees it, but allowedActions.edit not allowed, for the one \
            reason its writes answer: TODO_NOT_YOURS, the same message); their own list of todos doesn't show it; the \
            owner's detail allows edit with no reason""")
    void theRequesterReadsTheTodo() throws Exception {
        Asked asked = askedTodo();
        String refusal = read(send(OwnersCall.CHANGE_STATUS, asked.requester(), asked).andExpect(status().isForbidden()), "$.detail");

        getAs(asked.requester().accessToken(), TODOS + "/" + asked.todoId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.id").value(asked.todoId()))
                .andExpect(jsonPath("$.response.title").value("Asked for"))
                .andExpect(jsonPath("$.response.ownerId").value(asked.owner().id().toString()))
                .andExpect(jsonPath(REQUESTER_ID).value(asked.requester().id().toString()))
                .andExpect(jsonPath("$.response.todoList.id").value(asked.listId()))
                .andExpect(jsonPath("$.response.allowedActions.edit.allowed").value(false))
                .andExpect(jsonPath("$.response.allowedActions.edit.reasons[*].errorCode", contains("TODO_NOT_YOURS")))
                .andExpect(jsonPath("$.response.allowedActions.edit.reasons[0].message").value(refusal));
        getAs(asked.owner().accessToken(), TODOS + "/" + asked.todoId())
                .andExpect(jsonPath("$.response.allowedActions.edit.allowed").value(true))
                .andExpect(jsonPath("$.response.allowedActions.edit.reasons", empty()));
        getAs(asked.requester().accessToken(), TODOS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].id", not(hasItem(asked.todoId()))));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(OwnersCall.class)
    @DisplayName("Every other call of the requester on the todo is 403 TODO_NOT_YOURS without internals; nothing changes")
    void everyOtherCallOfTheRequesterIs403(OwnersCall call) throws Exception {
        Asked asked = askedTodo();

        String body = send(call, asked.requester(), asked)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_YOURS"))
                .andExpect(jsonPath("$.detail", containsString(asked.todoId())))
                .andReturn().getResponse().getContentAsString();

        assertNoInternals(body);
        assertUnchanged(asked);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(OwnersCall.class)
    @DisplayName("A stranger gets 404 TODO_NOT_FOUND on every call, as for a missing todo; nothing changes")
    void aStrangerGets404OnEveryCall(OwnersCall call) throws Exception {
        Asked asked = askedTodo();
        TestUser stranger = newUser();

        send(call, stranger, asked)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));

        getAs(stranger.accessToken(), TODOS + "/" + asked.todoId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
        assertUnchanged(asked);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(OwnersCall.class)
    @DisplayName("The owner keeps every right on a todo with a requester: each call succeeds")
    void theOwnerKeepsEveryRight(OwnersCall call) throws Exception {
        Asked asked = askedTodo();

        int status = send(call, asked.owner(), asked).andReturn().getResponse().getStatus();

        // A todo with time entries refuses its delete, whoever asks: that is its rule, not a right.
        if (call == OwnersCall.DELETE) {
            assertThat(status).isEqualTo(409);
            return;
        }
        assertThat(status).isBetween(200, 204);
    }

    @Test
    @DisplayName("""
            The right follows the requester: replaced, the former requester gets 404 and the new one reads the todo \
            and gets 403 on a change; cleared, nobody but the owner sees it""")
    void theRightFollowsTheRequester() throws Exception {
        Asked asked = askedTodo();
        TestUser next = newUser();

        putTodo(asked.owner(), asked, next.id().toString(), 0).andExpect(status().isOk());

        getAs(asked.requester().accessToken(), TODOS + "/" + asked.todoId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
        putTodo(asked.requester(), asked, null, 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
        getAs(next.accessToken(), TODOS + "/" + asked.todoId()).andExpect(status().isOk());
        putTodo(next, asked, null, 1)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_YOURS"));

        putTodo(asked.owner(), asked, null, 1).andExpect(status().isOk());
        getAs(next.accessToken(), TODOS + "/" + asked.todoId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
    }

    @Test
    @DisplayName("While the owner holds the todo's lock, the requester's delete is 403 and a stranger's 404 at once, never queued behind it")
    void aRefusedWriteNeverWaitsForTheOwnersLock() throws Exception {
        Asked asked = askedTodo();
        TestUser stranger = newUser();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (ExecutorService executor = newExecutor()) {
            Future<?> holder = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.queryForList("select id from todos where id = ?::uuid for update", asked.todoId());
                locked.countDown();
                awaitQuietly(release);
            }));
            awaitLatch(locked);
            try {
                deleteAs(asked.requester().accessToken(), TODOS + "/" + asked.todoId())
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.errorCode").value("TODO_NOT_YOURS"));
                deleteAs(stranger.accessToken(), TODOS + "/" + asked.todoId())
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
            } finally {
                release.countDown();
            }
            awaitResult(holder);
        }
        assertUnchanged(asked);
    }

    @Test
    @DisplayName("The API docs name TODO_NOT_YOURS on every owner's call, and the detail as the requester's to read")
    void apiDocsNameTheRequestersRefusal() throws Exception {
        getAs(null, "/v3/api-docs")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/todos/{id}'].get.summary", containsString("asked for")))
                .andExpect(jsonPath("$.paths['/api/v1/todos/{id}'].put.responses['403'].description", containsString("TODO_NOT_YOURS")))
                .andExpect(jsonPath("$.paths['/api/v1/todos/{id}'].delete.responses['403'].description", containsString("TODO_NOT_YOURS")))
                .andExpect(jsonPath("$.paths['/api/v1/todos/{id}/status'].patch.responses['403'].description", containsString("TODO_NOT_YOURS")))
                .andExpect(jsonPath("$.paths['/api/v1/todos/{id}/tags'].put.responses['403'].description", containsString("TODO_NOT_YOURS")))
                .andExpect(jsonPath("$.paths['/api/v1/todos/{todoId}/time-entries'].get.responses['403'].description", containsString("TODO_NOT_YOURS")))
                .andExpect(jsonPath("$.paths['/api/v1/todos/{todoId}/time-entries'].post.responses['403'].description", containsString("TODO_NOT_YOURS")))
                .andExpect(jsonPath("$.paths['/api/v1/todos/{todoId}/time-entries/{id}'].get.responses['403'].description", containsString("TODO_NOT_YOURS")))
                .andExpect(jsonPath("$.paths['/api/v1/todos/{todoId}/time-entries/{id}'].put.responses['403'].description", containsString("TODO_NOT_YOURS")))
                .andExpect(jsonPath("$.paths['/api/v1/todos/{todoId}/time-entries/{id}'].delete.responses['403'].description", containsString("TODO_NOT_YOURS")))
                .andExpect(jsonPath("$.paths['/api/v1/plans'].post.responses['403'].description", containsString("TODO_NOT_YOURS")));
    }
}
