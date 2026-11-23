package com.example.app.todo;

import com.example.app.common.dropdown.DropdownStatus;
import com.example.app.common.security.Permission;
import com.example.app.common.security.Reach;
import com.example.app.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TodoDropdownIntegrationTest extends IntegrationTest {

    private static final String LISTS = "/api/v1/todo-lists";
    private static final String TODOS = "/api/v1/todos";

    private String createList(TestUser user, String name) throws Exception {
        return read(postAs(user.accessToken(), LISTS, """
                {"name": "%s"}
                """.formatted(name)).andExpect(status().isCreated()), "$.response.id");
    }

    private String createTodo(TestUser user, String listId, String title) throws Exception {
        return read(postAs(user.accessToken(), TODOS, """
                {"todoListId": "%s", "title": "%s", "priority": "MEDIUM"}
                """.formatted(listId, title)).andExpect(status().isCreated()), "$.response.id");
    }

    private static String dropdownForANewPlan(String listId) {
        return TODOS + "/dropdown?for=PLAN_CREATE_OWN&todoListId=" + listId;
    }

    private long statementsFor(TestUser user, String path) throws Exception {
        statistics().clear();
        getAs(user.accessToken(), path).andExpect(status().isOk());
        return statistics().getPrepareStatementCount();
    }

    private List<List<String>> valuesOfEveryBatch(TestUser user, String firstBatch) throws Exception {
        List<List<String>> batches = new ArrayList<>();
        String next = firstBatch;
        while (Objects.nonNull(next)) {
            String body = getAs(user.accessToken(), next).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            batches.add(JsonPath.read(body, "$.response[*].value"));
            Boolean hasMore = JsonPath.read(body, "$.pageInfo.hasMore");
            next = hasMore ? firstBatch + "&after=" + JsonPath.read(body, "$.pageInfo.nextCursor") : null;
        }
        return batches;
    }

    @Test
    @DisplayName("The dropdown lists the list's todos only, as exactly {value, label}, ordered by title")
    void dropdownListsTheListsTodosInTitleOrder() throws Exception {
        TestUser user = newUser();
        String list = createList(user, "Errands " + UUID.randomUUID());
        String other = createList(user, "Work " + UUID.randomUUID());
        String bread = createTodo(user, list, "Bread");
        createTodo(user, list, "Milk");
        createTodo(user, list, "Apples");
        createTodo(user, other, "Another list's todo");

        getAs(user.accessToken(), dropdownForANewPlan(list))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].label", contains("Apples", "Bread", "Milk")))
                .andExpect(jsonPath("$.response[1].value").value(bread))
                .andExpect(jsonPath("$.response[1].length()").value(2))
                .andExpect(jsonPath("$.pageInfo.hasMore").value(false));
        getAs(user.accessToken(), dropdownForANewPlan(list) + "&sortDirection=DESC")
                .andExpect(jsonPath("$.response[*].label", contains("Milk", "Bread", "Apples")));
        getAs(user.accessToken(), dropdownForANewPlan(list) + "&query=READ")
                .andExpect(jsonPath("$.response[*].label", contains("Bread")));
    }

    @Test
    @DisplayName("Todos of one title split across batches come back in id order, every one once, in both directions")
    void equalTitlesWalkInIdOrderInBothDirections() throws Exception {
        TestUser user = newUser();
        String list = createList(user, "Repeats " + UUID.randomUUID());
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            ids.add(createTodo(user, list, "Same title"));
        }
        List<String> ascending = ids.stream().sorted().toList();
        List<String> descending = ascending.reversed();

        assertThat(valuesOfEveryBatch(user, dropdownForANewPlan(list) + "&size=2")).containsExactly(
                ascending.subList(0, 2), ascending.subList(2, 4), ascending.subList(4, 5));
        assertThat(valuesOfEveryBatch(user, dropdownForANewPlan(list) + "&size=2&sortDirection=DESC")).containsExactly(
                descending.subList(0, 2), descending.subList(2, 4), descending.subList(4, 5));
    }

    @Test
    @DisplayName("Another user's list, or an unknown id, offers no todos: an empty batch in one statement, never a 404")
    void anOutOfReachParentIsAnEmptyBatch() throws Exception {
        TestUser owner = newUser();
        String theirs = createList(owner, "Theirs " + UUID.randomUUID());
        createTodo(owner, theirs, "Their todo");
        TestUser stranger = newUser();
        getAs(owner.accessToken(), dropdownForANewPlan(theirs)).andExpect(jsonPath("$.response.length()").value(1));

        for (String parent : List.of(theirs, UUID.randomUUID().toString())) {
            getAs(stranger.accessToken(), dropdownForANewPlan(parent))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.response.length()").value(0))
                    .andExpect(jsonPath("$.pageInfo.hasMore").value(false));
            assertThat(statementsFor(stranger, dropdownForANewPlan(parent))).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("A dropdown batch costs exactly one statement, for one option and for a full batch: for and the parent included")
    void dropdownRunsOneStatement() throws Exception {
        TestUser user = newUser();
        String single = createList(user, "Single " + UUID.randomUUID());
        createTodo(user, single, "Only");
        String many = createList(user, "Many " + UUID.randomUUID());
        for (int index = 0; index < 21; index++) {
            createTodo(user, many, "Option " + index);
        }
        getAs(user.accessToken(), dropdownForANewPlan(many) + "&size=20")
                .andExpect(jsonPath("$.response.length()").value(20))
                .andExpect(jsonPath("$.pageInfo.hasMore").value(true));

        assertThat(statementsFor(user, dropdownForANewPlan(single) + "&size=20")).isEqualTo(1);
        assertThat(statementsFor(user, dropdownForANewPlan(many) + "&size=20")).isEqualTo(1);
    }

    @Test
    @DisplayName("The parent is mandatory: without todoListId 400 VALIDATION_FAILED naming it, not a UUID 400 REQUEST_PARAMETER_INVALID")
    void theParentIsMandatory() throws Exception {
        String token = newUser().accessToken();

        getAs(token, TODOS + "/dropdown?for=PLAN_CREATE_OWN")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].parameter").value("todoListId"));
        getAs(token, TODOS + "/dropdown?for=PLAN_CREATE_OWN&todoListId=not-a-uuid")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.errors[0].parameter").value("todoListId"));
    }

    @Test
    @DisplayName("The dropdown needs for: without it 400 REQUEST_PARAMETER_MISSING, with an unknown permission 400 REQUEST_PARAMETER_INVALID at for")
    void theDropdownNeedsFor() throws Exception {
        String token = newUser().accessToken();
        String parent = "todoListId=" + UUID.randomUUID();

        getAs(token, TODOS + "/dropdown?" + parent)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_MISSING"));
        getAs(token, TODOS + "/dropdown?for=NOT_A_PERMISSION&" + parent)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.errors[0].parameter").value("for"));
    }

    @Test
    @DisplayName("A for the caller doesn't hold is 403 ACCESS_DENIED, whatever else they hold, before any statement")
    void aForTheCallerDoesntHoldIs403() throws Exception {
        TestUser user = newUser();
        String list = createList(user, "Mine " + UUID.randomUUID());
        Set<Permission> allButIt = EnumSet.allOf(Permission.class);
        allButIt.remove(Permission.PLAN_CREATE_OWN);
        getAs(user.accessToken(), dropdownForANewPlan(list)).andExpect(status().isOk());

        statistics().clear();
        mvc.perform(get(dropdownForANewPlan(list)).with(holding(user.accessToken(), allButIt)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        assertThat(statistics().getPrepareStatementCount()).isZero();
    }

    @Test
    @DisplayName("A held for whose reach is every record is 400 DROPDOWN_REACH_UNSUPPORTED at for: this dropdown lists your own todos only")
    void aForOfAnotherReachIs400() throws Exception {
        Optional<Permission> everyRecord = Arrays.stream(Permission.values())
                .filter(permission -> permission.reach() == Reach.ALL)
                .findFirst();
        assumeTrue(everyRecord.isPresent(), "no permission reaches every record among these pieces");
        String holdingIt = newUser().accessToken();

        mvc.perform(get(TODOS + "/dropdown?todoListId=" + UUID.randomUUID() + "&for=" + everyRecord.get())
                        .with(holding(holdingIt, EnumSet.of(everyRecord.get()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("DROPDOWN_REACH_UNSUPPORTED"))
                .andExpect(jsonPath("$.errors[0].parameter").value("for"));
    }

    @Test
    @DisplayName("Each screen's for (a new plan's todo, the plans' todo filter) lists the same options")
    void eachScreensForListsTheSameOptions() throws Exception {
        TestUser user = newUser();
        String list = createList(user, "Shared " + UUID.randomUUID());
        createTodo(user, list, "Shared todo");

        for (String action : List.of("PLAN_CREATE_OWN", "PLAN_READ_OWN")) {
            getAs(user.accessToken(), TODOS + "/dropdown?for=" + action + "&todoListId=" + list)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.response[*].label", contains("Shared todo")));
        }
    }

    @ParameterizedTest
    @EnumSource(value = DropdownStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("A dropdown status other than ACTIVE is 400 REQUEST_PARAMETER_INVALID: todos have no active flag")
    void dropdownStatusIsRefusedWithoutAnActiveFlag(DropdownStatus refused) throws Exception {
        getAs(newUser().accessToken(), dropdownForANewPlan(UUID.randomUUID().toString()) + "&status=" + refused)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.errors[0].parameter").value("status"));
    }

    @Test
    @DisplayName("Without a token the dropdown is 401 AUTHENTICATION_REQUIRED")
    void theDropdownNeedsAToken() throws Exception {
        getAs(null, dropdownForANewPlan(UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
    }
}
