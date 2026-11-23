package com.example.app.todo;

import com.example.app.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TodoReferencesIntegrationTest extends IntegrationTest {

    private static final String LISTS = "/api/v1/todo-lists";
    private static final String TODOS = "/api/v1/todos";

    @Autowired
    private PlatformTransactionManager transactionManager;

    private String createList(TestUser user, String name) throws Exception {
        return read(postAs(user.accessToken(), LISTS, """
                {"name": "%s"}
                """.formatted(name)).andExpect(status().isCreated()), "$.response.id");
    }

    private ResultActions postTodo(TestUser user, String listId, String title, String requesterId) throws Exception {
        String requester = requesterId == null ? "null" : "\"" + requesterId + "\"";
        return postAs(user.accessToken(), TODOS, """
                {"todoListId": "%s", "title": "%s", "priority": "LOW", "requesterId": %s}
                """.formatted(listId, title, requester));
    }

    private ResultActions putTodo(TestUser user, String id, String listId, String requesterId, long version) throws Exception {
        String requester = requesterId == null ? "null" : "\"" + requesterId + "\"";
        return putAs(user.accessToken(), TODOS + "/" + id, """
                {"todoListId": "%s", "title": "Edited", "priority": "LOW", "requesterId": %s, "version": %d}
                """.formatted(listId, requester, version));
    }

    private String createTodo(TestUser user, String listId, String title) throws Exception {
        return read(postTodo(user, listId, title, null).andExpect(status().isCreated()), "$.response.id");
    }

    @Test
    @DisplayName("The detail, the create and every write show the todo's list by id and name")
    void responsesShowTheListByName() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Groceries");
        String otherListId = createList(user, "Hardware");

        String id = read(postTodo(user, listId, "Oat milk", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.todoList.id").value(listId))
                .andExpect(jsonPath("$.response.todoList.name").value("Groceries")), "$.response.id");

        getAs(user.accessToken(), TODOS + "/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.todoListId").value(listId))
                .andExpect(jsonPath("$.response.todoList.name").value("Groceries"));
        putAs(user.accessToken(), TODOS + "/" + id, """
                {"todoListId": "%s", "title": "Oat milk", "priority": "LOW", "version": 0}
                """.formatted(otherListId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.todoList.id").value(otherListId))
                .andExpect(jsonPath("$.response.todoList.name").value("Hardware"));
        patchAs(user.accessToken(), TODOS + "/" + id + "/status", """
                {"status": "DONE", "version": 1}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.todoList.name").value("Hardware"));
    }

    @Test
    @DisplayName("The list's rows show each todo's list by id and name, and who asked for it by id")
    void listRowsShowTheirListsByName() throws Exception {
        TestUser user = newUser();
        String groceries = createList(user, "Groceries");
        String hardware = createList(user, "Hardware");
        String milk = createTodo(user, groceries, "Oat milk");
        String nails = createTodo(user, hardware, "Nails");

        getAs(user.accessToken(), TODOS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[?(@.id == '%s')].todoList.name".formatted(milk)).value("Groceries"))
                .andExpect(jsonPath("$.response[?(@.id == '%s')].todoList.id".formatted(nails)).value(hardware))
                .andExpect(jsonPath("$.response[?(@.id == '%s')].todoList.name".formatted(nails)).value("Hardware"))
                .andExpect(jsonPath("$.response[0].requesterId").hasJsonPath());
    }

    @Test
    @DisplayName("""
            A todo deleted with its list between the batch's read and the lists' names is left out of the page \
            (200), never shown without its list or a 500""")
    void listLeavesOutATodoWhoseListVanishedBetweenTheReads() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Vanishing");
        String vanishing = createTodo(user, listId, "Gone soon");
        String staying = createTodo(user, createList(user, "Staying"), "Stays");

        Answer page = whileTheTodoAndItsListGo(vanishing, listId, () -> getAs(user.accessToken(), TODOS));

        assertThat(page.status()).as(page.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(page.body(), "$.response[*].id")).containsExactly(staying);
    }

    @Test
    @DisplayName("A todo deleted with its list between the todo's read and the list's is 404 TODO_NOT_FOUND, never a 500")
    void getOfATodoWhoseListVanishedBetweenTheReadsIs404() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Vanishing");
        String id = createTodo(user, listId, "Gone soon");

        Answer answer = whileTheTodoAndItsListGo(id, listId, () -> getAs(user.accessToken(), TODOS + "/" + id));

        assertThat(answer.status()).as(answer.body()).isEqualTo(404);
        assertRefusalsAre(List.of(answer), 404, "TODO_NOT_FOUND");
    }

    @Test
    @DisplayName("A status change that changes nothing holds no row lock: its todo deleted with its list meanwhile is 404, never a 500")
    void aStatusChangeToTheSameStatusOfAVanishingTodoIs404() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Vanishing");
        String id = createTodo(user, listId, "Gone soon");

        Answer answer = whileTheTodoAndItsListGo(id, listId, () -> patchAs(user.accessToken(), TODOS + "/" + id + "/status", """
                {"status": "OPEN", "version": 0}
                """));

        assertThat(answer.status()).as(answer.body()).isEqualTo(404);
        assertRefusalsAre(List.of(answer), 404, "TODO_NOT_FOUND");
    }

    /**
     * With the lists' table held ACCESS EXCLUSIVE by the deleting transaction, a read of the todo alone still
     * sees it and a read of the list waits. Read in one statement, the todo and its list go together (404);
     * read lazily, the todo is found and its list is not (a 500).
     */
    private Answer whileTheTodoAndItsListGo(String todoId, String listId, Callable<ResultActions> read) throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try (ExecutorService executor = newExecutor()) {
            Future<?> deletion = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.execute("lock table todo_lists in access exclusive mode");
                jdbc.update("delete from todos where id = ?", UUID.fromString(todoId));
                jdbc.update("delete from todo_lists where id = ?", UUID.fromString(listId));
                deleted.countDown();
                awaitQuietly(commit);
            }));
            awaitLatch(deleted);
            Future<Answer> reading;
            try {
                Callable<Answer> once = () -> answerOf(read.call());
                reading = executor.submit(once);
                awaitLockWaiters(1, reading);
                assertThat(reading.isDone()).as("the read waits at the list").isFalse();
            } finally {
                commit.countDown();
            }
            awaitResult(deletion);
            return awaitResult(reading);
        }
    }
    // tenancy:begin
    // tenancy:else

    @Test
    @DisplayName("""
            A todo names the account that asked for it: echoed on the create and the detail, kept by a PUT that \
            re-sends it, replaced by one that names another, cleared by one without it""")
    void aTodoNamesItsRequester() throws Exception {
        TestUser user = newUser();
        TestUser requester = newUser();
        TestUser another = newUser();
        String listId = createList(user, "Asked for");

        String id = read(postTodo(user, listId, "Asked", requester.id().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.requesterId").value(requester.id().toString())), "$.response.id");

        putTodo(user, id, listId, requester.id().toString(), 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.requesterId").value(requester.id().toString()));
        getAs(user.accessToken(), TODOS + "/" + id)
                .andExpect(jsonPath("$.response.requesterId").value(requester.id().toString()));
        putTodo(user, id, listId, another.id().toString(), 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.requesterId").value(another.id().toString()));
        putTodo(user, id, listId, null, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.requesterId").doesNotExist());
        postTodo(user, listId, "Nobody asked", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.requesterId").doesNotExist());
    }

    @Test
    @DisplayName("A requesterId that is no account is 404 USER_NOT_FOUND on the create and the update, and nothing is stored")
    void anUnknownRequesterIs404() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Asked for");
        String id = createTodo(user, createList(user, "Kept"), "Kept as it was");

        postTodo(user, listId, "Asked by nobody", UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("USER_NOT_FOUND"));
        putTodo(user, id, listId, UUID.randomUUID().toString(), 0)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("USER_NOT_FOUND"));

        assertThat(jdbc.queryForObject("select count(*) from todos where todo_list_id = ?", Integer.class, UUID.fromString(listId)))
                .isZero();
        getAs(user.accessToken(), TODOS + "/" + id)
                .andExpect(jsonPath("$.response.version").value(0))
                .andExpect(jsonPath("$.response.requesterId").doesNotExist());
    }
    // tenancy:end
    // metadata:begin

    private long numberOf(ResultActions created) throws Exception {
        Number number = read(created.andExpect(status().isCreated()), "$.response.number");
        return number.longValue();
    }

    @Test
    @DisplayName("Creates in a row get consecutive numbers, shown on the detail and on the list's rows")
    void consecutiveCreatesGetConsecutiveNumbers() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Numbered");

        long first = numberOf(postTodo(user, listId, "First", null));
        long second = numberOf(postTodo(user, listId, "Second", null));

        assertThat(first).isPositive();
        assertThat(second).isEqualTo(first + 1);
        getAs(user.accessToken(), TODOS + "?todoListId=" + listId + "&sort=NEWEST")
                .andExpect(jsonPath("$.response[0].number").value(second))
                .andExpect(jsonPath("$.response[1].number").value(first));
    }

    @Test
    @DisplayName("A refused create takes no number")
    void aRefusedCreateTakesNoNumber() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Numbered");
        long first = numberOf(postTodo(user, listId, "First", null));

        postTodo(user, UUID.randomUUID().toString(), "Refused", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TODO_LIST_NOT_FOUND"));

        assertThat(numberOf(postTodo(user, listId, "Second", null))).isEqualTo(first + 1);
    }

    @Test
    @DisplayName("Concurrent creates get distinct consecutive numbers: the counter's row queues them, never a duplicate or a 500")
    void concurrentCreatesGetDistinctConsecutiveNumbers() throws Exception {
        TestUser user = newUser();
        String listId = createList(user, "Raced");

        List<Answer> answers = race(() -> postTodo(user, listId, "Raced", null));

        assertThat(answers).extracting(Answer::status).containsOnly(201);
        List<Long> numbers = jdbc.queryForList("select number from todos where todo_list_id = ? order by number",
                Long.class, UUID.fromString(listId));
        assertThat(numbers).hasSize(RACERS).doesNotHaveDuplicates();
        assertThat(numbers.getLast() - numbers.getFirst()).isEqualTo(RACERS - 1);
    }
    // metadata:end
}
