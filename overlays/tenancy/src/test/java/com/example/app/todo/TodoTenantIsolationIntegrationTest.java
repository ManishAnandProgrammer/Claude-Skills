package com.example.app.todo;

import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Another tenant's records don't exist for a caller: GET, PUT and DELETE answer 404 as for a missing
 * id, lists never show them, and nothing of them changes. crud-gen generates this class per entity.
 * Todos are written by any member, so the stranger is a plain USER; for a root only ADMINs write, the
 * stranger is {@code newUserIn(newTenant(), "ADMIN")}, or the 404s would be the role check's 403.
 */
@WithoutTestTenant
class TodoTenantIsolationIntegrationTest extends IntegrationTest {

    private static final String LISTS = "/api/v1/todo-lists";
    private static final String TODOS = "/api/v1/todos";

    private String createList(TestUser user, String name) throws Exception {
        return read(postAs(user.accessToken(), LISTS, """
                {"name": "%s"}
                """.formatted(name)).andExpect(status().isCreated()), "$.response.id");
    }

    private String createTodo(TestUser user, String listId, String title) throws Exception {
        return read(postAs(user.accessToken(), TODOS, """
                {"todoListId": "%s", "title": "%s", "notes": null, "priority": "LOW", "dueDate": null}
                """.formatted(listId, title)).andExpect(status().isCreated()), "$.response.id");
    }

    @Test
    @DisplayName("Another tenant's todo is 404 TODO_NOT_FOUND on GET, PUT and DELETE, and stays unchanged")
    void anotherTenantsTodoDoesNotExist() throws Exception {
        TestUser owner = newUser();
        String listId = createList(owner, "Ours");
        String todoId = createTodo(owner, listId, "Ours to read");
        TestUser stranger = newUserIn(newTenant());

        getAs(stranger.accessToken(), TODOS + "/" + todoId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
        putAs(stranger.accessToken(), TODOS + "/" + todoId, """
                {"todoListId": "%s", "title": "Taken over", "notes": null, "priority": "HIGH", "dueDate": null, "version": 0}
                """.formatted(listId))
                .andExpect(status().isNotFound());
        deleteAs(stranger.accessToken(), TODOS + "/" + todoId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));

        getAs(owner.accessToken(), TODOS + "/" + todoId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.title").value("Ours to read"))
                .andExpect(jsonPath("$.response.version").value(0));
    }

    @Test
    @DisplayName("Another tenant's lists never show the caller's records, nor the reverse")
    void listsShowOneTenantOnly() throws Exception {
        TestUser owner = newUser();
        String listId = createList(owner, "Ours to list");
        String todoId = createTodo(owner, listId, "Ours to list");
        TestUser stranger = newUserIn(newTenant());
        String strangersList = createList(stranger, "Ours to list");
        String strangersTodo = createTodo(stranger, strangersList, "Ours to list");

        // Each list first shows its caller's own row: an absence alone would pass on a broken list too.
        getAs(stranger.accessToken(), LISTS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].id", hasItem(strangersList)))
                .andExpect(jsonPath("$.response[*].id", not(hasItem(listId))));
        getAs(stranger.accessToken(), TODOS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].id", hasItem(strangersTodo)))
                .andExpect(jsonPath("$.response[*].id", not(hasItem(todoId))));
        getAs(owner.accessToken(), LISTS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].id", hasItem(listId)))
                .andExpect(jsonPath("$.response[*].id", not(hasItem(strangersList))));
        getAs(owner.accessToken(), TODOS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].id", hasItem(todoId)))
                .andExpect(jsonPath("$.response[*].id", not(hasItem(strangersTodo))));
    }
}
