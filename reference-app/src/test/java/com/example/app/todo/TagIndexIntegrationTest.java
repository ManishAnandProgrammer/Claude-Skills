package com.example.app.todo;

import com.example.app.common.dropdown.DropdownStatus;
import com.example.app.support.IntegrationTest;
import com.example.app.support.ReadCost;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every cell of the tag list and its dropdown (each order under each status value, a later page, the
 * search, the ids lookup) has its index and reads at most its bound (crud-gen tests.md row 28). The owner's
 * 500 tags, one in five inactive, sit beside a sibling owner's 10,000, interleaved in id order, so a cell
 * its index doesn't serve reads at least ten times its bound.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TagIndexIntegrationTest extends IntegrationTest {

    private static final String TAG_ROW = """
            select id, name, color, active, owner_id, created_at, updated_at, created_by, updated_by, version
              from tags""";
    private static final String DROPDOWN_ITEM = "select id, name from tags";
    private static final int PAGE = 20;
    private static final int TWO_PAGES = 2 * PAGE;
    private static final int OWNER_TAGS = 500;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final MapSqlParameterSource parameters = new MapSqlParameterSource();

    @BeforeAll
    void seedAnOwnerBesideABiggerOne() throws Exception {
        UUID ownerId = newUser().id();
        UUID siblingId = newUser().id();
        // Twenty rounds of the owner's share, then the sibling's: rows reach the heap in id order, as in production.
        for (int round = 0; round < 20; round++) {
            seedTags(ownerId, round, OWNER_TAGS / 20);
            seedTags(siblingId, round, 10_000 / 20);
        }
        parameters.addValue("owner", ownerId)
                .addValue("active", true)
                .addValue("inactive", false)
                .addValue("query", "matches nothing")
                .addValue("ids", jdbc.queryForList("select id from tags where owner_id = ? limit 20", UUID.class, ownerId));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("set local default_statistics_target = 1000");
            jdbc.execute("analyze tags");
        });
    }

    /** {@code count} more tags of the owner, one in five inactive, created now: UUIDv7 ids and clock times. */
    private void seedTags(UUID owner, int round, int count) {
        // tenancy-row:begin
        // // Row mode: every insert names its tenant (tenant_id has no default).
        // sql("""
        //         insert into tags (id, tenant_id, owner_id, name, active, created_at, updated_at, version)
        //         select uuidv7(), ?, ?, 'Seeded ' || ? || '-' || n, n % 5 <> 0, clock_timestamp(), now(), 0 from generate_series(1, ?) n
        //         """, testTenant().id(), owner, round, count);
        // tenancy-row:else
        sql("""
                insert into tags (id, owner_id, name, active, created_at, updated_at, version)
                select uuidv7(), ?, 'Seeded ' || ? || '-' || n, n % 5 <> 0, clock_timestamp(), now(), 0 from generate_series(1, ?) n
                """, owner, round, count);
        // tenancy-row:end
    }

    private static Arguments cell(String name, String index, String statement, int bound) {
        return Arguments.of(name, index, statement, bound);
    }

    /** The page's and the dropdown's statements, every DropdownStatus value under every order. */
    static Stream<Arguments> cells() {
        List<Arguments> cells = new ArrayList<>();
        cells.add(cell("tags, NAME", "idx_tags_owner_id_name_id",
                TAG_ROW + " where owner_id = :owner order by name, id limit 20", TWO_PAGES));
        cells.add(cell("tags, NAME, a later page", "idx_tags_owner_id_name_id",
                TAG_ROW + " where owner_id = :owner order by name, id limit 20 offset 40", 40 + TWO_PAGES));
        cells.add(cell("tags, NEWEST", "idx_tags_owner_id_created_at_id",
                TAG_ROW + " where owner_id = :owner order by created_at desc, id desc limit 20", TWO_PAGES));
        cells.add(cell("tags, NEWEST, a later page", "idx_tags_owner_id_created_at_id",
                TAG_ROW + " where owner_id = :owner order by created_at desc, id desc limit 20 offset 40", 40 + TWO_PAGES));
        for (DropdownStatus status : List.of(DropdownStatus.ACTIVE, DropdownStatus.INACTIVE)) {
            String flag = status == DropdownStatus.ACTIVE ? ":active" : ":inactive";
            cells.add(cell("tags " + status + ", NAME", "idx_tags_owner_id_active_name_id",
                    TAG_ROW + " where owner_id = :owner and active = " + flag + " order by name, id limit 20", TWO_PAGES));
            cells.add(cell("tags " + status + ", NEWEST", "idx_tags_owner_id_active_created_at_id",
                    TAG_ROW + " where owner_id = :owner and active = " + flag + " order by created_at desc, id desc limit 20",
                    TWO_PAGES));
            cells.add(cell("tag dropdown " + status + ", ASC", "idx_tags_owner_id_active_name_id",
                    DROPDOWN_ITEM + " where owner_id = :owner and active = " + flag + " order by name, id limit 21", TWO_PAGES));
            cells.add(cell("tag dropdown " + status + ", DESC", "idx_tags_owner_id_active_name_id",
                    DROPDOWN_ITEM + " where owner_id = :owner and active = " + flag + " order by name desc, id desc limit 21",
                    TWO_PAGES));
        }
        cells.add(cell("tag dropdown ALL, ASC", "idx_tags_owner_id_name_id",
                DROPDOWN_ITEM + " where owner_id = :owner order by name, id limit 21", TWO_PAGES));
        cells.add(cell("tag dropdown ALL, DESC", "idx_tags_owner_id_name_id",
                DROPDOWN_ITEM + " where owner_id = :owner order by name desc, id desc limit 21", TWO_PAGES));
        // query is a contains search no btree serves (0004): it reads the owner's tags, never anyone else's.
        cells.add(cell("tags matching a query, NAME (the owner's tags at most)", "idx_tags_owner_id_name_id",
                TAG_ROW + " where owner_id = :owner and strpos(lower(name), :query) > 0 order by name, id limit 20", OWNER_TAGS));
        cells.add(cell("tags matching a query, NEWEST (the owner's tags at most)", "idx_tags_owner_id_created_at_id",
                TAG_ROW + " where owner_id = :owner and strpos(lower(name), :query) > 0 order by created_at desc, id desc limit 20",
                OWNER_TAGS));
        // The ids filter under every order: the asked rows through the primary key, then sorted.
        cells.add(cell("tags by ids, NAME", "pk_tags", TAG_ROW + " where owner_id = :owner and id in (:ids) order by name, id limit 20",
                TWO_PAGES));
        cells.add(cell("tags by ids, NEWEST", "pk_tags",
                TAG_ROW + " where owner_id = :owner and id in (:ids) order by created_at desc, id desc limit 20", TWO_PAGES));
        return cells.stream();
    }

    @ParameterizedTest(name = "{0} ({1}): at most {3} rows")
    @MethodSource("cells")
    @DisplayName("Every cell of the tag list and dropdown has its index and reads at most its bound")
    void everyCellReadsAtMostItsBound(String cell, String index, String statement, int bound) {
        ReadCost cost = ReadCost.of(jdbc, transactionManager, statement, parameters);

        assertThat(ReadCost.indexExists(jdbc, index)).as(index).isTrue();
        assertThat(cost.rowsRead()).as(cost.plan()).isLessThanOrEqualTo(bound);
    }
}
