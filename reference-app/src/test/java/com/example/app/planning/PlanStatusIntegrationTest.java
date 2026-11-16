package com.example.app.planning;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A plan's status workflow (PlanStatus.canBecome) and the state action that records a day (DONE's doneOn,
 * the user's own today). Every user is fresh, so the overlap rule never meets another test's plans.
 */
class PlanStatusIntegrationTest extends IntegrationTest {

    private static final String PLANS = "/api/v1/plans";
    private static final String STARTS_ON = "2026-01-10";

    @Autowired
    private Clock clock;

    private String createPlan(TestUser user, String startsOn, String endsOn) throws Exception {
        String listId = read(postAs(user.accessToken(), "/api/v1/todo-lists", """
                {"name": "Plan status %s"}
                """.formatted(UUID.randomUUID())).andExpect(status().isCreated()), "$.response.id");
        String todoId = read(postAs(user.accessToken(), "/api/v1/todos", """
                {"todoListId": "%s", "title": "Planned", "priority": "LOW"}
                """.formatted(listId)).andExpect(status().isCreated()), "$.response.id");
        return read(postAs(user.accessToken(), PLANS, """
                {"todoId": "%s", "startsOn": "%s", "endsOn": "%s"}
                """.formatted(todoId, startsOn, endsOn)).andExpect(status().isCreated()), "$.response.id");
    }

    private long versionOf(TestUser user, String id) throws Exception {
        Number version = read(getAs(user.accessToken(), PLANS + "/" + id).andExpect(status().isOk()), "$.response.version");
        return version.longValue();
    }

    /** The status change with the plan's current version; {@code doneOn} null leaves it out. */
    private ResultActions changeStatus(TestUser user, String id, String target, String doneOn) throws Exception {
        return changeStatus(user, id, target, doneOn, versionOf(user, id));
    }

    private ResultActions changeStatus(TestUser user, String id, String target, String doneOn, long version) throws Exception {
        String day = Objects.isNull(doneOn) ? "" : ", \"doneOn\": \"%s\"".formatted(doneOn);
        return patchAs(user.accessToken(), PLANS + "/" + id + "/status", """
                {"status": "%s"%s, "version": %d}
                """.formatted(target, day, version));
    }

    /** DONE needs its day; the others take none. */
    private static String dayFor(String target) {
        return target.equals("DONE") ? "2026-01-11" : null;
    }

    @ParameterizedTest(name = "{0} -> {1}: {2}")
    @CsvSource(delimiter = '|', textBlock = """
            PLANNED   | DONE      | 200
            PLANNED   | CANCELLED | 200
            PLANNED   | PLANNED   | 409
            DONE      | PLANNED   | 409
            DONE      | DONE      | 409
            DONE      | CANCELLED | 409
            CANCELLED | PLANNED   | 409
            CANCELLED | DONE      | 409
            CANCELLED | CANCELLED | 409
            """)
    @DisplayName("Each move of the lifecycle: the allowed ones 200, every other 409 PLAN_STATUS_CHANGE_INVALID naming both, the plan unchanged")
    void everyMoveOfTheLifecycle(String from, String target, int status) throws Exception {
        TestUser user = newUser();
        String id = createPlan(user, STARTS_ON, "2026-01-12");
        if (!from.equals("PLANNED")) {
            changeStatus(user, id, from, dayFor(from)).andExpect(status().isOk());
        }
        long version = versionOf(user, id);

        ResultActions answer = changeStatus(user, id, target, dayFor(target), version).andExpect(status().is(status));

        if (status == 200) {
            answer.andExpect(jsonPath("$.response.status").value(target))
                    .andExpect(jsonPath("$.response.version").value(version + 1));
        } else {
            answer.andExpect(jsonPath("$.errorCode").value("PLAN_STATUS_CHANGE_INVALID"))
                    .andExpect(jsonPath("$.detail").value("A %s plan can't become %s.".formatted(from, target)));
            getAs(user.accessToken(), PLANS + "/" + id)
                    .andExpect(jsonPath("$.response.status").value(from))
                    .andExpect(jsonPath("$.response.version").value(version));
        }
    }

    @Test
    @DisplayName("DONE records its day, shown on the detail and the list's rows; any other status shows none")
    void doneRecordsItsDay() throws Exception {
        TestUser user = newUser();
        String id = createPlan(user, STARTS_ON, "2026-01-12");

        changeStatus(user, id, "DONE", "2026-01-11")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.doneOn").value("2026-01-11"));
        getAs(user.accessToken(), PLANS)
                .andExpect(jsonPath("$.response[0].status").value("DONE"))
                .andExpect(jsonPath("$.response[0].doneOn").value("2026-01-11"));
        String cancelled = createPlan(user, "2026-02-10", "2026-02-10");
        changeStatus(user, cancelled, "CANCELLED", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.doneOn").doesNotExist());
    }

    @Test
    @DisplayName("""
            doneOn up to the user's today (UTC today plus the configured day) is accepted; a day later is 400 at doneOn; \
            the configured earliest date and a year PostgreSQL can't hold are 400 there too""")
    void doneOnIsAtMostToday() throws Exception {
        TestUser user = newUser();
        LocalDate today = LocalDate.now(clock);
        String late = createPlan(user, today.minusDays(3).toString(), today.minusDays(3).toString());

        changeStatus(user, late, "DONE", today.plusDays(2).toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].pointer", contains("#/doneOn")))
                .andExpect(jsonPath("$.errors[*].detail", contains("must not be in the future")));
        changeStatus(user, late, "DONE", "1899-12-31")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].pointer", contains("#/doneOn")))
                .andExpect(jsonPath("$.errors[*].detail", contains("must be on or after 1900-01-01")));
        changeStatus(user, late, "DONE", "-300000-01-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].pointer", contains("#/doneOn")));
        changeStatus(user, late, "DONE", today.plusDays(1).toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.doneOn").value(today.plusDays(1).toString()));
    }

    @Test
    @DisplayName("A done day before the plan's first is 400 PLAN_DONE_ON_BEFORE_STARTS_ON naming both days; the first day itself is accepted")
    void doneOnIsNeverBeforeTheFirstDay() throws Exception {
        TestUser user = newUser();
        String id = createPlan(user, STARTS_ON, "2026-01-12");

        changeStatus(user, id, "DONE", "2026-01-09")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("PLAN_DONE_ON_BEFORE_STARTS_ON"))
                .andExpect(jsonPath("$.detail").value("A plan can't be done on 2026-01-09, before its first day, 2026-01-10."));
        getAs(user.accessToken(), PLANS + "/" + id).andExpect(jsonPath("$.response.status").value("PLANNED"));
        changeStatus(user, id, "DONE", STARTS_ON).andExpect(status().isOk());
    }

    @Test
    @DisplayName("doneOn comes with DONE and only with it: missing for DONE, or sent with CANCELLED, is 400 at doneOn; a missing status or version 400")
    void doneOnComesWithDoneOnly() throws Exception {
        TestUser user = newUser();
        String id = createPlan(user, STARTS_ON, "2026-01-12");

        for (ResultActions refused : List.of(changeStatus(user, id, "DONE", null), changeStatus(user, id, "CANCELLED", "2026-01-11"))) {
            refused.andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[*].pointer", contains("#/doneOn")))
                    .andExpect(jsonPath("$.errors[*].detail", contains("doneOn comes with status DONE, and only with it")));
        }
        patchAs(user.accessToken(), PLANS + "/" + id + "/status", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].pointer", containsInAnyOrder("#/status", "#/version")));
        patchAs(user.accessToken(), PLANS + "/" + id + "/status", """
                {"status": "FINISHED", "version": 0}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"));
        getAs(user.accessToken(), PLANS + "/" + id).andExpect(jsonPath("$.response.version").value(0));
    }

    @Test
    @DisplayName("A cancelled plan frees its days for another plan; a done one keeps them (409 PLAN_OVERLAPS)")
    void onlyALivePlanHoldsItsDays() throws Exception {
        TestUser user = newUser();
        String cancelled = createPlan(user, "2026-03-10", "2026-03-12");
        String done = createPlan(user, "2026-03-20", "2026-03-22");

        changeStatus(user, cancelled, "CANCELLED", null).andExpect(status().isOk());
        changeStatus(user, done, "DONE", "2026-03-21").andExpect(status().isOk());

        createPlan(user, "2026-03-11", "2026-03-11");
        String listId = read(postAs(user.accessToken(), "/api/v1/todo-lists", """
                {"name": "Overlap %s"}
                """.formatted(UUID.randomUUID())).andExpect(status().isCreated()), "$.response.id");
        String todoId = read(postAs(user.accessToken(), "/api/v1/todos", """
                {"todoListId": "%s", "title": "Overlapping", "priority": "LOW"}
                """.formatted(listId)).andExpect(status().isCreated()), "$.response.id");
        postAs(user.accessToken(), PLANS, """
                {"todoId": "%s", "startsOn": "2026-03-21", "endsOn": "2026-03-21"}
                """.formatted(todoId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PLAN_OVERLAPS"));
    }

    @Test
    @DisplayName("A stale version is 409 OPTIMISTIC_LOCK_CONFLICT; another user's plan or an unknown one 404 PLAN_NOT_FOUND")
    void staleVersionsAndStrangers() throws Exception {
        TestUser user = newUser();
        String id = createPlan(user, STARTS_ON, "2026-01-12");
        changeStatus(user, id, "CANCELLED", null, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK_CONFLICT"));

        changeStatus(newUser(), id, "CANCELLED", null, 0)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PLAN_NOT_FOUND"));
        changeStatus(user, UUID.randomUUID().toString(), "CANCELLED", null, 0)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PLAN_NOT_FOUND"));
        getAs(user.accessToken(), PLANS + "/" + id).andExpect(jsonPath("$.response.status").value("PLANNED"));
    }

    @Test
    @DisplayName("Concurrent status changes with one version apply once: one 200, the rest 409, the version advanced once")
    void concurrentStatusChangesApplyOnce() throws Exception {
        TestUser user = newUser();
        String id = createPlan(user, STARTS_ON, "2026-01-12");

        List<Answer> answers = race(() -> changeStatus(user, id, "CANCELLED", null, 0));

        assertThat(answers).extracting(Answer::status).containsOnlyOnce(200).isSubsetOf(200, 409);
        assertRefusalsAre(answers, 409, "OPTIMISTIC_LOCK_CONFLICT");
        assertThat(jdbc.queryForObject("select version from plans where id = ?::uuid", Long.class, id)).isOne();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            DONE      | PLAN_DONE
            CANCELLED | PLAN_CANCELLED
            """)
    @DisplayName("""
            A final plan is frozen: a PUT is 409 with its status's code, before the overlap it would also trip, the plan \
            unchanged (version included); the same PUT on a PLANNED plan works; a delete still does""")
    void aFinalPlanIsFrozen(String status, String errorCode) throws Exception {
        TestUser user = newUser();
        String id = createPlan(user, STARTS_ON, "2026-01-12");
        createPlan(user, "2026-04-01", "2026-04-02");
        String live = createPlan(user, "2026-05-01", "2026-05-01");
        changeStatus(user, id, status, dayFor(status)).andExpect(status().isOk());
        long version = versionOf(user, id);

        putAs(user.accessToken(), PLANS + "/" + id, """
                {"startsOn": "2026-04-02", "endsOn": "2026-04-03", "version": %d}
                """.formatted(version))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(errorCode));
        getAs(user.accessToken(), PLANS + "/" + id)
                .andExpect(jsonPath("$.response.startsOn").value(STARTS_ON))
                .andExpect(jsonPath("$.response.version").value(version));
        putAs(user.accessToken(), PLANS + "/" + live, """
                {"startsOn": "2026-05-02", "endsOn": "2026-05-03", "version": 0}
                """).andExpect(status().isOk());
        deleteAs(user.accessToken(), PLANS + "/" + id).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("A DONE plan whose todo is done too is 409 PLAN_DONE on a PUT, not its todo's PLAN_TODO_DONE: the plan's own status comes first")
    void aFinalPlanIsRefusedBeforeItsTodosState() throws Exception {
        TestUser user = newUser();
        String id = createPlan(user, STARTS_ON, "2026-01-12");
        changeStatus(user, id, "DONE", dayFor("DONE")).andExpect(status().isOk());
        String todoId = read(getAs(user.accessToken(), PLANS + "/" + id), "$.response.todo.id");
        patchAs(user.accessToken(), "/api/v1/todos/" + todoId + "/status", """
                {"status": "DONE", "version": 0}
                """).andExpect(status().isOk());

        putAs(user.accessToken(), PLANS + "/" + id, """
                {"startsOn": "2026-04-02", "endsOn": "2026-04-03", "version": %d}
                """.formatted(versionOf(user, id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PLAN_DONE"));
    }

    @Test
    @DisplayName("The API docs name the status change's codes")
    void apiDocsNameTheStatusCodes() throws Exception {
        getAs(null, "/v3/api-docs")
                .andExpect(jsonPath("$.paths['/api/v1/plans/{id}/status'].patch.responses['409'].description",
                        containsString("PLAN_STATUS_CHANGE_INVALID")))
                .andExpect(jsonPath("$.paths['/api/v1/plans/{id}/status'].patch.responses['400'].description",
                        containsString("PLAN_DONE_ON_BEFORE_STARTS_ON")))
                .andExpect(jsonPath("$.paths['/api/v1/plans/{id}'].put.responses['409'].description", containsString("PLAN_DONE")))
                .andExpect(jsonPath("$.paths['/api/v1/plans/{id}'].put.responses['409'].description", containsString("PLAN_CANCELLED")));
    }
}
