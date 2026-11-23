package com.example.app.todo;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TodoRequesterPickerIntegrationTest extends IntegrationTest {

    private static final String TODOS = "/api/v1/todos";
    private static final String PICKER = "/api/v1/users/dropdown";

    private TestUser named(TestUser user, String fullName) {
        sql("update users set full_name = ? where id = ?", fullName, user.id());
        return user;
    }

    private void suspend(TestUser user) {
        sql("update users set status = 'SUSPENDED' where id = ?", user.id());
    }

    private String createList(TestUser user) throws Exception {
        return read(postAs(user.accessToken(), "/api/v1/todo-lists", """
                {"name": "Asked %s"}
                """.formatted(UUID.randomUUID())).andExpect(status().isCreated()), "$.response.id");
    }

    private ResultActions postTodo(TestUser user, String listId, UUID requesterId) throws Exception {
        return postAs(user.accessToken(), TODOS, """
                {"todoListId": "%s", "title": "Asked for", "priority": "LOW", "requesterId": "%s"}
                """.formatted(listId, requesterId));
    }

    private ResultActions putTodo(TestUser user, String id, String listId, UUID requesterId, long version) throws Exception {
        return putAs(user.accessToken(), TODOS + "/" + id, """
                {"todoListId": "%s", "title": "Edited", "priority": "LOW", "requesterId": "%s", "version": %d}
                """.formatted(listId, requesterId, version));
    }

    @Test
    @DisplayName("""
            The picker for a todo's write lists the active accounts that may be its requester; the create accepts a \
            listed one and refuses one it leaves out with 409 TODO_REQUESTER_INELIGIBLE""")
    void thePickerOffersExactlyWhomTheWriteAccepts() throws Exception {
        String prefix = "Picked-" + UUID.randomUUID();
        TestUser owner = newUser();
        TestUser asker = named(newUser(), prefix + " Ann");
        TestUser away = named(newUser(), prefix + " Bob");
        suspend(away);
        String listId = createList(owner);

        getAs(owner.accessToken(), PICKER + "?for=TODO_CREATE_OWN&query=" + prefix)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].value", contains(asker.id().toString())))
                .andExpect(jsonPath("$.response[*].label", contains(prefix + " Ann")))
                .andExpect(jsonPath("$.pageInfo.hasMore").value(false));
        getAs(owner.accessToken(), PICKER + "?for=TODO_EDIT_OWN&query=" + prefix)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].value", contains(asker.id().toString())));

        postTodo(owner, listId, asker.id()).andExpect(status().isCreated());
        postTodo(owner, listId, away.id())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TODO_REQUESTER_INELIGIBLE"));
    }

    @Test
    @DisplayName("""
            The picker leaves its caller out, who is never the requester of their own todo: naming yourself is 400 \
            TODO_REQUESTER_IS_OWNER at #/requesterId on the create and the update, and nothing is stored""")
    void theOwnerIsNeverTheirOwnRequester() throws Exception {
        String prefix = "Self-" + UUID.randomUUID();
        TestUser owner = named(newUser(), prefix + " Ann");
        TestUser asker = named(newUser(), prefix + " Bob");
        String listId = createList(owner);

        getAs(owner.accessToken(), PICKER + "?for=TODO_CREATE_OWN&query=" + prefix)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].value", contains(asker.id().toString())));
        getAs(owner.accessToken(), PICKER + "?for=TODO_EDIT_OWN&query=" + prefix)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].value", contains(asker.id().toString())));
        getAs(asker.accessToken(), PICKER + "?for=TODO_CREATE_OWN&query=" + prefix)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].value", contains(owner.id().toString())));

        postTodo(owner, listId, owner.id())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("TODO_REQUESTER_IS_OWNER"))
                .andExpect(jsonPath("$.errors[0].pointer").value("#/requesterId"));
        assertThat(jdbc.queryForObject("select count(*) from todos where todo_list_id = ?", Integer.class, UUID.fromString(listId)))
                .isZero();
        String id = read(postTodo(owner, listId, asker.id()).andExpect(status().isCreated()), "$.response.id");
        putTodo(owner, id, listId, owner.id(), 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("TODO_REQUESTER_IS_OWNER"))
                .andExpect(jsonPath("$.errors[0].pointer").value("#/requesterId"));
        getAs(owner.accessToken(), TODOS + "/" + id)
                .andExpect(jsonPath("$.response.version").value(0))
                .andExpect(jsonPath("$.response.requesterId").value(asker.id().toString()));
    }

    @Test
    @DisplayName("""
            A requester suspended after being named stays on the todo through an edit that keeps them; naming \
            another one who can't be named is 409 TODO_REQUESTER_INELIGIBLE and changes nothing""")
    void aStaleRequesterIsKeptAndAnIneligibleOneRefused() throws Exception {
        TestUser owner = newUser();
        TestUser asker = newUser();
        TestUser away = newUser();
        suspend(away);
        String listId = createList(owner);
        String id = read(postTodo(owner, listId, asker.id()).andExpect(status().isCreated()), "$.response.id");
        suspend(asker);

        putTodo(owner, id, listId, asker.id(), 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.requesterId").value(asker.id().toString()));
        putTodo(owner, id, listId, away.id(), 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TODO_REQUESTER_INELIGIBLE"));
        getAs(owner.accessToken(), TODOS + "/" + id)
                .andExpect(jsonPath("$.response.version").value(1))
                .andExpect(jsonPath("$.response.requesterId").value(asker.id().toString()));
    }

    @Test
    @DisplayName("""
            The picker refuses a for you don't hold (403 ACCESS_DENIED), a for whose write names nobody and a status \
            other than ACTIVE (400 REQUEST_PARAMETER_INVALID at the parameter)""")
    void thePickerRefusesWhatItCantServe() throws Exception {
        TestUser user = newUser();

        getAs(user.accessToken(), PICKER + "?for=ACCOUNT_READ")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        getAs(user.accessToken(), PICKER + "?for=TODO_LIST_CREATE_OWN")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.errors[0].parameter").value("for"));
        getAs(user.accessToken(), PICKER + "?for=TODO_CREATE_OWN&status=ALL")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.errors[0].parameter").value("status"));
    }
}
