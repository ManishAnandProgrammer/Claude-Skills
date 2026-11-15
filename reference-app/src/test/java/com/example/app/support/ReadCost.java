package com.example.app.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a statement reads, measured by running it: the rows every table scan of its plan handed on, plus
 * the rows its filter threw away, times the scan's loops. Index tests bound this number instead of
 * asserting a plan's shape (an index name, no sort step): which plan wins turns on the statistics, so a
 * shape test passes alone and fails in a full run, while the rows a statement reads only grow when the
 * table holds other classes' rows too, and only for a plan the index doesn't serve (crud-gen tests.md
 * row 28). Beside the bound, a test asserts that the index meant for the cell exists ({@link #indexExists}):
 * an index that filters inside itself (a composite key {@code (id, owner_id)} walked for the owner's
 * newest) reads few rows on a test database's few thousand, and only the missing index's name tells.
 *
 * <p>{@code EXPLAIN ANALYZE} executes the statement, so it runs in a transaction that is rolled back: a
 * lease's {@code UPDATE} changes nothing. Whole-table and bitmap scans are switched off, as a missing
 * index is read whole either way, while on a test database's few rows the planner may rightly prefer a
 * sequential scan to a perfectly good index.</p>
 */
public record ReadCost(long rowsRead, String plan) {

    private static final Pattern TABLE_SCAN = Pattern.compile(
            "(?:Seq|Index|Index Only|Bitmap Heap|Tid|Tid Range) Scan.*\\(actual rows=([0-9.]+) loops=([0-9]+)\\)");
    private static final Pattern PLAN_NODE = Pattern.compile("\\((?:actual rows=|never executed)");
    private static final Pattern ROWS_REMOVED = Pattern.compile("Rows Removed by (?:Filter|Index Recheck): ([0-9]+)");

    /**
     * The index exists, under that name: a check of the schema, which no statistics can turn. An index
     * test asserts it for the index each cell is meant to read, beside the cell's bound.
     */
    public static boolean indexExists(JdbcTemplate jdbc, String index) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, index));
    }

    /** Runs {@code statement} once under EXPLAIN ANALYZE and adds up what its table scans read. */
    public static ReadCost of(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, String statement,
                              MapSqlParameterSource parameters) {
        List<String> planLines = new TransactionTemplate(transactionManager).execute(transaction -> {
            transaction.setRollbackOnly();
            jdbc.execute("set local enable_seqscan = off");
            jdbc.execute("set local enable_bitmapscan = off");
            return new NamedParameterJdbcTemplate(jdbc).queryForList(
                    "explain (analyze, costs off, timing off, summary off, buffers off) " + statement, parameters, String.class);
        });
        long rowsRead = 0;
        // EXPLAIN reports rows and removed rows per loop: both count once per loop of their scan.
        long loopsOfTheScan = 0;
        for (String line : planLines) {
            Matcher scan = TABLE_SCAN.matcher(line);
            Matcher removed = ROWS_REMOVED.matcher(line);
            if (scan.find()) {
                loopsOfTheScan = Long.parseLong(scan.group(2));
                rowsRead += Math.round(Double.parseDouble(scan.group(1)) * loopsOfTheScan);
            } else if (PLAN_NODE.matcher(line).find()) {
                loopsOfTheScan = 0;
            } else if (removed.find()) {
                rowsRead += Long.parseLong(removed.group(1)) * loopsOfTheScan;
            }
        }
        return new ReadCost(rowsRead, String.join("\n", planLines));
    }
}
