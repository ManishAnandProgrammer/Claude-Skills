# Configuration baseline (CFG-xx)

Values are production values, each with its *why*; the reference application's
`application.yml` and `config/*.yml` carry them. A missing key, or a different value without a reason in the profile, is a
finding (default Medium). The profile can override a value with its reason.

Check these in whole-codebase mode, and in diff mode whenever configuration files change.

---

## CFG-01: Connection pool (HikariCP)

| Key | Value | Why |
|---|---|---|
| `spring.datasource.hikari.pool-name` | `<App>Pool` | Finds the pool in metrics and logs |
| `…hikari.maximum-pool-size` / `minimum-idle` | set per profile (prod 30 / 10) | Never the default 10 |
| `…hikari.connection-timeout` | `3000` | Fail fast. Admission control is the queue (PERF-03); this is the backstop |
| `…hikari.validation-timeout` | `1000` | |
| `…hikari.keepalive-time` | `30000` | Finds connections that were dropped |
| `…hikari.idle-timeout` / `max-lifetime` | `120000` / `1800000` | |
| `…hikari.leak-detection-threshold` | `30000` | Logs any connection held longer than a transaction should be |
| `…hikari.transaction-isolation` | `TRANSACTION_READ_COMMITTED` | Set once on the pool, never per method (DATA-20/21) |
| `…hikari.auto-commit` | `false` | **Together with** `hibernate.connection.provider_disables_autocommit=true` and Spring's `LazyConnectionDataSourceProxy` around the pool (reference `config/LazyConnectionConfig`): the connection is borrowed at the first SQL statement, not at transaction begin. Without the proxy, **read-only** transactions still borrow at begin: Spring's `HibernateJpaDialect` takes the connection then to call `setReadOnly` (measured 2026-11-14: active 1 before any SQL; with the proxy 0, and PostgreSQL still runs them read-only). Missing either setting, or the proxy, is a finding. Code that must hold a real connection without a statement (a pool-exhaustion test) borrows from the unwrapped `HikariDataSource` |
| `…data-source-properties.options` | `-c statement_timeout=25s -c lock_timeout=3s -c idle_in_transaction_session_timeout=30s` | Server-side limits on the **app pool only**. Liquibase builds its own connection and sets its own `lock_timeout` (about 5 s, no `statement_timeout`). DATA-19 |
| `…data-source-properties.socketTimeout` | `30` | Must be **greater** than `statement_timeout`, so the server cancels first and the connection survives (PA-16) |
| `…data-source-properties.reWriteBatchedInserts` | `true` | One round trip per insert batch |
| `…data-source-properties.tcpKeepAlive` | `true` | Finds connections a NAT or load balancer dropped |
| `…data-source-properties.ApplicationName` | `<app>-<env>` | Finds the app's sessions in `pg_stat_activity` |
| `…data-source-properties.logServerErrorDetail` | `false` | pgjdbc otherwise copies the server's `DETAIL` (`Key (email)=(…) already exists`) and a failed batch's **bound values** (`Batch entry 0 insert into … values ('ann@…', …)`) into the exception message, which Hibernate logs at WARN (`org.hibernate.orm.jdbc.error`, kept by a prod `root: WARN`): every refused write, an ordinary 409 included, writes the row's personal data to the logs (SEC-04, High). The constraint name the error mapping reads stays in the message. Liquibase's own data source sets it too (`setLogServerErrorDetail(false)`, reference `config/LiquibaseConnectionConfig`). Test: reference `RefusedWriteLogIntegrationTest` (a refused statement and a refused batch log the constraint, never the value) |

MySQL-only driver properties (`cachePrepStmts`, `useServerPrepStmts`,
`rewriteBatchedStatements`) on PostgreSQL are a finding: they do nothing.

## CFG-02: JPA / Hibernate

| Key | Value | Why |
|---|---|---|
| `spring.jpa.open-in-view` | `false` | The session closes with the transaction |
| `spring.jpa.hibernate.ddl-auto` | `validate` | `none` let an unmigrated schema go unnoticed (LIQUIBASE-URL-HARDCODED-LOCALHOST) |
| `spring.jpa.show-sql` | `false` | SQL logging through datasource-proxy in dev only |
| `hibernate.connection.provider_disables_autocommit` | `true` | See auto-commit |
| `hibernate.jdbc.time_zone` | `UTC` | |
| `hibernate.jdbc.batch_size` | `30` | With `order_inserts` / `order_updates=true`: batching, and a consistent lock order (no deadlocks across concurrent multi-row writes) |
| `hibernate.default_batch_fetch_size` | `30` | Removes N+1 queries on lazy collections (measured) |
| `hibernate.query.fail_on_pagination_over_collection_fetch` | `true` | A guard only: Hibernate 7.4 pages collection fetches in SQL and never trips it. The rule that matters is DATA-26 (no collection fetch on list pages) |
| `hibernate.query.in_clause_parameter_padding` | `true` | Fewer distinct `IN` statements for the plan cache |
| JSON format mapper | configured explicitly when any `SqlTypes.JSON` attribute exists | DATA-27 |

Not findings: `hibernate.jdbc.fetch_size` unset (measured: no benefit with keyset pages); the
query plan cache size left at its default.

## CFG-03: Database roles and migrations
- Liquibase migrates the database the app uses, over its own connection: a `@LiquibaseDataSource`
  built from the app's connection details with `lock_timeout` (`app.liquibase.lock-timeout`, about
  5 s) and no statement timeout (DATA-19). Reference: `config/LiquibaseConnectionConfig`. Never a
  second, hand-written URL (LIQUIBASE-URL-HARDCODED-LOCALHOST).
- *Profile-optional (required with RLS):* the app connects as a non-owner, DML-only role; only
  Liquibase uses the owner role.
- Server settings (RDS parameter group): `log_lock_waits=on`, `deadlock_timeout=1s`.
- **Maintenance** (*profile-optional where the database is managed and the provider covers it,
  e.g. RDS; say what the provider does*):
  - autovacuum stays on (the default; never off for a table "to save load"), with
    `log_autovacuum_min_duration` set (e.g. `10s`), so slow vacuums show in the log;
  - an alert on `age(datfrozenxid)` above about 1 billion (wraparound stops writes at about 2
    billion, and recovery is a manual `VACUUM FREEZE`);
  - dead tuples on the hot tables watched (`pg_stat_user_tables.n_dead_tup`); a long `idle in
    transaction` session (which blocks vacuum) is already cut by
    `idle_in_transaction_session_timeout` (CFG-01).

## CFG-04: Server, threads and overload
| Key | Value | Why |
|---|---|---|
| `spring.threads.virtual.enabled` | `true` | Then `server.tomcat.threads.max` bounds **nothing** (REL-07): concurrency is bounded by admission control (PERF-03) |
| `server.shutdown` / `spring.lifecycle.timeout-per-shutdown-phase` | `graceful` / `30s` | Longer than a normal request (pool wait 3 s + statement timeout 25 s), so a drain doesn't cut requests that would have finished; the container's stop grace is longer still (REL-13) |
| `server.compression.*` | enabled with `min-response-size=1KB` and JSON mime types, **or** documented as done by the edge proxy | Off in prod when Nginx compresses |
| `server.tomcat.connection-timeout` | `5s` | |
| `server.tomcat.max-connections` / `accept-count` / `keep-alive-timeout` / `max-keep-alive-requests` | set for prod (5000 / 1000 / 15s / 1000) | Don't lower `max-connections` to "cap load": it counts idle keep-alive sockets |
| `server.tomcat.max-http-form-post-size`, `max-swallow-size`, `max-http-response-header-size`, multipart `max-file-size` / `max-request-size` | set | Bounded input |
| Scheduler | a `TaskScheduler` bean with N named platform threads, `waitForTasksToCompleteOnShutdown`, `awaitTerminationSeconds` | `spring.task.scheduling.pool.size` is ignored under virtual threads (REL-08) |
| `spring.task.execution.mode` | `force` (whenever a `TaskScheduler` bean exists) | A scheduler is an `Executor`, so Boot otherwise skips its `applicationTaskExecutor`, and the MDC `TaskDecorator` with it (verified 2026-11-13) |
| `spring.task.execution.thread-name-prefix` | set | Readable thread dumps |
| Admission control | PERF-03 | Never the thread-pool bulkhead: it undoes virtual threads |

## CFG-05: Jackson (strict input)
```
spring.jackson.deserialization.fail-on-unknown-properties=true
spring.jackson.deserialization.fail-on-invalid-subtype=true
spring.jackson.deserialization.accept-float-as-int=false
spring.jackson.deserialization.wrap-exceptions=true
spring.jackson.mapper.allow-coercion-of-scalars=false
spring.jackson.mapper.accept-case-insensitive-enums=false
```
Why: a misspelt field is a 400 `REQUEST_UNREADABLE`, not silently ignored (it was how six tests
"passed" with the wrong field name, SETUP-THAT-SILENTLY-FAILED). Stored JSON read back by
Hibernate is the exception: tolerant, DATA-27.

## CFG-06: Actuator, health and metrics
The keys; the rules behind them are REL-14 (health, probes, management surface) and SEC-03 (the
scrape is secured, `{{metricsAuth}}`).
- `management.server.port` set (e.g. `8081`).
- Production: `management.endpoint.health.show-details=never`; exposure `health,metrics,prometheus`
  only.
- `management.endpoint.health.probes.enabled=true`; the readiness group is `readinessState` plus a
  **pool-queue indicator** reading Hikari's `threadsAwaitingConnection` from memory
  (OUT_OF_SERVICE when the queue is at least the pool size; reference:
  `config/ConnectionPoolHealthIndicator`), never Boot's `db` indicator, which borrows a connection.
  Optional dependencies (Redis, mail, AI, Resilience4j) have their health indicators off.
- Metrics:
  - `management.metrics.tags.application=<app>`;
  - percentiles `0.5,0.95,0.99` **and** `percentiles-histogram=true` for `http.server.requests`,
    with the histogram turned off for `http.server.requests.active`;
  - histograms for `hikaricp.connections.acquire` **and** `hikaricp.connections.usage` (wait vs
    hold);
  - `jvm.gc.pause`;
  - every custom hot-path timer.

## CFG-07: Logging (house pattern)
`logback-spring.xml`:
- **Context on every line** (MDC, filled by a request-context filter and by schedulers and async
  tasks): `traceId`, `spanId` (Micrometer Tracing), `requestId` (echoed as `X-Request-ID`),
  `endpoint` (method + path), `clientIp`, `userId`, plus `tenantId` when multi-tenant.
  ```
  [%X{traceId:-},%X{spanId:-}] [%X{requestId:-}] [%X{endpoint:-}] [%X{clientIp:-}] [%X{userId:-}]
  ```
- **Client IP is configurable:** `app.logging.client-ip: raw | truncated | hashed | none`, with
  the reason documented next to the setting.
- **Asynchronous appender everywhere** (a load test found request threads queued on the console
  lock): `queueSize=65536`, `discardingThreshold=0`, `includeCallerData=false`, and no
  `%class` / `%method` / `%line` in patterns.
- **Per profile:**
  - local/dev: level-first text;
  - staging/loadtest: timestamp-first text;
  - prod: **JSON** (`LogstashEncoder`, `includeMdcKeyName` for every context key, `customFields`
    app and env).
  - Exactly one root and one appender per profile, plus a fallback console appender for when no
    profile is active (so the fail-fast startup error is visible). Not declared at top level, or
    every line is written twice.
- **Hibernate's SQL error lines off:** `logging.level.org.hibernate.orm.jdbc.error: ERROR` (reference
  `config/db.yml`): Hibernate logs every refused statement at WARN twice, so every ordinary 409 wrote two
  lines with the driver's message; the handler logs what is worth a line (ERR-02). A WARN that prints a
  database exception's message or `toString()` (a 503's cause) is a finding: the class and SQL state only.
- **Request completion line:** one line per request ("Request done: status, durationMs"), WARN
  past a configured slow threshold. Switched by `app.logging.request-completion.enabled` and
  `…slow-threshold`. In prod the app logger level is WARN except this filter.

## CFG-08: JVM flags (container)
- `-XX:+ExitOnOutOfMemoryError`
- `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=<private volume>`: heap dumps are secrets
  (SEC-04).
- GC log with rotation (`-Xlog:gc*:file=…:time`).
- Heap bounded against the container limit with headroom (`-Xmx` or `MaxRAMPercentage` about
  65–75%; RSS is about heap × 1.3–1.5).
- The image keeps `jcmd` / JFR (a JDK, or jlink with `jdk.jcmd` and `jdk.jfr`), or the profile
  accepts the gap.
- No container definition in the repository at all is itself the finding (the flags can't be
  reviewed): a Dockerfile, or `build-image` with the flags recorded.
- Unique node id per instance when ids need one (TSID).

## CFG-09: merged into REL-01, REL-04 and OPS-06

## CFG-10: Configuration layout and startup checks
- `application.yml` only names the app and imports **one file per concern**:
  `config/{db,server,security,observability,limits,jobs}.yml`, each with profile variants beside it
  (`db-prod.yml`, `server-dev.yml`). Spring Boot applies a `<name>-<profile>.yml` variant of an
  imported file on top of it (verified 2026-11-13).
- Every tunable is in configuration (SMELL-17); `spring.application.name` is the one name the rest
  derives from.
- No default profile; **startup fails** on a missing or unknown active profile (an allow-list),
  on missing secrets, and on inert settings (admission below twice the pool size).
- API docs are off in the base configuration and on only in `dev` (a forgotten profile must not
  publish the schema).
- The prod pool is set explicitly (e.g. 30/10), never the default 10.
- **Severity:** Medium (High when a missing profile exposes docs or development defaults).
