package com.example.app.planning;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.example.app.support.CapturedLog;
import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlanAllowedActionsIntegrationTest extends IntegrationTest {

    private static final String PLANS = "/api/v1/plans";
    private static final String LANGUAGE = "en-GB,en;q=0.8";
    private static final String AUTHORIZATION_LOGGER = "com.example.app.authorization";

    private record Planned(TestUser user, String planId, String todoId) {
    }

    private Planned planned() throws Exception {
        TestUser user = newUser();
        String listId = read(postAs(user.accessToken(), "/api/v1/todo-lists", """
                {"name": "Allowed actions %s"}
                """.formatted(UUID.randomUUID())).andExpect(status().isCreated()), "$.response.id");
        String todoId = read(postAs(user.accessToken(), "/api/v1/todos", """
                {"todoListId": "%s", "title": "Planned", "priority": "LOW"}
                """.formatted(listId)).andExpect(status().isCreated()), "$.response.id");
        String planId = read(postAs(user.accessToken(), PLANS, """
                {"todoId": "%s", "startsOn": "2026-03-10", "endsOn": "2026-03-11"}
                """.formatted(todoId)).andExpect(status().isCreated()), "$.response.id");
        return new Planned(user, planId, todoId);
    }

    private Planned cancelledWithItsTodoDone() throws Exception {
        Planned planned = planned();
        patchAs(planned.user().accessToken(), PLANS + "/" + planned.planId() + "/status", """
                {"status": "CANCELLED", "version": 0}
                """).andExpect(status().isOk());
        patchAs(planned.user().accessToken(), "/api/v1/todos/" + planned.todoId() + "/status", """
                {"status": "DONE", "version": 0}
                """).andExpect(status().isOk());
        return planned;
    }

    private ResultActions getInLanguage(TestUser user, String path) throws Exception {
        return mvc.perform(get(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                .header(HttpHeaders.ACCEPT_LANGUAGE, LANGUAGE));
    }

    private ResultActions rescheduleInLanguage(Planned planned) throws Exception {
        return mvc.perform(put(PLANS + "/" + planned.planId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + planned.user().accessToken())
                .header(HttpHeaders.ACCEPT_LANGUAGE, LANGUAGE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"startsOn": "2026-03-12", "endsOn": "2026-03-13", "version": 1}
                        """));
    }

    @Test
    @DisplayName("A PLANNED plan of an open todo allows every action, with no reasons")
    void aPlannedPlanAllowsEverything() throws Exception {
        Planned planned = planned();

        getAs(planned.user().accessToken(), PLANS + "/" + planned.planId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.allowedActions.reschedule.allowed").value(true))
                .andExpect(jsonPath("$.response.allowedActions.reschedule.reasons", empty()))
                .andExpect(jsonPath("$.response.allowedActions.markDone.allowed").value(true))
                .andExpect(jsonPath("$.response.allowedActions.cancel.allowed").value(true));
    }

    @Test
    @DisplayName("""
            A cancelled plan of a done todo lists both reasons against new days, on the detail and its list row, each \
            the message the PUT itself answers with the first; the status changes name the move refused""")
    void everyReasonAtOnce() throws Exception {
        Planned planned = cancelledWithItsTodoDone();

        String detail = read(rescheduleInLanguage(planned)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PLAN_CANCELLED")), "$.detail");

        getInLanguage(planned.user(), PLANS + "/" + planned.planId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.allowedActions.reschedule.allowed").value(false))
                .andExpect(jsonPath("$.response.allowedActions.reschedule.reasons[*].errorCode",
                        contains("PLAN_CANCELLED", "PLAN_TODO_DONE")))
                .andExpect(jsonPath("$.response.allowedActions.reschedule.reasons[0].message").value(detail))
                .andExpect(jsonPath("$.response.allowedActions.reschedule.reasons[1].message")
                        .value("Todo %s is done: plan days only for an open todo.".formatted(planned.todoId())))
                .andExpect(jsonPath("$.response.allowedActions.markDone.allowed").value(false))
                .andExpect(jsonPath("$.response.allowedActions.markDone.reasons[0].errorCode").value("PLAN_STATUS_CHANGE_INVALID"))
                .andExpect(jsonPath("$.response.allowedActions.markDone.reasons[0].message").value("A CANCELLED plan can't become DONE."))
                .andExpect(jsonPath("$.response.allowedActions.cancel.reasons[0].message")
                        .value("A CANCELLED plan can't become CANCELLED."));
        getInLanguage(planned.user(), PLANS + "?todoId=" + planned.todoId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[0].id").value(planned.planId()))
                .andExpect(jsonPath("$.response[0].allowedActions.reschedule.reasons[*].errorCode",
                        contains("PLAN_CANCELLED", "PLAN_TODO_DONE")))
                .andExpect(jsonPath("$.response[0].allowedActions.reschedule.reasons[0].message").value(detail));
    }

    @Test
    @DisplayName("With the authorization logger at DEBUG, a write its policy refuses logs its codes, never the message")
    void aRefusedWriteIsLoggedAtDebug() throws Exception {
        Planned planned = cancelledWithItsTodoDone();
        Logger authorization = (Logger) LoggerFactory.getLogger(AUTHORIZATION_LOGGER);
        Level configured = authorization.getLevel();
        CapturedLog log = new CapturedLog();
        authorization.setLevel(Level.DEBUG);
        log.open();
        try {
            rescheduleInLanguage(planned).andExpect(status().isConflict());
        } finally {
            log.close();
            authorization.setLevel(configured);
        }

        assertThat(log.text())
                .contains("Write refused: [PLAN_CANCELLED, PLAN_TODO_DONE]")
                .doesNotContain("is cancelled");
    }
}
