package com.example.app.planning;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlanOptionsIntegrationTest extends IntegrationTest {

    private static final String PLANS = "/api/v1/plans";
    private static final String LANGUAGE = "en-GB,en;q=0.8";

    private String createList(TestUser user) throws Exception {
        return read(postAs(user.accessToken(), "/api/v1/todo-lists", """
                {"name": "Options %s"}
                """.formatted(UUID.randomUUID())).andExpect(status().isCreated()), "$.response.id");
    }

    private String createTodo(TestUser user, String listId, String title) throws Exception {
        return read(postAs(user.accessToken(), "/api/v1/todos", """
                {"todoListId": "%s", "title": "%s", "priority": "LOW"}
                """.formatted(listId, title)).andExpect(status().isCreated()), "$.response.id");
    }

    private void markDone(TestUser user, String todoId) throws Exception {
        patchAs(user.accessToken(), "/api/v1/todos/" + todoId + "/status", """
                {"status": "DONE", "version": 0}
                """).andExpect(status().isOk());
    }

    private ResultActions createPlan(TestUser user, String todoId, String startsOn, String endsOn) throws Exception {
        return mvc.perform(post(PLANS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                .header(HttpHeaders.ACCEPT_LANGUAGE, LANGUAGE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"todoId": "%s", "startsOn": "%s", "endsOn": "%s"}
                        """.formatted(todoId, startsOn, endsOn)));
    }

    private ResultActions getInLanguage(TestUser user, String path) throws Exception {
        return mvc.perform(get(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                .header(HttpHeaders.ACCEPT_LANGUAGE, LANGUAGE));
    }

    private long statementsFor(TestUser user, String path) throws Exception {
        statistics().clear();
        getAs(user.accessToken(), path).andExpect(status().isOk());
        return statistics().getPrepareStatementCount();
    }

    private static String todoOptions(String listId) {
        return PLANS + "/todo-options?todoListId=" + listId;
    }

    @Test
    @DisplayName("""
            Each todo option is exactly {value, label, selectable}: an open todo selectable with no reasons, a done one \
            refused with the create's own code and message""")
    void aDoneTodoIsRefusedWithTheCreatesReason() throws Exception {
        TestUser user = newUser();
        String list = createList(user);
        String open = createTodo(user, list, "Apples");
        String done = createTodo(user, list, "Bread");
        markDone(user, done);
        String detail = read(createPlan(user, done, "2026-03-10", "2026-03-10")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PLAN_TODO_DONE")), "$.detail");

        getInLanguage(user, todoOptions(list))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].value", contains(open, done)))
                .andExpect(jsonPath("$.response[0].length()").value(3))
                .andExpect(jsonPath("$.response[0].label").value("Apples"))
                .andExpect(jsonPath("$.response[0].selectable.allowed").value(true))
                .andExpect(jsonPath("$.response[0].selectable.reasons", empty()))
                .andExpect(jsonPath("$.response[1].selectable.allowed").value(false))
                .andExpect(jsonPath("$.response[1].selectable.reasons.length()").value(1))
                .andExpect(jsonPath("$.response[1].selectable.reasons[0].errorCode").value("PLAN_TODO_DONE"))
                .andExpect(jsonPath("$.response[1].selectable.reasons[0].message").value(detail));
    }

    @Test
    @DisplayName("The todo options cost one statement, for one option and for a full batch; another user's list offers none")
    void todoOptionsRunOneStatement() throws Exception {
        TestUser user = newUser();
        String single = createList(user);
        createTodo(user, single, "Only");
        String many = createList(user);
        // A full batch of 2 and one more: the count can't grow with the batch. Few rows, since the shared todos
        // table's statistics steer other classes' plans (UpgradeFromPreviousReleaseIntegrationTest).
        for (int index = 0; index < 3; index++) {
            createTodo(user, many, "Option " + index);
        }
        getAs(user.accessToken(), todoOptions(many) + "&size=2")
                .andExpect(jsonPath("$.response.length()").value(2))
                .andExpect(jsonPath("$.pageInfo.hasMore").value(true));
        TestUser stranger = newUser();
        getAs(stranger.accessToken(), todoOptions(single))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.length()").value(0));

        assertThat(statementsFor(user, todoOptions(single) + "&size=2")).isEqualTo(1);
        assertThat(statementsFor(user, todoOptions(many) + "&size=2")).isEqualTo(1);
        assertThat(statementsFor(stranger, todoOptions(single))).isEqualTo(1);
    }

    @Test
    @DisplayName("""
            The availability covers every asked day in spans: taken by live plans (cut to the range), refused with the \
            create's overlap code and message; a cancelled plan's days free""")
    void theAvailabilityListsFreeAndTakenSpans() throws Exception {
        TestUser user = newUser();
        String list = createList(user);
        createPlan(user, createTodo(user, list, "Before"), "2026-03-07", "2026-03-10").andExpect(status().isCreated());
        createPlan(user, createTodo(user, list, "Inside"), "2026-03-13", "2026-03-13").andExpect(status().isCreated());
        String cancelled = read(createPlan(user, createTodo(user, list, "Cancelled"), "2026-03-15", "2026-03-15")
                .andExpect(status().isCreated()), "$.response.id");
        patchAs(user.accessToken(), PLANS + "/" + cancelled + "/status", """
                {"status": "CANCELLED", "version": 0}
                """).andExpect(status().isOk());
        String detail = read(createPlan(user, createTodo(user, list, "Overlap"), "2026-03-13", "2026-03-14")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PLAN_OVERLAPS")), "$.detail");

        getInLanguage(user, PLANS + "/availability?from=2026-03-09&to=2026-03-16")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.from").value("2026-03-09"))
                .andExpect(jsonPath("$.response.to").value("2026-03-16"))
                .andExpect(jsonPath("$.response.spans[*].startsOn",
                        contains("2026-03-09", "2026-03-11", "2026-03-13", "2026-03-14")))
                .andExpect(jsonPath("$.response.spans[*].endsOn",
                        contains("2026-03-10", "2026-03-12", "2026-03-13", "2026-03-16")))
                .andExpect(jsonPath("$.response.spans[*].plannable.allowed", contains(false, true, false, true)))
                .andExpect(jsonPath("$.response.spans[0].plannable.reasons[0].errorCode").value("PLAN_OVERLAPS"))
                .andExpect(jsonPath("$.response.spans[0].plannable.reasons[0].message").value(detail))
                .andExpect(jsonPath("$.response.spans[1].plannable.reasons", empty()));
        getAs(newUser().accessToken(), PLANS + "/availability?from=2026-03-09&to=2026-03-16")
                .andExpect(jsonPath("$.response.spans.length()").value(1))
                .andExpect(jsonPath("$.response.spans[0].plannable.allowed").value(true));
    }

    @Test
    @DisplayName("""
            The availability is bounded: both ends required, to not before from and at most plan.max-availability-days \
            after it, each a 400 naming to; one statement""")
    void theAvailabilityIsBounded() throws Exception {
        TestUser user = newUser();
        String availability = PLANS + "/availability";

        getAs(user.accessToken(), availability + "?from=2026-01-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].parameter").value("to"));
        getAs(user.accessToken(), availability + "?from=2026-01-02&to=2026-01-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].parameter").value("to"));
        getAs(user.accessToken(), availability + "?from=2026-01-01&to=2026-04-03")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].parameter").value("to"));
        getAs(user.accessToken(), availability + "?from=2026-01-01&to=2026-04-02").andExpect(status().isOk());

        assertThat(statementsFor(user, availability + "?from=2026-01-01&to=2026-04-02")).isEqualTo(1);
    }
}
