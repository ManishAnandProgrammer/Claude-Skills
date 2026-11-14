# Incident runbook

For a slow or failing API. `<app>` is `spring.application.name`; the app's database sessions are
named `<app>-<APP_ENV>`, a migration's `<app>-liquibase`. Work top to bottom and write down what you
see (time, query output, file names) as you go.

**Never "bump the pool" as a first fix.** A bigger pool lets more requests pile onto a database that
is already the bottleneck: more connections, more lock waits, the same slow query running more times.
First find what holds the connections (steps 1 and 2).

## 1. What is the application telling you?

Management port only (`MANAGEMENT_PORT`, 8081; never published): `curl -s localhost:8081/actuator/health/readiness`,
`/actuator/metrics/<name>`, `/actuator/prometheus`.

| Signal | Meaning |
|---|---|
| readiness `OUT_OF_SERVICE` | the pool's queue is full (`connectionPool`); the load balancer sends traffic elsewhere |
| `hikaricp_connections_pending` > 0 for minutes, `hikaricp_connections_active` = `_max` | requests wait for a connection: something holds connections too long |
| `hikaricp_connections_usage_seconds` p99 high | connections are held long: slow queries or long transactions (step 2) |
| `hikaricp_connections_acquire_seconds` p99 high, `hikaricp_connections_timeout_total` rising | requests give up after 3 s: 503 `DATABASE_UNAVAILABLE` |
| `resilience4j_bulkhead_available_concurrent_calls{name="api"}` (or `"sign-in"`) at 0; log `Refusing requests at the door` | admission control sheds load: 503 `SERVER_BUSY`. It protects the pool; it is a symptom, not the cause |
| 503 `DATABASE_CONTENTION` in logs | a write waited past `lock_timeout` (3 s): find the blocker (step 2) |
| 503 `QUERY_TIMEOUT` in logs (ERROR with stack) | a statement ran past `statement_timeout` (25 s): that query is the suspect |
| `http_server_requests_seconds` by `uri`; `http_server_requests_seconds_count{status="503"}` by `uri` | which endpoint is slow, and where the 503s are (the log line's `errorCode` says why) |

## 2. What is the database doing?

Run as a role that sees every session (`pg_monitor` or the owner).

Connections in use against the server's maximum (every instance's pool counts):
```sql
SELECT count(*) AS connections, current_setting('max_connections') AS max_connections,
       count(*) FILTER (WHERE application_name LIKE '<app>-%') AS this_app
FROM pg_stat_activity;
```

Running for longest:
```sql
SELECT pid, application_name, state, now() - query_start AS running_for,
       wait_event_type, wait_event, left(query, 200) AS query
FROM pg_stat_activity
WHERE state <> 'idle' AND pid <> pg_backend_pid()
ORDER BY query_start
LIMIT 20;
```

Idle in a transaction (holds its locks and an old snapshot; the app's own end after 30 s,
`idle_in_transaction_session_timeout`, so an older one is a console, a script or another service):
```sql
SELECT pid, application_name, usename, now() - xact_start AS in_transaction_for,
       now() - state_change AS idle_for, left(query, 200) AS last_query
FROM pg_stat_activity
WHERE state IN ('idle in transaction', 'idle in transaction (aborted)')
ORDER BY xact_start;
```

Who blocks whom (the session at the head of a chain is blocking but not blocked itself):
```sql
SELECT blocked.pid AS blocked_pid, now() - blocked.query_start AS waiting_for,
       left(blocked.query, 120) AS blocked_query,
       blocking.pid AS blocking_pid, blocking.application_name AS blocking_app, blocking.state AS blocking_state,
       left(blocking.query, 120) AS blocking_query
FROM pg_stat_activity AS blocked
CROSS JOIN LATERAL unnest(pg_blocking_pids(blocked.pid)) AS blocker(pid)
JOIN pg_stat_activity AS blocking ON blocking.pid = blocker.pid
ORDER BY waiting_for DESC;
```
A migration (`<app>-liquibase`) waiting for a lock fails itself after 5 s (`app.liquibase.lock-timeout`);
while it waits, every later query on that table queues behind it.

Stop a session, gentlest first: `SELECT pg_cancel_backend(<pid>);` cancels its query;
`SELECT pg_terminate_backend(<pid>);` ends the session and rolls its transaction back.

Transaction-id age (wraparound). PostgreSQL stops accepting writes near 2 billion; autovacuum freezes
aggressively from 200 million (`autovacuum_freeze_max_age`). Above about 1 billion, act now:
```sql
SELECT datname, age(datfrozenxid) AS xid_age FROM pg_database ORDER BY xid_age DESC;

SELECT c.relname, age(c.relfrozenxid) AS xid_age
FROM pg_class AS c
JOIN pg_namespace AS n ON n.oid = c.relnamespace
WHERE c.relkind IN ('r', 'm', 't') AND n.nspname NOT IN ('pg_catalog', 'information_schema')
ORDER BY xid_age DESC
LIMIT 10;
```

Dead rows (bloat, slow scans):
```sql
SELECT relname, n_live_tup, n_dead_tup,
       round(100.0 * n_dead_tup / greatest(n_live_tup + n_dead_tup, 1), 1) AS dead_percent,
       last_autovacuum, last_autoanalyze
FROM pg_stat_user_tables
ORDER BY n_dead_tup DESC
LIMIT 10;
```
A high share with an old `last_autovacuum`, or a rising xid age, usually means something holds the
oldest snapshot, and vacuum can remove nothing newer than it:
```sql
SELECT pid, application_name, state, now() - xact_start AS open_for, age(backend_xmin) AS xmin_age
FROM pg_stat_activity WHERE backend_xmin IS NOT NULL ORDER BY age(backend_xmin) DESC LIMIT 5;
SELECT slot_name, active, xmin, catalog_xmin FROM pg_replication_slots;
SELECT gid, prepared, owner FROM pg_prepared_xacts;
```
End that holder, then `VACUUM (VERBOSE, ANALYZE) <table>;` (online). Never `VACUUM FULL` during an
incident: it locks the table exclusively while it rewrites it.

## 3. What is the JVM doing?

Requests run on virtual threads: `jstack` and `jcmd <pid> Thread.print` show only the platform
(carrier) threads and miss them. Take a full dump, two or three a few seconds apart (the JVM is PID 1
in the container; `/diagnostics` is the private volume, and dumps can show data):
```bash
docker exec <container> jcmd 1 Thread.dump_to_file -format=json /diagnostics/threads-$(date -u +%H%M%S).json
```
Many threads in `HikariPool.getConnection`: waiting for the pool (step 2). In the driver's socket read
(`PgStream`, `SocketInputStream`): waiting for the database. In one of our methods: that is the hot spot.

A recording for a slow, not stuck, application:
`docker exec <container> jcmd 1 JFR.start duration=60s settings=profile filename=/diagnostics/incident.jfr`.
GC logs and out-of-memory heap dumps are already in `/diagnostics/<start>/`.

## 4. Mitigate

1. **Remove the cause**: cancel the runaway query or end the blocking or idle-in-transaction session
   (step 2).
2. **A bad release**: roll back to the previous image. That is safe when the release's changesets only
   added (tables, nullable columns, indexes): the previous code ignores them. When one removed or
   renamed something the previous code uses, fix forward instead. Don't run a Liquibase rollback
   during the incident: a rollback that drops a table or column drops its data.
3. **A failed migration**: the deployment stops at startup (`canceling statement due to lock timeout`).
   Find the blocker (step 2), let it finish or end it, then deploy again. If no instance is migrating
   and `SELECT locked, lockedby FROM databasechangeloglock;` still says locked (a migrating instance
   was killed), release it: `UPDATE databasechangeloglock SET locked = false, lockgranted = NULL, lockedby = NULL WHERE id = 1;`.
4. **Take an instance out**: stop it normally (`docker stop`, SIGTERM). It drains for up to 30 s
   (`spring.lifecycle.timeout-per-shutdown-phase`); its stop grace period must be longer (40 s). Never
   `kill -9` a serving instance: its requests and transactions are cut mid-way.
5. **More load than capacity** with a healthy database: admission control already answers 503
   `SERVER_BUSY` with `Retry-After`. Add instances. Raise `DB_POOL_SIZE` only when step 2 shows the
   database idle while requests wait for the pool, and only together with `ADMISSION_MAX_CONCURRENT`
   (at least twice the pool, checked at startup), keeping pool size × instances well under
   `max_connections` (`SHOW max_connections;`, `SELECT count(*) FROM pg_stat_activity;`).

## 5. Afterwards

Keep the notes, the query output and the files in `/diagnostics`. Write down the cause, what fixed
it, and the guard (a test, a limit, an alert) that would have caught it earlier.
