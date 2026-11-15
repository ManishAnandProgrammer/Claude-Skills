package com.example.app.todo;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every tag order (the page's and the dropdown's), under every filter that scopes it, reads its
 * intended index in order: that index named in the plan, the scope in the index condition and no
 * sort step. Scans that read whole tables are
 * switched off, because on the few rows of a test database they would win on cost and hide a
 * missing index.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TagIndexIntegrationTest extends IntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID ownerId;

    @BeforeAll
    void seedOneOwnerWithManyTags() throws Exception {
        ownerId = newUser().id();
        // tenancy-row:begin
        // // Row mode: every insert names its tenant (tenant_id has no default).
        // sql("""
        //         insert into tags (id, tenant_id, owner_id, name, active, created_at, updated_at, version)
        //         select gen_random_uuid(), ?, ?, 'Seeded ' || n, n % 5 <> 0, now(), now(), 0 from generate_series(1, 500) n
        //         """, testTenant().id(), ownerId);
        // tenancy-row:else
        sql("""
                insert into tags (id, owner_id, name, active, created_at, updated_at, version)
                select gen_random_uuid(), ?, 'Seeded ' || n, n % 5 <> 0, now(), now(), 0 from generate_series(1, 500) n
                """, ownerId);
        // tenancy-row:end
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> jdbc.execute("analyze tags"));
    }

    static Stream<Arguments> scopedOrders() {
        return Stream.of(
                Arguments.of("tags, NAME", "tags where owner_id = :owner order by name, id", List.of("owner_id"),
                        "idx_tags_owner_id_name_id"),
                Arguments.of("tags, NEWEST", "tags where owner_id = :owner order by id desc", List.of("owner_id"),
                        "idx_tags_owner_id_id"),
                Arguments.of("tags by status, NAME", "tags where owner_id = :owner and active = :active order by name, id",
                        List.of("owner_id", "active"),
                        "idx_tags_owner_id_active_name_id"),
                Arguments.of("tag dropdown ALL, DESC", "tags where owner_id = :owner order by name desc, id desc",
                        List.of("owner_id"),
                        "idx_tags_owner_id_name_id"),
                Arguments.of("tag dropdown ACTIVE, ASC", "tags where owner_id = :owner and active = :active order by name, id",
                        List.of("owner_id", "active"),
                        "idx_tags_owner_id_active_name_id"),
                Arguments.of("tag dropdown ACTIVE, DESC",
                        "tags where owner_id = :owner and active = :active order by name desc, id desc",
                        List.of("owner_id", "active"),
                        "idx_tags_owner_id_active_name_id"));
    }

    @ParameterizedTest(name = "{0}: {3}")
    @MethodSource("scopedOrders")
    @DisplayName("Every offered tag order under every scoping filter reads its intended index in order")
    void everyScopedOrderReadsAnIndexInOrder(String name, String fromWhereOrder, List<String> scope, String index) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("owner", ownerId)
                .addValue("active", true);

        List<String> planLines = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("set local enable_seqscan = off");
            jdbc.execute("set local enable_bitmapscan = off");
            return new NamedParameterJdbcTemplate(jdbc)
                    .queryForList("explain select id from " + fromWhereOrder + " limit 21", parameters, String.class);
        });
        String plan = String.join("\n", planLines);

        assertThat(plan).as(plan).contains("using " + index + " on").doesNotContain("Sort");
        String indexCondition = plan.lines().filter(line -> line.contains("Index Cond:")).findFirst().orElse("");
        // One column at a time: an unscoped order (a whole shared table) has none, and contains() refuses an empty list.
        for (String column : scope) {
            assertThat(indexCondition).as(plan).contains(column);
        }
    }
}
