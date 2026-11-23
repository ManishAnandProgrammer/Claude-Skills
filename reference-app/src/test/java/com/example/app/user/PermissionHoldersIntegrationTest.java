package com.example.app.user;

import com.example.app.common.dropdown.DropdownCandidate;
import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.security.Permission;
import com.example.app.support.IntegrationTest;
import com.example.app.support.QueryPlan;
import com.example.app.user.api.PermissionHolders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;

// roles:begin
// import java.sql.Timestamp;
// import java.time.Instant;
// roles:end
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionHoldersIntegrationTest extends IntegrationTest {

    // roles:begin
    // private static final String HOLDERS = """
    //         select u.id, u.full_name from users u
    //          where u.status = 'ACTIVE'
    //            and (select count(distinct held.permission)
    //                   from (select p.permission from user_roles a join role_permissions p on p.role_id = a.role_id
    //                          where a.user_id = u.id and p.permission in (:permissions)
    //                            and (a.valid_until is null or a.valid_until > :now)
    //                         union all
    //                         select g.permission from user_permissions g
    //                          where g.user_id = u.id and g.permission in (:permissions) and g.effect = 'ALLOW'
    //                            and (g.valid_until is null or g.valid_until > :now)) held) = :needed
    //            and not exists (select 1 from user_permissions g
    //                             where g.user_id = u.id and g.permission in (:permissions) and g.effect = 'DENY'
    //                               and (g.valid_until is null or g.valid_until > :now))
    //         """;
    // roles:else
    private static final String HOLDERS = """
            select u.id, u.full_name from users u where u.status = 'ACTIVE' and u.role in (:roles)
            """;
    // roles:end

    @Autowired
    private PermissionHolders holders;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TestUser named(TestUser user, String fullName) {
        assertThat(sql("update users set full_name = ? where id = ?", fullName, user.id())).isEqualTo(1);
        return user;
    }

    private void suspend(TestUser user) {
        assertThat(sql("update users set status = 'SUSPENDED' where id = ?", user.id())).isEqualTo(1);
    }

    private static DropdownFilter matching(String query, DropdownCandidate.SortDirection direction, String after) {
        DropdownFilter filter = new DropdownFilter();
        filter.setQuery(query);
        filter.setSortDirection(direction);
        filter.setAfter(after);
        return filter;
    }

    private List<List<UUID>> idsOfEveryBatch(String query, DropdownCandidate.SortDirection direction) {
        List<List<UUID>> batches = new ArrayList<>();
        String after = null;
        do {
            CursorBatch<DropdownItem> batch = holders.findHolders(Permission.ACCOUNT_READ, matching(query, direction, after), 2);
            batches.add(batch.items().stream().map(item -> (UUID) item.value()).toList());
            after = batch.nextCursor();
        } while (Objects.nonNull(after));
        return batches;
    }

    @Test
    @DisplayName("An active account holds what its access grants now; a suspended one, or no account, holds nothing")
    void holdsIsTheAccountsAccessNow() throws Exception {
        TestUser admin = newAdmin();
        TestUser user = newUser();
        TestUser suspended = newAdmin();
        suspend(suspended);

        assertThat(holders.holds(admin.id(), Permission.ACCOUNT_READ)).isTrue();
        assertThat(holders.holds(user.id(), Permission.ACCOUNT_READ)).isFalse();
        assertThat(holders.holds(suspended.id(), Permission.ACCOUNT_READ)).isFalse();
        assertThat(holders.holds(UUID.randomUUID(), Permission.ACCOUNT_READ)).isFalse();
    }

    @Test
    @DisplayName("""
            The holders are the active accounts holding the permission, by full name then id, in batches either way; \
            query narrows them""")
    void findHoldersListsTheActiveHoldersInBatches() throws Exception {
        String prefix = "Holder " + UUID.randomUUID();
        TestUser ann = named(newAdmin(), prefix + " Ann");
        TestUser bob = named(newAdmin(), prefix + " Bob");
        TestUser bobToo = named(newAdmin(), prefix + " Bob");
        TestUser cid = named(newAdmin(), prefix + " Cid");
        named(newUser(), prefix + " Dee");
        suspend(named(newAdmin(), prefix + " Eve"));
        List<UUID> bobs = Stream.of(bob.id(), bobToo.id()).sorted(Comparator.comparing(UUID::toString)).toList();

        List<List<UUID>> ascending = idsOfEveryBatch(prefix.toUpperCase(), DropdownCandidate.SortDirection.ASC);
        List<List<UUID>> descending = idsOfEveryBatch(prefix, DropdownCandidate.SortDirection.DESC);

        assertThat(ascending).containsExactly(List.of(ann.id(), bobs.get(0)), List.of(bobs.get(1), cid.id()));
        assertThat(descending).containsExactly(List.of(cid.id(), bobs.get(1)), List.of(bobs.get(0), ann.id()));
        assertThat(holders.findHolders(Permission.ACCOUNT_READ, matching(prefix + " Ann", null, null), 10).items())
                .containsExactly(new DropdownItem(ann.id(), prefix + " Ann"));
    }

    static Stream<Arguments> batches() {
        return Stream.of(
                Arguments.of("first, by name", HOLDERS + "order by u.full_name, u.id limit 21", null),
                Arguments.of("after a cursor, by name",
                        HOLDERS + "and (u.full_name, u.id) > (:name, :id) order by u.full_name, u.id limit 21",
                        "ROW(full_name, id) > "),
                Arguments.of("after a cursor, by name descending",
                        HOLDERS + "and (u.full_name, u.id) < (:name, :id) order by u.full_name desc, u.id desc limit 21",
                        "ROW(full_name, id) < "));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("batches")
    @DisplayName("Each batch of the holders reads idx_users_full_name_id in order, a later one seeking its cursor, never a sequential scan")
    void eachBatchReadsTheNameIndex(String batch, String statement, String seek) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("name", "Ann").addValue("id", UUID.randomUUID());
        // roles:begin
        // parameters.addValue("permissions", List.of(Permission.ACCOUNT_READ.name())).addValue("needed", 1)
        //         .addValue("now", Timestamp.from(Instant.now()));
        // roles:else
        parameters.addValue("roles", List.of("ADMIN"));
        // roles:end

        String plan = QueryPlan.explain(jdbc, transactionManager, statement, parameters);

        assertThat(QueryPlan.indexExists(jdbc, "idx_users_full_name_id")).isTrue();
        assertThat(plan).as(batch).doesNotContain("Seq Scan on users");
        assertThat(QueryPlan.indexesScanned(plan)).as(plan).contains("idx_users_full_name_id");
        if (Objects.nonNull(seek)) {
            assertThat(plan.lines().filter(line -> line.contains("Index Cond")).toList()).as(plan)
                    .anySatisfy(condition -> assertThat(condition).contains(seek));
        }
    }
}
