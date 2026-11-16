package com.example.app.planning;

import com.example.app.common.pagination.Cursor;
import com.example.app.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plans: a parent in another module (the todo, through {@code todo.api.Todos}) and no overlapping
 * ranges (the exclusion constraint {@code ex_plans_owner_id_dates}). Every user is fresh, so the
 * exclusion, which is per owner, never meets another test's plans.
 */
class PlanIntegrationTest extends IntegrationTest {

    private static final String PLANS = "/api/v1/plans";
    /** {@code plan.max-days} in config/limits.yml. */
    private static final int MAX_DAYS = 31;
    private static final String TODOS = "/api/v1/todos";

    @Autowired
    private PlatformTransactionManager transactionManager;

    private String createTodo(TestUser user, String title) throws Exception {
        String listId = read(postAs(user.accessToken(), "/api/v1/todo-lists", """
                {"name": "Plans %s"}
                """.formatted(UUID.randomUUID())).andExpect(status().isCreated()), "$.response.id");
        return read(postAs(user.accessToken(), TODOS, """
                {"todoListId": "%s", "title": "%s", "priority": "LOW"}
                """.formatted(listId, title)).andExpect(status().isCreated()), "$.response.id");
    }

    private static String planPayload(String todoId, String startsOn, String endsOn) {
        return """
                {"todoId": "%s", "startsOn": "%s", "endsOn": "%s"}
                """.formatted(todoId, startsOn, endsOn);
    }

    private ResultActions create(TestUser user, String todoId, String startsOn, String endsOn) throws Exception {
        return postAs(user.accessToken(), PLANS, planPayload(todoId, startsOn, endsOn));
    }

    private String createPlan(TestUser user, String todoId, String startsOn, String endsOn) throws Exception {
        return read(create(user, todoId, startsOn, endsOn).andExpect(status().isCreated()), "$.response.id");
    }

    private int plansOf(TestUser user) {
        return jdbc.queryForObject("select count(*) from plans where owner_id = ?", Integer.class, user.id());
    }

    @Test
    @DisplayName("Create answers 201 with the days, the todo's id and title, version 0 and the audit fields")
    void createReturns201WithTheSavedPlan() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user, "Write the report");

        create(user, todoId, "2026-11-20", "2026-11-22")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.id").isNotEmpty())
                .andExpect(jsonPath("$.response.todo.id").value(todoId))
                .andExpect(jsonPath("$.response.todo.title").value("Write the report"))
                .andExpect(jsonPath("$.response.ownerId").value(user.id().toString()))
                .andExpect(jsonPath("$.response.startsOn").value("2026-11-20"))
                .andExpect(jsonPath("$.response.endsOn").value("2026-11-22"))
                .andExpect(jsonPath("$.response.version").value(0))
                .andExpect(jsonPath("$.response.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.response.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.response.createdBy").value(user.id().toString()))
                .andExpect(jsonPath("$.response.updatedBy").value(user.id().toString()));
    }

    @Test
    @DisplayName("Get returns the plan with its todo")
    void getReturnsThePlan() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user, "Read");
        String id = createPlan(user, todoId, "2026-12-01", "2026-12-01");

        getAs(user.accessToken(), PLANS + "/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.todo.id").value(todoId))
                .andExpect(jsonPath("$.response.todo.title").value("Read"))
                .andExpect(jsonPath("$.response.startsOn").value("2026-12-01"));
    }

    @Test
    @DisplayName("Get of an unknown id is 404 PLAN_NOT_FOUND")
    void getUnknownIdIs404() throws Exception {
        getAs(newUser().accessToken(), PLANS + "/" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PLAN_NOT_FOUND"));
    }

    @Test
    @DisplayName("A non-UUID id in the path is 400 REQUEST_PARAMETER_INVALID")
    void malformedIdIs400() throws Exception {
        getAs(newUser().accessToken(), PLANS + "/not-a-uuid")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"));
    }

    @Test
    @DisplayName("Delete answers 204, the plan is gone, and its days are free again")
    void deleteFreesTheDays() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user, "Paint");
        String id = createPlan(user, todoId, "2026-11-20", "2026-11-22");

        deleteAs(user.accessToken(), PLANS + "/" + id).andExpect(status().isNoContent());

        getAs(user.accessToken(), PLANS + "/" + id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PLAN_NOT_FOUND"));
        create(user, todoId, "2026-11-20", "2026-11-22").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Delete of an unknown id is 404 PLAN_NOT_FOUND")
    void deleteUnknownIdIs404() throws Exception {
        deleteAs(newUser().accessToken(), PLANS + "/" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PLAN_NOT_FOUND"));
    }

    @Test
    @DisplayName("Create without a todo or dates is 400 naming each field")
    void createWithoutRequiredFieldsIs400() throws Exception {
        postAs(newUser().accessToken(), PLANS, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].pointer", hasItem("#/todoId")))
                .andExpect(jsonPath("$.errors[*].pointer", hasItem("#/startsOn")))
                .andExpect(jsonPath("$.errors[*].pointer", hasItem("#/endsOn")));
    }

    @Test
    @DisplayName("endsOn before startsOn is 400 reported on endsOn; the same day twice is a one-day plan")
    void rangeMustBeOrdered() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user, "Ordered");

        create(user, todoId, "2026-11-22", "2026-11-21")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].pointer", contains("#/endsOn")))
                .andExpect(jsonPath("$.errors[*].detail", contains("endsOn must not be before startsOn")));
        create(user, todoId, "2026-11-22", "2026-11-22").andExpect(status().isCreated());
        assertThat(plansOf(user)).isOne();
    }

    @Test
    @DisplayName("Each day up to the configured latest date is accepted on both ends; a day later is 400 with the limit, naming each field past it")
    void datesFollowTheConfiguredLatestDate() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user, "Far ahead");

        create(user, todoId, "2999-12-31", "2999-12-31").andExpect(status().isCreated());
        create(user, todoId, "2999-12-29", "3000-01-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].pointer", contains("#/endsOn")))
                .andExpect(jsonPath("$.errors[*].detail", contains("must be on or before 2999-12-31")));
        // startsOn can't be late alone: an endsOn before it would be refused as an inverted range.
        create(user, todoId, "3000-01-01", "3000-01-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].pointer", containsInAnyOrder("#/startsOn", "#/endsOn")))
                .andExpect(jsonPath("$.errors[*].detail", containsInAnyOrder("must be on or before 2999-12-31", "must be on or before 2999-12-31")));
        assertThat(plansOf(user)).isOne();
    }

    @Test
    @DisplayName("Each day from the configured earliest date is accepted on both ends; a day earlier is 400 with the limit, naming each field before it, never a database error")
    void datesFollowTheConfiguredEarliestDate() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user, "Long ago");

        create(user, todoId, "1900-01-01", "1900-01-01").andExpect(status().isCreated());
        create(user, todoId, "1899-12-31", "1900-01-02")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].pointer", contains("#/startsOn")))
                .andExpect(jsonPath("$.errors[*].detail", contains("must be on or after 1900-01-01")));
        // endsOn can't be early alone: a startsOn after it would be refused as an inverted range.
        create(user, todoId, "1899-12-31", "1899-12-31")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].pointer", containsInAnyOrder("#/startsOn", "#/endsOn")))
                .andExpect(jsonPath("$.errors[*].detail", containsInAnyOrder("must be on or after 1900-01-01", "must be on or after 1900-01-01")));
        // A year PostgreSQL's date can't hold, in each field: refused here, not at the insert.
        create(user, todoId, "-300000-01-01", "2026-01-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].pointer", containsInAnyOrder("#/startsOn", "#/endsOn")))
                .andExpect(jsonPath("$.errors[*].detail", containsInAnyOrder("must be on or after 1900-01-01", "must be at most " + MAX_DAYS)));
        create(user, todoId, "-300000-01-01", "-300000-01-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].pointer", containsInAnyOrder("#/startsOn", "#/endsOn")))
                .andExpect(jsonPath("$.errors[*].detail", containsInAnyOrder("must be on or after 1900-01-01", "must be on or after 1900-01-01")));
        assertThat(plansOf(user)).isOne();
    }

    @Test
    @DisplayName("A plan of the configured maximum length is accepted; one day longer is 400 reported on endsOn, nothing stored")
    void lengthIsBounded() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user, "Long");
        LocalDate first = LocalDate.parse("2026-05-01");

        create(user, todoId, first.toString(), first.plusDays(MAX_DAYS).toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].pointer", contains("#/endsOn")))
                .andExpect(jsonPath("$.errors[*].detail", contains("must be at most " + MAX_DAYS)));
        assertThat(plansOf(user)).isZero();
        create(user, todoId, first.toString(), first.plusDays(MAX_DAYS - 1).toString()).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("A malformed todoId or date in the body is 400 REQUEST_UNREADABLE without internals")
    void unconvertibleBodyValuesAre400() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user, "Strict");

        for (String body : List.of(planPayload("not-a-uuid", "2026-11-20", "2026-11-22"), planPayload(todoId, "20-11-2026", "2026-11-22"))) {
            String answer = postAs(user.accessToken(), PLANS, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"))
                    .andReturn().getResponse().getContentAsString();
            assertNoInternals(answer);
        }
    }

    @Test
    @DisplayName("An unknown todo, or another user's, is 404 TODO_NOT_FOUND, and no plan is stored")
    void todoMustBeTheCallersOwn() throws Exception {
        TestUser user = newUser();
        String othersTodo = createTodo(newUser(), "Not yours");

        for (String todoId : List.of(UUID.randomUUID().toString(), othersTodo)) {
            create(user, todoId, "2026-11-20", "2026-11-22")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("TODO_NOT_FOUND"));
        }
        assertThat(plansOf(user)).isZero();
    }

    @Test
    @DisplayName("A plan sharing a day with another of yours, even on another todo and only its first or last day, is 409 PLAN_OVERLAPS")
    void overlappingPlansAre409() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user, "Busy");
        String otherTodo = createTodo(user, "Also busy");
        createPlan(user, todoId, "2026-11-20", "2026-11-22");

        for (List<String> days : List.of(List.of("2026-11-22", "2026-11-24"), List.of("2026-11-18", "2026-11-20"),
                List.of("2026-11-21", "2026-11-21"), List.of("2026-11-01", "2026-11-30"))) {
            String body = create(user, otherTodo, days.get(0), days.get(1))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("PLAN_OVERLAPS"))
                    .andReturn().getResponse().getContentAsString();
            assertNoInternals(body);
        }
        assertThat(plansOf(user)).isOne();
    }

    @Test
    @DisplayName("The days right before and right after a plan are free, and another user's plans never collide with yours")
    void adjacentDaysAndOtherUsersAreFree() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user, "Edges");
        createPlan(user, todoId, "2026-11-20", "2026-11-22");

        create(user, todoId, "2026-11-23", "2026-11-25").andExpect(status().isCreated());
        create(user, todoId, "2026-11-17", "2026-11-19").andExpect(status().isCreated());
        TestUser other = newUser();
        create(other, createTodo(other, "Same days"), "2026-11-20", "2026-11-22").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Concurrent creates of overlapping plans for two todos store exactly one; the others are 409 PLAN_OVERLAPS or 503 DATABASE_CONTENTION, never 500")
    void concurrentOverlappingCreatesStoreOne() throws Exception {
        TestUser user = newUser();
        List<String> todoIds = List.of(createTodo(user, "Contested days"), createTodo(user, "Same days"));
        AtomicInteger racer = new AtomicInteger();

        List<Answer> answers = race(() -> create(user, todoIds.get(racer.getAndIncrement() % 2), "2026-11-20", "2026-11-22"));

        // The creates of one todo queue on its lock; the exclusion is per owner, so creates for two todos
        // still meet at the same instant, each waiting in the exclusion check for the other's row, and
        // PostgreSQL ends that as a deadlock: the victim's 503 is a safe retry, which then answers 409.
        assertThat(answers).extracting(Answer::status).containsOnlyOnce(201).isSubsetOf(201, 409, 503);
        assertRefusalsAre(answers, 409, "PLAN_OVERLAPS");
        assertRefusalsAre(answers, 503, "DATABASE_CONTENTION");
        assertThat(plansOf(user)).isOne();
    }

    @Test
    @DisplayName("Another user's plan is 404 on get and delete, and stays")
    void anotherUsersPlanIs404() throws Exception {
        TestUser owner = newUser();
        String id = createPlan(owner, createTodo(owner, "Mine"), "2026-11-20", "2026-11-22");
        TestUser stranger = newUser();

        getAs(stranger.accessToken(), PLANS + "/" + id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PLAN_NOT_FOUND"));
        deleteAs(stranger.accessToken(), PLANS + "/" + id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PLAN_NOT_FOUND"));
        getAs(owner.accessToken(), PLANS + "/" + id).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Every plan endpoint needs a token: 401 AUTHENTICATION_REQUIRED")
    void everyEndpointNeedsAToken() throws Exception {
        String id = UUID.randomUUID().toString();

        getAs(null, PLANS)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        getAs(null, PLANS + "/" + id)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        postAs(null, PLANS, planPayload(id, "2026-11-20", "2026-11-22"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        deleteAs(null, PLANS + "/" + id)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("A plan never changes: PUT and PATCH are 405 REQUEST_METHOD_NOT_ALLOWED and the plan is unchanged")
    void planIsImmutable() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user, "Fixed");
        String id = createPlan(user, todoId, "2026-11-20", "2026-11-22");
        String moved = planPayload(todoId, "2026-12-20", "2026-12-22");

        putAs(user.accessToken(), PLANS + "/" + id, moved)
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_METHOD_NOT_ALLOWED"));
        patchAs(user.accessToken(), PLANS + "/" + id, moved)
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_METHOD_NOT_ALLOWED"));
        getAs(user.accessToken(), PLANS + "/" + id).andExpect(jsonPath("$.response.startsOn").value("2026-11-20"));
    }

    @Test
    @DisplayName("Concurrent deletes of one plan: one 204; each other is 404 PLAN_NOT_FOUND or the documented 409 OPTIMISTIC_LOCK_CONFLICT")
    void concurrentDeletesRemoveItOnce() throws Exception {
        TestUser user = newUser();
        String id = createPlan(user, createTodo(user, "Contested"), "2026-11-20", "2026-11-22");

        List<Answer> answers = race(() -> deleteAs(user.accessToken(), PLANS + "/" + id));

        // A racer that read the plan before the winner's delete committed finds no row to delete (409);
        // one that read it after finds no plan (404). Which of the two each loser meets is timing.
        assertThat(answers).extracting(Answer::status).containsOnlyOnce(204).isSubsetOf(204, 404, 409);
        assertRefusalsAre(answers, 404, "PLAN_NOT_FOUND");
        assertRefusalsAre(answers, 409, "OPTIMISTIC_LOCK_CONFLICT");
        assertThat(plansOf(user)).isZero();
    }

    @Test
    @DisplayName("An unknown field in the body is 400 REQUEST_UNREADABLE")
    void unknownJsonFieldIs400() throws Exception {
        TestUser user = newUser();

        postAs(user.accessToken(), PLANS, """
                {"todoId": "%s", "startsOn": "2026-11-20", "endsOn": "2026-11-22", "colour": "red"}
                """.formatted(createTodo(user, "Strict")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"));
    }

    @Test
    @DisplayName("Deleting a todo that has plans is 409 TODO_IN_USE without internals; once they are deleted it goes")
    void todoWithPlansCannotBeDeleted() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user, "Planned");
        String id = createPlan(user, todoId, "2026-11-20", "2026-11-22");

        String body = deleteAs(user.accessToken(), TODOS + "/" + todoId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TODO_IN_USE"))
                .andReturn().getResponse().getContentAsString();
        assertNoInternals(body);
        getAs(user.accessToken(), TODOS + "/" + todoId).andExpect(status().isOk());

        deleteAs(user.accessToken(), PLANS + "/" + id).andExpect(status().isNoContent());
        deleteAs(user.accessToken(), TODOS + "/" + todoId).andExpect(status().isNoContent());
    }

    /**
     * Row 23 with a locked parent: each create locks the todo (it checks the todo is open), so the delete
     * waits for the held creates instead of landing between a create's read and its insert. It then finds
     * their plans: every create 201, the delete 409 TODO_IN_USE, and TODO_GONE can't happen.
     */
    @Test
    @DisplayName("Plans created while their todo is deleted: the delete waits for them, every create is 201 and the delete 409 TODO_IN_USE")
    void createsRacingTheTodoDeleteAreKeptAndTheDeleteIsRefused() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user, "Contested");
        List<Callable<ResultActions>> creates = new ArrayList<>();
        for (int day = 0; day < HELD_CREATES; day++) {
            // Each on its own day, so only the todo's delete could refuse them.
            String date = LocalDate.parse("2026-11-01").plusDays(day).toString();
            creates.add(() -> create(user, todoId, date, date));
        }

        HeldWrites race = writesHeldWhileTheParentWaits("plans", creates, () -> deleteAs(user.accessToken(), TODOS + "/" + todoId));

        assertThat(race.writes()).extracting(Answer::status).containsOnly(201);
        assertThat(race.parentWrite().status()).isEqualTo(409);
        assertRefusalsAre(List.of(race.parentWrite()), 409, "TODO_IN_USE");
        assertThat(plansOf(user)).isEqualTo(HELD_CREATES);
        getAs(user.accessToken(), TODOS + "/" + todoId).andExpect(status().isOk());
    }

    /**
     * Row 23b: a create checks the todo is open under the todo's lock. Held at its insert, the plan keeps
     * the todo's completion waiting, which then applies after the plan, made while the todo was open.
     * Without the lock the completion commits first and the plan is stored for a done todo (the helper's
     * "waits" assertion goes red).
     */
    @Test
    @DisplayName("A todo completed while a plan for it is being created waits for the plan: the plan is 201, the completion 200 after it")
    void completionRacingACreateWaitsForIt() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user, "Nearly done");
        List<Callable<ResultActions>> creates = List.of(() -> create(user, todoId, "2026-11-20", "2026-11-22"));

        HeldWrites race = writesHeldWhileTheParentWaits("plans", creates, () -> complete(user, todoId, 0));

        assertThat(race.writes()).extracting(Answer::status).containsExactly(201);
        assertThat(race.parentWrite().status()).isEqualTo(200);
        assertThat(plansOf(user)).isOne();
        create(user, todoId, "2026-12-20", "2026-12-22")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PLAN_TODO_DONE"));
    }

    @Test
    @DisplayName("A plan for a done todo is 409 PLAN_TODO_DONE and stores nothing; reopened, the todo takes plans again")
    void planForADoneTodoIs409() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user, "Done already");
        complete(user, todoId, 0).andExpect(status().isOk());

        String body = create(user, todoId, "2026-11-20", "2026-11-22")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PLAN_TODO_DONE"))
                .andReturn().getResponse().getContentAsString();
        assertNoInternals(body);
        assertThat(plansOf(user)).isZero();

        patchAs(user.accessToken(), TODOS + "/" + todoId + "/status", """
                {"status": "OPEN", "version": 1}
                """).andExpect(status().isOk());
        create(user, todoId, "2026-11-20", "2026-11-22").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("A batch whose plan and todo are deleted between its two statements leaves that plan out, never 500")
    void listLeavesOutAPlanWhoseTodoVanishedBetweenTheReads() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user, "Vanishing");
        String vanishing = createPlan(user, todoId, "2026-11-20", "2026-11-20");
        String staying = createPlan(user, createTodo(user, "Staying"), "2026-11-21", "2026-11-21");

        Answer batch = whileThePlanAndItsTodoGoBetweenTheReads(vanishing, todoId, () -> getAs(user.accessToken(), PLANS));

        assertThat(batch.status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(batch.body(), "$.response[*].id")).containsExactly(staying);
    }

    @Test
    @DisplayName("A plan deleted with its todo between the plan's read and the todo's is 404 PLAN_NOT_FOUND, never the todo's code")
    void getOfAPlanWhoseTodoVanishedBetweenTheReadsIs404() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user, "Vanishing");
        String id = createPlan(user, todoId, "2026-11-20", "2026-11-20");

        Answer answer = whileThePlanAndItsTodoGoBetweenTheReads(id, todoId, () -> getAs(user.accessToken(), PLANS + "/" + id));

        assertThat(answer.status()).isEqualTo(404);
        assertRefusalsAre(List.of(answer), 404, "PLAN_NOT_FOUND");
    }

    private ResultActions complete(TestUser user, String todoId, long version) throws Exception {
        return patchAs(user.accessToken(), TODOS + "/" + todoId + "/status", """
                {"status": "DONE", "version": %d}
                """.formatted(version));
    }

    /**
     * Runs {@code read} (the plan, then its todo through the todo module's facade: two statements) while
     * the plan and its todo are deleted between them. A transaction deletes both and holds the todos table
     * ACCESS EXCLUSIVE: the read's first statement (plans only) still sees the plan, its second (the todos)
     * waits for that lock. Once it waits there, the deletion commits.
     */
    private Answer whileThePlanAndItsTodoGoBetweenTheReads(String planId, String todoId, Callable<ResultActions> read)
            throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try (ExecutorService executor = newExecutor()) {
            Future<?> deletion = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.execute("lock table todos in access exclusive mode");
                jdbc.update("delete from plans where id = ?", UUID.fromString(planId));
                jdbc.update("delete from todos where id = ?", UUID.fromString(todoId));
                deleted.countDown();
                awaitQuietly(commit);
            }));
            awaitLatch(deleted);
            Future<Answer> reading;
            try {
                Callable<Answer> once = () -> answerOf(read.call());
                reading = executor.submit(once);
                awaitLockWaiters(1, reading);
                assertThat(reading.isDone()).as("the read waits at its todo lookup").isFalse();
            } finally {
                commit.countDown();
            }
            within(deletion);
            return within(reading);
        }
    }

    /**
     * Plans on consecutive days, as {@code user}, across two todos; returns their ids in calendar order.
     * The last day is created first, so calendar order and creation (id) order differ: a STARTS_ON walk
     * that ignored its sort would fail (TEST-10). Newest first is then the other days backwards, then the
     * last day ({@link #newestFirst}).
     */
    private List<String> planDays(TestUser user, LocalDate firstDay, int days) throws Exception {
        List<String> todoIds = List.of(createTodo(user, "Even days"), createTodo(user, "Odd days"));
        String[] ids = new String[days];
        int last = days - 1;
        String lastDate = firstDay.plusDays(last).toString();
        ids[last] = createPlan(user, todoIds.get(last % 2), lastDate, lastDate);
        for (int day = 0; day < last; day++) {
            String date = firstDay.plusDays(day).toString();
            ids[day] = createPlan(user, todoIds.get(day % 2), date, date);
        }
        return List.of(ids);
    }

    /** {@link #planDays}' ids in creation order, newest first: the days before the last backwards, then the last. */
    private static List<String> newestFirst(List<String> calendarOrder) {
        List<String> newest = new ArrayList<>(calendarOrder.subList(0, calendarOrder.size() - 1));
        Collections.reverse(newest);
        newest.add(calendarOrder.getLast());
        return newest;
    }

    /**
     * Follows {@code pageInfo.nextCursor} from {@code firstBatch} (a path with its query string) to the
     * last batch, which must say {@code hasMore} false with no cursor; returns each batch's ids.
     */
    private List<List<String>> walk(TestUser user, String firstBatch) throws Exception {
        List<List<String>> batches = new ArrayList<>();
        String next = firstBatch;
        while (Objects.nonNull(next)) {
            assertThat(batches).hasSizeLessThan(20);
            ResultActions batch = getAs(user.accessToken(), next).andExpect(status().isOk());
            batches.add(read(batch, "$.response[*].id"));
            if (Boolean.TRUE.equals(read(batch, "$.pageInfo.hasMore"))) {
                next = firstBatch + "&after=" + read(batch, "$.pageInfo.nextCursor");
            } else {
                batch.andExpect(jsonPath("$.pageInfo.nextCursor").doesNotExist());
                next = null;
            }
        }
        return batches;
    }

    private static List<String> flatten(List<List<String>> batches) {
        return batches.stream().flatMap(List::stream).toList();
    }

    @Test
    @DisplayName("The list walks your plans in calendar order (STARTS_ON, the default), every plan once, each with its todo")
    void cursorBatchesWalkTheWholeListInOrder() throws Exception {
        TestUser user = newUser();
        List<String> ids = planDays(user, LocalDate.parse("2026-03-01"), 7);
        TestUser stranger = newUser();
        createPlan(stranger, createTodo(stranger, "Not mine"), "2026-03-02", "2026-03-02");

        List<List<String>> batches = walk(user, PLANS + "?size=3");

        assertThat(batches).hasSize(3);
        assertThat(flatten(batches)).containsExactlyElementsOf(ids);
        getAs(user.accessToken(), PLANS + "?size=1")
                .andExpect(jsonPath("$.response[0].todo.title").value("Even days"))
                .andExpect(jsonPath("$.response[0].startsOn").value("2026-03-01"));
    }

    @Test
    @DisplayName("NEWEST walks your plans newest first, every plan once")
    void newestWalksNewestFirst() throws Exception {
        TestUser user = newUser();
        List<String> ids = newestFirst(planDays(user, LocalDate.parse("2026-04-01"), 5));

        assertThat(flatten(walk(user, PLANS + "?sort=NEWEST&size=2"))).containsExactlyElementsOf(ids);
    }

    @Test
    @DisplayName("todoId returns only that todo's plans; another user's todo id returns none")
    void filterByTodoIdReturnsOnlyMatching() throws Exception {
        TestUser user = newUser();
        String reading = createTodo(user, "Reading");
        String readingPlan = createPlan(user, reading, "2026-05-01", "2026-05-02");
        String otherPlan = createPlan(user, createTodo(user, "Writing"), "2026-05-03", "2026-05-04");
        TestUser stranger = newUser();
        String strangersTodo = createTodo(stranger, "Theirs");
        createPlan(stranger, strangersTodo, "2026-05-01", "2026-05-02");
        getAs(user.accessToken(), PLANS).andExpect(jsonPath("$.response[*].id", hasItem(otherPlan)));

        getAs(user.accessToken(), PLANS + "?todoId=" + reading)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].id", contains(readingPlan)));
        getAs(user.accessToken(), PLANS + "?todoId=" + strangersTodo)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", empty()));
    }

    @Test
    @DisplayName("plannedFrom..plannedTo returns every plan sharing a day with the range, in calendar order: the ones crossing either end too")
    void plannedDaysFindEveryPlanSharingADay() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user, "Planned");
        String crossingTheEnd = createPlan(user, todoId, "2026-03-18", "2026-03-20");
        createPlan(user, todoId, "2026-03-01", "2026-03-05");
        String crossingTheStart = createPlan(user, todoId, "2026-03-08", "2026-03-11");
        String inside = createPlan(user, todoId, "2026-03-14", "2026-03-14");
        createPlan(user, todoId, "2026-03-21", "2026-03-21");
        TestUser stranger = newUser();
        createPlan(stranger, createTodo(stranger, "Theirs"), "2026-03-14", "2026-03-14");

        getAs(user.accessToken(), PLANS + "?plannedFrom=2026-03-10&plannedTo=2026-03-19")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].id", contains(crossingTheStart, inside, crossingTheEnd)));
        getAs(user.accessToken(), PLANS + "?plannedFrom=2026-03-20")
                .andExpect(jsonPath("$.response[*].id", hasSize(2)));
        getAs(user.accessToken(), PLANS + "?plannedTo=2026-03-09")
                .andExpect(jsonPath("$.response[*].id", hasSize(2)));
    }

    @Test
    @DisplayName("A plan of the maximum length is found on its last day, the bound plannedFrom reads back to; not a day later")
    void theLongestPlanIsFoundOnItsLastDay() throws Exception {
        TestUser user = newUser();
        LocalDate first = LocalDate.parse("2026-05-01");
        LocalDate last = first.plusDays(MAX_DAYS - 1);
        String longest = createPlan(user, createTodo(user, "Longest"), first.toString(), last.toString());

        getAs(user.accessToken(), PLANS + "?plannedFrom=" + last)
                .andExpect(jsonPath("$.response[*].id", contains(longest)));
        getAs(user.accessToken(), PLANS + "?plannedFrom=" + last.plusDays(1))
                .andExpect(jsonPath("$.response", empty()));
    }

    @Test
    @DisplayName("plannedFrom after plannedTo is 400 reported on plannedTo; one day for both is a range")
    void plannedRangeMustBeOrdered() throws Exception {
        TestUser user = newUser();

        getAs(user.accessToken(), PLANS + "?plannedFrom=2026-03-11&plannedTo=2026-03-10")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].parameter", contains("plannedTo")))
                .andExpect(jsonPath("$.errors[*].detail", contains("plannedFrom must not be after plannedTo")));
        getAs(user.accessToken(), PLANS + "?plannedFrom=2026-03-10&plannedTo=2026-03-10").andExpect(status().isOk());
    }

    @Test
    @DisplayName("todoId walks one todo's plans in both orders, every plan once")
    void cursorWalkUnderTheTodoFilter() throws Exception {
        TestUser user = newUser();
        String todoId = createTodo(user, "Walked");
        List<String> ids = new ArrayList<>();
        for (int day = 1; day <= 5; day++) {
            String date = "2026-06-0" + day;
            ids.add(createPlan(user, todoId, date, date));
        }
        createPlan(user, createTodo(user, "Elsewhere"), "2026-06-09", "2026-06-09");

        assertThat(flatten(walk(user, PLANS + "?todoId=" + todoId + "&size=2"))).containsExactlyElementsOf(ids);
        List<String> newestFirst = new ArrayList<>(ids);
        Collections.reverse(newestFirst);
        assertThat(flatten(walk(user, PLANS + "?todoId=" + todoId + "&sort=NEWEST&size=2"))).containsExactlyElementsOf(newestFirst);
    }

    @Test
    @DisplayName("ids returns only the asked plans, in the list's order; unknown and another user's ids are simply absent")
    void filterByIdsReturnsOnlyMatching() throws Exception {
        TestUser user = newUser();
        List<String> ids = planDays(user, LocalDate.parse("2026-07-01"), 3);
        TestUser stranger = newUser();
        String theirs = createPlan(stranger, createTodo(stranger, "Theirs"), "2026-07-01", "2026-07-01");
        getAs(user.accessToken(), PLANS).andExpect(jsonPath("$.response[*].id", hasItem(ids.get(1))));

        getAs(user.accessToken(), PLANS + "?ids=" + String.join(",", ids.get(2), ids.get(0), theirs, UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].id", contains(ids.get(0), ids.get(2))));
    }

    @Test
    @DisplayName("ids takes up to the maximum batch size; one more is 400 VALIDATION_FAILED, a malformed id 400 REQUEST_PARAMETER_INVALID")
    void idsAreBounded() throws Exception {
        TestUser user = newUser();

        getAs(user.accessToken(), PLANS + "?ids=" + randomIds(100))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", empty()));
        getAs(user.accessToken(), PLANS + "?ids=" + randomIds(101))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].parameter", contains("ids")))
                .andExpect(jsonPath("$.errors[*].detail", contains("must have at most 100 items")));
        getAs(user.accessToken(), PLANS + "?ids=nope," + UUID.randomUUID())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.errors[*].parameter", contains("ids")))
                .andExpect(jsonPath("$.errors[*].detail", contains("invalid value")));
    }

    @Test
    @DisplayName("A malformed todoId or an unknown sort is 400 REQUEST_PARAMETER_INVALID naming only the parameter")
    void unconvertibleFilterValuesAre400() throws Exception {
        TestUser user = newUser();

        for (String query : List.of("?todoId=nope", "?sort=OLDEST", "?plannedFrom=10-03-2026")) {
            String body = getAs(user.accessToken(), PLANS + query)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                    .andReturn().getResponse().getContentAsString();
            assertNoInternals(body);
        }
    }

    @Test
    @DisplayName("A cursor from another sort, or a tampered one, is 400 REQUEST_PARAMETER_INVALID naming after")
    void foreignOrTamperedCursorIsRefused() throws Exception {
        TestUser user = newUser();
        planDays(user, LocalDate.parse("2026-08-01"), 2);
        String newestCursor = read(getAs(user.accessToken(), PLANS + "?sort=NEWEST&size=1"), "$.pageInfo.nextCursor");

        for (String path : List.of(PLANS + "?sort=STARTS_ON&size=1&after=" + newestCursor, PLANS + "?after=not-a-cursor")) {
            getAs(user.accessToken(), path)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                    .andExpect(jsonPath("$.errors[0].parameter").value("after"));
        }
    }

    @Test
    @DisplayName("A cursor key the database can't store or the list doesn't expect is 400 naming after, never a 500")
    void cursorKeysTheDatabaseCantTakeAreRefused() throws Exception {
        TestUser user = newUser();
        String id = UUID.randomUUID().toString();
        List<String> paths = List.of(
                PLANS + "?sort=STARTS_ON&after=" + Cursor.write("STARTS_ON", "-300000-01-01", id),
                PLANS + "?sort=STARTS_ON&after=" + Cursor.write("STARTS_ON", "+300000-01-01", id),
                PLANS + "?sort=NEWEST&after=" + Cursor.write("NEWEST", "+290000-01-01T00:00:00Z", id),
                PLANS + "?sort=NEWEST&after=" + Cursor.write("NEWEST", Instant.MIN.toString(), id),
                PLANS + "?sort=NEWEST&after=" + Cursor.write("NEWEST", Instant.MAX.toString(), id),
                PLANS + "?sort=STARTS_ON&after=" + Cursor.write("STARTS_ON", "2026-11-14", id, id));

        for (String path : paths) {
            String body = getAs(user.accessToken(), path)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                    .andExpect(jsonPath("$.errors[0].parameter").value("after"))
                    .andReturn().getResponse().getContentAsString();
            assertNoInternals(body);
        }
    }

    @Test
    @DisplayName("size up to the configured maximum is accepted; one more is 400 VALIDATION_FAILED with the limit")
    void sizeIsBounded() throws Exception {
        TestUser user = newUser();

        getAs(user.accessToken(), PLANS + "?size=100").andExpect(status().isOk());
        getAs(user.accessToken(), PLANS + "?size=101")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].parameter", contains("size")))
                .andExpect(jsonPath("$.errors[*].detail", contains("must be at most 100")));
    }

    private long statementsFor(TestUser user, String path) throws Exception {
        statistics().clear();
        getAs(user.accessToken(), path).andExpect(status().isOk());
        return statistics().getPrepareStatementCount();
    }

    @Test
    @DisplayName("A batch costs two statements (the batch, then its todos through the todo module), for 1 row, a full batch and by ids")
    void listRunsAConstantNumberOfQueries() throws Exception {
        TestUser one = newUser();
        createPlan(one, createTodo(one, "Single"), "2026-09-01", "2026-09-01");
        TestUser many = newUser();
        List<String> ids = planDays(many, LocalDate.parse("2026-09-01"), 11);

        // Two, not a root list's one: the todos' titles are the todo module's, read in one lookup per batch.
        assertThat(statementsFor(one, PLANS + "?size=10")).isEqualTo(2);
        assertThat(statementsFor(many, PLANS + "?size=10")).isEqualTo(2);
        assertThat(statementsFor(many, PLANS + "?ids=" + String.join(",", ids))).isEqualTo(2);
    }

    @Test
    @DisplayName("The API docs describe the overlap and done-todo refusals and the todo's 404, without TODO_GONE (the create locks the todo)")
    void apiDocsDescribeThePlanErrors() throws Exception {
        getAs(null, "/v3/api-docs")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/plans'].get.responses['400'].description", containsString("REQUEST_PARAMETER_INVALID")))
                .andExpect(jsonPath("$.paths['/api/v1/plans'].get.parameters[?(@.name == 'ids')].schema.maxItems", contains(100)))
                .andExpect(jsonPath("$.paths['/api/v1/plans'].post.responses['409'].description", containsString("PLAN_OVERLAPS")))
                .andExpect(jsonPath("$.paths['/api/v1/plans'].post.responses['409'].description", containsString("PLAN_TODO_DONE")))
                .andExpect(jsonPath("$.paths['/api/v1/plans'].post.responses['404'].description", containsString("TODO_NOT_FOUND")))
                .andExpect(jsonPath("$.paths['/api/v1/plans'].post.responses['404'].description", not(containsString("TODO_GONE"))))
                .andExpect(jsonPath("$.paths['/api/v1/todos/{id}'].delete.responses['409'].description", containsString("plans")));
    }
}
