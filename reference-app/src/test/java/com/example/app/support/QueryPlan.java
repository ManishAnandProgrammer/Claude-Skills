package com.example.app.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The schema side of index tests (crud-gen tests.md row 28): every offered filter and order has its index
 * ({@link #indexExists}), and a list's first page is planned on the index meant for it
 * ({@link #indexesScanned} of {@link #explain}), never a sequential scan. Plan nodes and index names only,
 * never a cost or a row count: those turn on the data, and a project that knows its data distribution or
 * has a latency target adds its own {@code EXPLAIN (ANALYZE, BUFFERS)} tests.
 */
public final class QueryPlan {

    private static final Pattern SCAN_USING = Pattern.compile("Scan(?: Backward)? using (\\S+) on ");

    private QueryPlan() {
    }

    /** The index exists under that name: a check of the schema, which no statistics can turn. */
    public static boolean indexExists(JdbcTemplate jdbc, String index) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, index));
    }

    /**
     * The plan of {@code statement} under {@code EXPLAIN} (planned, not run), in a transaction rolled back.
     * Sequential scans, bitmap scans and sorts are off: on a test database's small tables, and for a scope
     * id the statistics put at a row or two, reading the table whole or sorting a few rows from any index is
     * rightly cheaper, and the plan would turn on the data. With them off the planner shows the index that
     * serves the statement in its order, and a missing index shows as another one or a disabled node.
     */
    public static String explain(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, String statement,
                                 MapSqlParameterSource parameters) {
        List<String> planLines = new TransactionTemplate(transactionManager).execute(transaction -> {
            transaction.setRollbackOnly();
            jdbc.execute("set local enable_seqscan = off");
            jdbc.execute("set local enable_bitmapscan = off");
            jdbc.execute("set local enable_sort = off");
            return new NamedParameterJdbcTemplate(jdbc).queryForList("explain (costs off) " + statement, parameters, String.class);
        });
        return String.join("\n", planLines);
    }

    /** The indexes the plan's index scans read, in plan order. */
    public static List<String> indexesScanned(String plan) {
        List<String> indexes = new ArrayList<>();
        Matcher scan = SCAN_USING.matcher(plan);
        while (scan.find()) {
            indexes.add(scan.group(1));
        }
        return indexes;
    }
}
