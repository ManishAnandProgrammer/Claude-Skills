package com.example.app.todo;

import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A todo's requester is a member of the tenant (crud-gen tests.md row 28g): checked through Members whenever a
 * write names someone new, shown by name while they are a member, by id alone once they have left, the todo
 * itself unchanged and still editable (a PUT re-sending the former member keeps them).
 * Each test works in a tenant of its own, through the tokens that name it.
 */
@WithoutTestTenant
class TodoRequesterTenancyIntegrationTest extends IntegrationTest {

    private static final String LISTS = "/api/v1/todo-lists";
    private static final String TODOS = "/api/v1/todos";
    private static final String MEMBERS = "/api/v1/tenant-members";

    private String createList(TestUser user) throws Exception {
        return read(postAs(user.accessToken(), LISTS, """
                {"name": "Asked for"}
                """).andExpect(status().isCreated()), "$.response.id");
    }

    private ResultActions putTodo(TestUser user, String id, String listId, UUID requesterId, long version) throws Exception {
        return putAs(user.accessToken(), TODOS + "/" + id, """
                {"todoListId": "%s", "title": "Still asked", "priority": "LOW", "requesterId": "%s", "version": %d}
                """.formatted(listId, requesterId, version));
    }

    private ResultActions postTodo(TestUser user, String listId, UUID requesterId) throws Exception {
        return postAs(user.accessToken(), TODOS, """
                {"todoListId": "%s", "title": "Asked", "priority": "LOW", "requesterId": "%s"}
                """.formatted(listId, requesterId));
    }

    @Test
    @DisplayName("A member who asked is shown by id and name; a removed one by id alone, the todo listed, read, changed and deleted as before")
    void aRemovedRequesterKeepsTheirTodos() throws Exception {
        TestTenant tenant = newTenant();
        TestUser admin = newUserIn(tenant, "ADMIN");
        TestUser requester = newUserIn(tenant);
        String listId = createList(admin);
        String id = read(postTodo(admin, listId, requester.id())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.requester.id").value(requester.id().toString()))
                .andExpect(jsonPath("$.response.requester.fullName").isNotEmpty()), "$.response.id");

        deleteAs(admin.accessToken(), MEMBERS + "/" + requester.id()).andExpect(status().isNoContent());

        getAs(admin.accessToken(), TODOS + "?todoListId=" + listId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[0].id").value(id))
                .andExpect(jsonPath("$.response[0].requesterId").value(requester.id().toString()));
        getAs(admin.accessToken(), TODOS + "/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.requester.id").value(requester.id().toString()))
                .andExpect(jsonPath("$.response.requester.fullName").doesNotExist());
        // A PUT re-sends the person it read: a former member is kept, never re-checked.
        putTodo(admin, id, listId, requester.id(), 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.requester.id").value(requester.id().toString()));
        deleteAs(admin.accessToken(), TODOS + "/" + id).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("""
            A PUT that newly names a removed member, or another tenant's, is 404 TENANT_MEMBER_NOT_FOUND and changes \
            nothing; one without a requester clears it""")
    void anUpdateNamesOnlyACurrentMember() throws Exception {
        TestTenant tenant = newTenant();
        TestUser admin = newUserIn(tenant, "ADMIN");
        TestUser requester = newUserIn(tenant);
        TestUser leaving = newUserIn(tenant);
        TestUser stranger = newUserIn(newTenant());
        String listId = createList(admin);
        String id = read(postTodo(admin, listId, requester.id()).andExpect(status().isCreated()), "$.response.id");
        deleteAs(admin.accessToken(), MEMBERS + "/" + leaving.id()).andExpect(status().isNoContent());

        putTodo(admin, id, listId, leaving.id(), 0)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_NOT_FOUND"));
        putTodo(admin, id, listId, stranger.id(), 0)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_NOT_FOUND"));
        getAs(admin.accessToken(), TODOS + "/" + id)
                .andExpect(jsonPath("$.response.version").value(0))
                .andExpect(jsonPath("$.response.requester.id").value(requester.id().toString()));
        putAs(admin.accessToken(), TODOS + "/" + id, """
                {"todoListId": "%s", "title": "Nobody asked", "priority": "LOW", "version": 0}
                """.formatted(listId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.requester").doesNotExist());
    }

    @Test
    @DisplayName("A create naming a removed member, or another tenant's, is 404 TENANT_MEMBER_NOT_FOUND; re-added, they are named again")
    void onlyACurrentMemberIsNamed() throws Exception {
        TestTenant tenant = newTenant();
        TestUser admin = newUserIn(tenant, "ADMIN");
        TestUser requester = newUserIn(tenant);
        TestUser stranger = newUserIn(newTenant());
        String listId = createList(admin);

        postTodo(admin, listId, stranger.id())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_NOT_FOUND"));
        deleteAs(admin.accessToken(), MEMBERS + "/" + requester.id()).andExpect(status().isNoContent());
        postTodo(admin, listId, requester.id())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_NOT_FOUND"));

        // roles:begin
        postAs(admin.accessToken(), MEMBERS, """
                {"email": "%s"}
                """.formatted(requester.email())).andExpect(status().isCreated());
        // roles:else
        // postAs(admin.accessToken(), MEMBERS, """
        //         {"email": "%s", "role": "USER"}
        //         """.formatted(requester.email())).andExpect(status().isCreated());
        // roles:end
        postTodo(admin, listId, requester.id())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.requester.fullName").isNotEmpty());
    }
    // metadata:begin

    @Test
    @DisplayName("Another tenant's first todo is #1: each tenant numbers its own")
    void eachTenantNumbersFromOne() throws Exception {
        TestUser member = newUserIn(newTenant());
        String listId = createList(member);

        postAs(member.accessToken(), TODOS, """
                {"todoListId": "%s", "title": "First", "priority": "LOW"}
                """.formatted(listId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.number").value(1));
        postAs(member.accessToken(), TODOS, """
                {"todoListId": "%s", "title": "Second", "priority": "LOW"}
                """.formatted(listId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.number").value(2));
    }
    // metadata:end
}
