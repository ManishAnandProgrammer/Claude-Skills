package com.example.app.todo;

import com.example.app.support.IntegrationTest;
import com.example.app.support.ReadCost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the changesets after the previous release did to a used database (support/PreviousReleaseDatabase
 * built it: that release's changesets, then db/upgrade/previous-release-seed.sql, then the rest; crud-gen
 * tests.md, *Upgrading a used database*). Runs only on such a run: verify.sh sets
 * {@code app.test.upgrade.previous-release}.
 */
@EnabledIfSystemProperty(named = "app.test.upgrade.previous-release", matches = "\\d+")
class UpgradeFromPreviousReleaseIntegrationTest extends IntegrationTest {

    /** The seed's heavy owner: 20 lists of 60 todos, one in twenty OPEN. */
    private static final UUID HEAVY = UUID.fromString("01890000-0000-7000-8000-000000000001");
    private static final UUID HEAVY_FIRST_LIST = UUID.fromString("01890000-0000-7000-8001-000000000001");
    private static final String HEAVY_EMAIL = "upgrade-heavy@example.com";
    private static final UUID LIGHT = UUID.fromString("01890000-0000-7000-8000-000000000002");

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** The seeded owner's access token: the seed stores no real password hash, so the test gives it one. */
    private String heavyOwnersToken() throws Exception {
        sql("update users set password_hash = ? where id = ?", passwordEncoder.encode(PASSWORD), HEAVY);
        return read(login(HEAVY_EMAIL, PASSWORD).andExpect(status().isOk()), "$.response.accessToken");
    }

    @Test
    @DisplayName("""
            The new release's changesets ran over the seeded rows: the rows are all there, the requesters kept, NEWEST's \
            indexes on the creation time replaced the id ones (0029)""")
    void theNewChangesetsRanOverTheSeededRows() {
        assertThat(jdbc.queryForList("select id from databasechangelog", String.class)).contains("0029-newest-by-created-at");
        assertThat(jdbc.queryForObject("select count(*) from todos where owner_id = ?", Integer.class, HEAVY)).isGreaterThanOrEqualTo(1_200);
        assertThat(jdbc.queryForObject("select count(*) from todos where owner_id = ? and requester_id = ?", Integer.class, HEAVY, LIGHT))
                .as("every tenth seeded todo names the light account").isEqualTo(20 * 6);
        assertThat(ReadCost.indexExists(jdbc, "idx_todos_owner_id_created_at_id")).isTrue();
        assertThat(ReadCost.indexExists(jdbc, "idx_todos_owner_id_id")).isFalse();
    }

    @Test
    @DisplayName("""
            The upgrade changed no account and no refresh token: the suspended one still SUSPENDED (login 403), the \
            others ACTIVE, the live token live, the rotated one still ROTATED""")
    void theSeededAccountsKeepTheirStatus() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from users where email like 'upgrade-%' and status <> 'ACTIVE'", Integer.class))
                .isOne();
        assertThat(jdbc.queryForObject("select status from users where email = 'upgrade-idle@example.com'", String.class))
                .isEqualTo("SUSPENDED");
        sql("update users set password_hash = ? where email = 'upgrade-idle@example.com'", passwordEncoder.encode(PASSWORD));
        login("upgrade-idle@example.com", PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTH_ACCOUNT_SUSPENDED"));
        assertThat(jdbc.queryForObject("select revoked_at is null from refresh_tokens where token_hash = 'upgrade-live-token'", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("select revocation_reason from refresh_tokens where token_hash = 'upgrade-rotated-token'", String.class))
                .isEqualTo("ROTATED");
    }

    @Test
    @DisplayName("The seeded owner reads their lists' todos, a todo with its list's name, and creates one")
    void theSeededOwnerWorksAsBefore() throws Exception {
        String owner = heavyOwnersToken();
        UUID todoId = jdbc.queryForObject("select id from todos where todo_list_id = ? order by id limit 1", UUID.class, HEAVY_FIRST_LIST);

        getAs(owner, "/api/v1/todos?todoListId=" + HEAVY_FIRST_LIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.length()").value(20));
        getAs(owner, "/api/v1/todos/" + todoId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.todoList.name").value("Upgrade list 1"));
        postAs(owner, "/api/v1/todos", """
                {"todoListId": "%s", "title": "After the upgrade", "priority": "LOW"}
                """.formatted(HEAVY_FIRST_LIST)).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("A page of the seeded owner's OPEN todos (the rare status) and of their newest still reads at most two pages")
    void theIndexBoundsHoldOnTheSeededRows() {
        for (String statement : List.of(
                "select id from todos where owner_id = :owner and status = 'OPEN' order by due_sort, id limit 21",
                "select id from todos where owner_id = :owner order by created_at desc, id desc limit 21")) {
            ReadCost cost = ReadCost.of(jdbc, transactionManager, statement, new MapSqlParameterSource("owner", HEAVY));

            assertThat(cost.rowsRead()).as(cost.plan()).isLessThanOrEqualTo(42);
        }
    }
    // metadata:begin

    @Test
    @DisplayName("Every seeded todo keeps its number, 1 to N, and the counter goes on after the highest")
    void theSeededNumbersAreKept() throws Exception {
        Long seeded = jdbc.queryForObject("select count(*) from todos where title like 'Upgrade todo %' or title like 'Light todo %'",
                Long.class);
        assertThat(jdbc.queryForObject("""
                select count(distinct number) from todos where title like 'Upgrade todo %' or title like 'Light todo %'""",
                Long.class)).isEqualTo(seeded);
        Long counter = jdbc.queryForObject("select last_value from counters where key = 'TODO_NUMBER'", Long.class);

        Number created = read(postAs(heavyOwnersToken(), "/api/v1/todos", """
                {"todoListId": "%s", "title": "Numbered after the upgrade", "priority": "LOW"}
                """.formatted(HEAVY_FIRST_LIST)).andExpect(status().isCreated()), "$.response.number");

        assertThat(counter).isGreaterThanOrEqualTo(seeded);
        assertThat(created.longValue()).isEqualTo(counter + 1);
    }
    // metadata:end
}
