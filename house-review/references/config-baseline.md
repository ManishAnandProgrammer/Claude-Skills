# Configuration baseline (CFG-xx)

Source: DynamicForm `application.properties`, `application-prod.properties` and
`logback-spring.xml`, plus the reviews behind them. Values are the user's production values; the
*why* is theirs. A missing key, or a different value without a reason in the profile, is a
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
| `…hikari.auto-commit` | `false` | **Together with** `hibernate.connection.provider_disables_autocommit=true`: the connection is borrowed at the first SQL statement, not at transaction begin. One without the other is a finding |
| `…data-source-properties.options` | `-c statement_timeout=25s -c lock_timeout=3s -c idle_in_transaction_session_timeout=30s` | Server-side limits on the **app pool only** (Liquibase builds its own connection). DATA-19 |
| `…data-source-properties.socketTimeout` | `30` | Must be **greater** than `statement_timeout`, so the server cancels first and the connection survives (PA-16) |
| `…data-source-properties.reWriteBatchedInserts` | `true` | One round trip per insert batch |
| `…data-source-properties.tcpKeepAlive` | `true` | Finds connections a NAT or load balancer dropped |
| `…data-source-properties.ApplicationName` | `<app>-<env>` | Finds the app's sessions in `pg_stat_activity` |

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
- Liquibase uses `spring.liquibase.url=${spring.datasource.url}` (it migrates the database the
  app uses).
- *Profile-optional (required with RLS):* the app connects as a non-owner, DML-only role; only
  Liquibase uses the owner role.
- Server settings (RDS parameter group): `log_lock_waits=on`, `deadlock_timeout=1s`.

## CFG-04: Server, threads and overload
| Key | Value | Why |
|---|---|---|
| `spring.threads.virtual.enabled` | `true` | Then `server.tomcat.threads.max` bounds **nothing** (REL-07): concurrency is bounded by admission control (PERF-03) |
| `server.shutdown` / `spring.lifecycle.timeout-per-shutdown-phase` | `graceful` / `10s`+ | REL-13 |
| `server.compression.*` | enabled with `min-response-size=1KB` and JSON mime types, **or** documented as done by the edge proxy | DynamicForm prod turns it off because Nginx compresses |
| `server.tomcat.connection-timeout` | `5s` | |
| `server.tomcat.max-connections` / `accept-count` / `keep-alive-timeout` / `max-keep-alive-requests` | set for prod (5000 / 1000 / 15s / 1000) | Don't lower `max-connections` to "cap load": it counts idle keep-alive sockets |
| `server.tomcat.max-http-form-post-size`, `max-swallow-size`, `max-http-response-header-size`, multipart `max-file-size` / `max-request-size` | set | Bounded input |
| Scheduler | a `TaskScheduler` bean with N named platform threads, `waitForTasksToCompleteOnShutdown`, `awaitTerminationSeconds` | `spring.task.scheduling.pool.size` is ignored under virtual threads (REL-08) |
| `spring.task.execution.mode` | `force` (whenever a `TaskScheduler` bean exists) | A scheduler is an `Executor`, so Boot otherwise skips its `applicationTaskExecutor`, and the MDC `TaskDecorator` with it (verified 2026-11-13) |
| `spring.lifecycle.timeout-per-shutdown-phase` | longer than the longest request (pool wait + statement timeout) | A shorter one cuts requests that would have finished |
| `spring.task.execution.thread-name-prefix` | set | Readable thread dumps |
| Admission control | a Resilience4j semaphore bulkhead per route group, about 2× the pool for request groups, small for reports and exports, short max-wait, then 503 `SERVER_BUSY` + `Retry-After`; never the thread-pool bulkhead (it undoes virtual threads); runs before the security chain; the actuator is never capped | PERF-03 |

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
- `management.server.port` set (e.g. `8081`): health, probes and scraping never wait for the
  application connector or the pool.
- **Health and probes are unauthenticated** (the load balancer needs them). **The metrics scrape
  is secured** (`{{metricsAuth}}`, mechanism pending the user's decision). The management port is
  never published publicly. Production: `show-details=never`; exposure `health,metrics,prometheus`
  only.
- `management.endpoint.health.probes.enabled=true`:
  - liveness: no external dependencies;
  - readiness: `readinessState` plus a **pool-queue indicator** that reads Hikari's
    `threadsAwaitingConnection` from memory (OUT_OF_SERVICE when the queue is at least the pool
    size), never Boot's `db` indicator, which borrows a connection and waits in the same queue.
- Optional dependencies (Redis, mail, AI, Resilience4j) are never in the health contributors that
  probes use.
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
- **Request completion line:** one line per request ("Request done: status, durationMs"), WARN
  past a configured slow threshold. Switched by `app.logging.request-completion.enabled` and
  `…slow-threshold`. In prod the app logger level is WARN except this filter.

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

## CFG-09: Outbound clients
Every outbound client sets a connect and a request timeout explicitly, never the library default.
SDKs set `apiCallTimeout` greater than `apiCallAttemptTimeout`. HTTP clients are built from Boot's
injected builders, so they get observation and trace propagation (OPS-06). Retries are configured
in the resilience library's YAML, within the deadline budget (REL-04).
