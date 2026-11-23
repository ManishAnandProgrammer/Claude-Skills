# Configuration baseline (CFG-xx)

Values are production values, each with its *why*; the reference application's
`application.yml` and `config/*.yml` carry them. A missing key, or a different value without a reason in the profile, is a
finding (default Medium). The profile can override a value with its reason.

Check these in whole-codebase mode, and in diff mode whenever configuration files change or a settings record does
(a `@ConfigurationProperties` record, a job's `Settings`, the records they hold: CFG-10 and CFG-12).

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
| `…hikari.auto-commit` | `false` | **Together with** `hibernate.connection.provider_disables_autocommit=true` and Spring's `LazyConnectionDataSourceProxy` around the pool (reference `config/LazyConnectionConfig`): the connection is borrowed at the first SQL statement, not at transaction begin. Without the proxy, **read-only** transactions still borrow at begin: Spring's `HibernateJpaDialect` takes the connection then to call `setReadOnly` (with the proxy, PostgreSQL still runs them read-only). Missing either setting, or the proxy, is a finding. Code that must hold a real connection without a statement (a pool-exhaustion test) borrows from the unwrapped `HikariDataSource` |
| `…data-source-properties.options` | `-c statement_timeout=25s -c lock_timeout=3s -c idle_in_transaction_session_timeout=30s` | Server-side limits on the **app pool only**. Liquibase builds its own connection and sets its own `lock_timeout` (about 5 s, no `statement_timeout`). DATA-19 |
| `…data-source-properties.socketTimeout` | `30` | Must be **greater** than `statement_timeout`, so the server cancels first and the connection survives (DATA-19) |
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
  - dead tuples watched per table (`pg_stat_user_tables.n_dead_tup`); a long `idle in
    transaction` session (which blocks vacuum) is already cut by
    `idle_in_transaction_session_timeout` (CFG-01).

## CFG-04: Server, threads and overload
| Key | Value | Why |
|---|---|---|
| `spring.threads.virtual.enabled` | `false`, set explicitly (owner decision) | Every request waits on the connection pool, and admission caps the requests in flight far below Tomcat's threads, so virtual threads buy nothing here; on, they make `server.tomcat.threads.max` inert (REL-07). Virtual threads for one piece of work (an outbound call fan-out) are that code's own executor, bounded (REL-09) |
| `server.tomcat.threads.max` | `${TOMCAT_MAX_THREADS:100}`, above the sum of every admission group's cap plus headroom (checked at startup) | A refused request still needs a thread for its 503, and the actuator is never capped; at or below the caps, admission never refuses and the excess waits unseen in `accept-count` (REL-07) |
| `server.shutdown` / `spring.lifecycle.timeout-per-shutdown-phase` | `graceful` / `30s` | Longer than a normal request (pool wait 3 s + statement timeout 25 s), so a drain doesn't cut requests that would have finished; the container's stop grace is longer still (REL-13) |
| `server.compression.*` | enabled with `min-response-size=1KB` and JSON mime types, **or** documented as done by the edge proxy | Off in prod when Nginx compresses |
| `server.tomcat.connection-timeout` | `5s` | |
| `server.tomcat.max-connections` / `accept-count` / `keep-alive-timeout` / `max-keep-alive-requests` | set for prod (5000 / 1000 / 15s / 1000) | Don't lower `max-connections` to "cap load": it counts idle keep-alive sockets |
| `server.tomcat.max-http-form-post-size`, `max-swallow-size`, `max-http-response-header-size` | set | Bounded input. These bound forms and headers only: Jackson reads a whole JSON body into memory before validation, and nothing in Boot caps it. The reverse proxy's body limit is the bound (Nginx `client_max_body_size`, a deployment setting); an in-app cap (a declared `Content-Length` check plus a counting stream for chunked bodies, 413) is a project's own filter |
| `spring.servlet.multipart.enabled` | `false` where no endpoint takes a file (the house uploads go to storage directly, MEDIA-01); else `max-file-size` / `max-request-size` set | The resolver parses every part of a multipart body before any filter or wrapper sees it |
| `spring.mvc.formcontent.filter.enabled` | `false` where no endpoint reads form parameters | Boot's `FormContentFilter` (order -9900, on by default) reads a PUT, PATCH or DELETE form body whole into memory ahead of admission control, the security chain and any body cap. Reference: `ConfigurationBaselineIntegrationTest.noBodyIsReadAheadOfTheFilters` |
| Scheduler | a `TaskScheduler` bean with N named threads, `waitForTasksToCompleteOnShutdown`, `awaitTerminationSeconds` | One hung job can't stall the others, and shutdown waits for a running job (REL-08) |
| `spring.task.execution.mode` | `force` (whenever a `TaskScheduler` bean exists) | A scheduler is an `Executor`, so Boot otherwise skips its `applicationTaskExecutor`, and the MDC `TaskDecorator` with it |
| `spring.task.execution.thread-name-prefix` | set | Readable thread dumps |
| Admission control | PERF-03 | A semaphore bulkhead, never the thread-pool bulkhead: that adds a second pool and a hand-off in front of Tomcat's |

## CFG-05: Jackson (strict input)
```
spring.jackson.deserialization.fail-on-unknown-properties=true
spring.jackson.deserialization.fail-on-invalid-subtype=true
spring.jackson.deserialization.accept-float-as-int=false
spring.jackson.deserialization.wrap-exceptions=true
spring.jackson.mapper.allow-coercion-of-scalars=false
spring.jackson.mapper.accept-case-insensitive-enums=false
spring.jackson.default-leniency=false
spring.jackson.datatype.enum.fail-on-numbers-for-enums=true
```
Why: a misspelt field is a 400 `REQUEST_UNREADABLE`, not silently ignored (it was how six tests
"passed" with the wrong field name, SETUP-THAT-SILENTLY-FAILED), and a value is read only in its
documented form (REST-22). Without `default-leniency=false` Jackson takes the day out of a date-time
sent for a `LocalDate`: `"2026-04-01T18:30:00Z"`, 2 April east of UTC, is stored as 1 April behind a
201. Without the enum key `0` is read as the first constant. Guarded by the reference's
`StrictJsonInputIntegrationTest` (one field per type, on the application's mapper). Settings only, never
a custom deserializer for a standard type. Stored JSON read back by Hibernate is the exception:
tolerant, DATA-27.

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
- The image holds the jar the verified build produced (reference `Dockerfile`: `COPY target/*.jar`). A
  Dockerfile that compiles the code itself must copy every file the build reads (`lombok.config`, `.mvn/`,
  annotation-processor settings), and CI builds that image: an image compiled without `lombok.config`
  dropped every `@Qualifier` from Lombok's constructors (JAVA-26) while every test and CI run was green.
- Unique node id per instance when ids need one (TSID).

## CFG-09: merged into REL-01, REL-04 and OPS-06

## CFG-10: Configuration layout and startup checks
- `application.yml` only names the app and imports **one file per concern**:
  `config/{db,server,security,observability,limits,jobs}.yml`, each with profile variants beside it
  (`db-prod.yml`, `server-dev.yml`). Spring Boot applies a `<name>-<profile>.yml` variant of an
  imported file on top of it.
- Every tunable is in configuration (SMELL-17); `spring.application.name` is the one name the rest
  derives from.
- No default profile; **startup fails** on a missing or unknown active profile (an allow-list),
  on missing secrets, on inert settings (admission below twice the pool size), and on a renamed setting's old
  property or environment variable while it is still set (CFG-12's migration: its value would be read in another
  unit, or silently ignored).
- API docs are off in the base configuration and on only in `dev` (a forgotten profile must not
  publish the schema).
- The prod pool is set explicitly (e.g. 30/10), never the default 10.
- **Severity:** Medium (High when a missing profile exposes docs or development defaults).

## CFG-11: Build quality gates
The build's gate command (the profile's `buildCommand`, normally `./mvnw -B verify`) fails on more than a red
test: a **coverage minimum** and **static analysis with security rules** run in `verify`, and CI runs that same
command. Reference: `pom.xml` (JaCoCo 0.8.16; SpotBugs 4.10.5 with FindSecBugs 1.14.0) and `spotbugs-exclude.xml`.
- **Coverage** (JaCoCo): the agent with the test run, the report and `check` in `verify`, a line and a branch
  minimum for the whole code base (`coverage.line.minimum`, `coverage.branch.minimum`). Lombok's generated code
  (`lombok.addLombokGeneratedAnnotation = true` in `lombok.config`) and the generated JPA metamodel (`*_`) are not
  counted, and nothing else is left out: excluding a hand-written class to pass the gate is a finding.
  - **Set from measurement:** at least three clean runs, each minimum **a full point below the lowest measured
    value**, rounded down, with a comment saying so. A minimum within a point of the measured level fails at
    random: a branch or two vary between runs (a retry, a race a test provokes), and the varying branch is a
    timing-dependent test to find (TEST-07), never a reason to lower the minimum.
  - **Only ever raised.** A lowered minimum in a diff is a finding (Medium): the fix is the tests the change
    lacks. A raise is set by the same rule.
- **Static analysis** (SpotBugs + FindSecBugs): effort max, threshold low, `check` in `verify`: any finding, low
  priority included, fails the build. A real finding is fixed. A false positive gets one `<Match>` in
  the exclude file: its **class** (and method, where the finding is one method's) and **bug pattern**, with a
  one-line reason on it (why the pattern is not a defect at that code: the generated metamodel, a record handing
  out a list it was built from and no one keeps, an injected singleton whose sharing is the point, a constructor
  that refuses bad settings at startup in a class Spring proxies, JAVA-03).
  - Never a `<Package>`, a whole `category`, a priority or rank, or a class pattern wider than one class: a new
    finding of the same kind elsewhere must still fail. The metamodel's naming pattern (`~.*_`) with its two
    patterns (`UUF_UNUSED_PUBLIC_OR_PROTECTED_FIELD`, `UWF_UNWRITTEN_PUBLIC_OR_PROTECTED_FIELD`) is the one
    pattern entry: the classes are generated.
  - **An entity's relation method** (one that points a JPA relation at its argument: `EI_EXPOSE_REP2`) is an
    entry per class and method like any other, with the reason "a JPA relation holds the managed entity; it
    can't be a copy"; crud-gen generates it with the entity.
  - **Low priority's informational kinds**, one entry per class: `SPRING_ENDPOINT` per controller (FindSecBugs
    marks every handler for a reviewer; who may call it is its `@Requires`/`@SignedIn`/`@Public` and its tests, AUTHZ-03), which crud-gen
    generates with the controller; `CRLF_INJECTION_LOGS` per class that logs a value, while every deployed profile
    writes JSON lines (CFG-07: the encoder escapes a line break; a text log in a deployed profile makes the finding
    real). These and the relation method are the only entries generated code brings. A filter (a bean bound through
    its setters) compares its range's bounds through `DateRanges` / `NumberRanges`: compared in the filter's own
    method, static analysis reads the fields as never null-checked (`UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR`).
  - In a diff, **a new entry is reviewed like a deviation** (Step 4): its reason must hold at the code it names;
    when it doesn't, the finding is the defect the entry hides. An entry whose class or method no longer exists
    is stale (Low).
- **CI** runs the gate command and keeps the coverage report and `target/spotbugsXml.xml` whether the build is
  green or red. A project without CI says where the command runs (a pre-push check, the release build).
- **Green means the gate command.** "Green at every commit" (a brief, a review commit by commit, crud-gen's
  commit per entity) is `buildCommand` at that commit, never the tests alone: a batch that ran `test` per commit
  left commits that fail `verify`.
- **Not the gate:** SonarLint is an IDE aid, never what passes or fails a build; Error Prone (a compiler
  setup next to Lombok), PMD (weak security rules) and a SonarQube server are not required. A project with
  another tool that fails the build on security rules at medium and above meets the static-analysis half.
- **Detect:** `pom.xml` (`build.gradle`): the two plugins, their `check` goals bound to `verify`, the threshold
  (`Low`), the minimums' properties and their comment; the exclude file; the CI workflow's command and kept reports.
- **Severity:** Medium for a missing gate, a lowered minimum, a minimum within the noise, or an entry that is a
  package or a category; Low for an entry without its reason or a stale one.

## CFG-12: Typed settings
Each setting is bound as the **type of what it means**, so Boot parses and checks it once, at startup, naming its key,
and code never converts a unit or parses text. Reference: the settings records (`JwtProperties`, `MediaProperties`,
the jobs' `Settings`, `NotAfterTodayProperties`), `common/scheduling/Cron`; `SettingsBindingTest` proves each binding
below on Boot's own binder.
- **Check:** every component of a settings record (`@ConfigurationProperties`, a job's `Settings`, a group or value
  record it holds) has its meaning's type:
  - a time span is a `Duration` (`500ms`, `30s`, `5m`, `6h`, `7d`, or ISO `PT15M`); a span of calendar days added to a
    `LocalDate` a `Period` (`1d`, `2w`, `1y`); a size a `DataSize` (`512KB`, `2MB`; a KB is 1024 bytes);
  - a mode or a strategy an enum (code branches on a constant, never on text; Boot binds `truncated` to `TRUNCATED`);
  - an address a `URI` (an unset `${VAR:}` binds as null: "none"); a language a `Locale`; a content type a
    `MediaType`; an email sender the mail library's address type when the project has one; a library's own value
    type when it binds (the AWS SDK's `Region`, through its `Region.of`);
  - one value per case a `Map<SomeEnum, X>` (a code lifetime per purpose, keys written `signup-verification`); named
    instances a `Map<String, Settings>` (as Resilience4j's `instances`); a group of settings a nested record;
  - text with a grammar (a cron, a percentage, a CIDR) a small value record whose constructor checks it: Spring's
    converter binds a `String` through a **public** `String` constructor (a record's own) or a public static
    `of(String)` (a package-private one is never found). A cron is `Cron`: what `CronExpression` parses, or Spring's
    `"-"` (off). The job's `@Scheduled(cron =
    "${...}")` keeps reading the raw property (its run observation and `@MultiInstanceSafe` stay); the record makes
    the setting typed and checked.
- **Bounded:** a value whose zero, negative or too-large setting breaks something carries its bound in the record:
  `@DurationMin` / `@DurationMax` (Hibernate Validator; `days = 0` for "never negative", `millis = 1` for "positive":
  a timeout or a lock timeout of zero often means none at all), `@Positive`, `@Size`, `@NotEmpty`; a rule across
  fields (a grace below a lifetime, an attempt's timeout within the call's) or a library's own range (a presigned
  URL's 1 s to 7 days) is checked in the compact constructor, naming the key, so it fails at startup rather than at
  each call. A token lifetime nothing ends early has a maximum (15m, as the access token's). A `URI` the code relies on is checked for what it needs (absolute, with a host): Spring encodes
  what the URI grammar refuses instead of failing (`http://bad host/` binds as `http://bad%20host/`, with no host).
- **The unit is in the value, never in the name:** `accessTokenTtl: 15m`, not `accessTokenTtlMinutes: 15`; the
  environment variable likewise (`JWT_ACCESS_TTL=15m`).
- **The migration trap:** a bare number bound to a `Duration` means **milliseconds** (a `Period`, days; a `DataSize`,
  bytes). Turning `ttlMinutes` into a `Duration` while the deployment keeps `JWT_ACCESS_TTL_MINUTES=15`, or the yml
  keeps `15`, silently gives 15 ms. So a migrated setting gets a **new name** for the property and its environment
  variable, the startup checks (CFG-10) **refuse the old names while they are still set** (naming the new one and its
  format), and its minimum is one a stray bare number fails (`JwtProperties`: an access-token lifetime of at least
  `1s`). `@DurationUnit(ChronoUnit.MINUTES)` keeps the old meaning of a bare number; it is only for a setting whose
  name and value can't change yet, with its reason; prefer the rename.
- **Detect:** `int`/`long`/`double` components named `*Ms`, `*Millis`, `*Seconds`, `*Minutes`, `*Hours`, `*Days`,
  `*Bytes`, `*Kb`, `*Mb`; `String` components named `*Url`, `*Uri`, `*Endpoint`, `*Cron` (a host name alone stays
  text), or holding a mode compared with
  `equals`; `Duration.ofMinutes(settings.x())`, `* 1000`, `TimeUnit.X.toMillis(...)`, `URI.create(settings.x())`,
  `CronExpression.parse(...)` or `Enum.valueOf(...)` on a setting; a `Limits`-style read by key of a time or a size;
  a renamed setting's old name or environment variable still in `.env.example`, a compose file or a deployment
  manifest; a `Period` written `1m` (a month, where a `Duration`'s `1m` is a minute). **Enforced** by ArchUnit
  (`ConventionArchTest.settingsAreTypedByWhatTheyMean`, the names above on every `@ConfigurationProperties` record and
  the records it holds, a map's or list's values included; proven red on its fixtures in `ArchRuleScopeTest`); the
  rest (a mode as text, a conversion in code, a bound) is review.
- **Not a finding when:**
  - a count (`batchSize`, `maxFiles`, attempts, a per-day budget): a number is its type;
  - a library's own property that takes only a number (Hikari's `connection-timeout` in milliseconds, PgJDBC's
    `socketTimeout` in seconds, JavaMail's `mail.smtp.timeout`), with a comment naming the unit;
  - the configured bound of a number the **API** receives in its own unit (`time-entry.max-minutes` for a request
    field in minutes, `upload-policy.max-size-bytes` for a size in bytes): the setting matches the wire. It is a
    `Limits` key, read by key, so the ArchUnit rule never meets it;
  - an invariant (an algorithm's or a format's constant, S3's 5 GiB, SigV4's seven days) stays in code with a
    why-comment (SMELL-17);
  - business values clients edit are data (rows, configuration metadata), not settings;
  - a time zone: there is no time-zone setting (the backend is UTC), so `ZoneId` and a business-local `LocalTime`
    stay out.
- **Tests:** the binding is Boot's, so a settings record needs no binding test of its own; its compact
  constructor's checks get a unit test (`JwtPropertiesTest`), and a value record of the project's own (`Cron`) is
  bound in `SettingsBindingTest`. A test profile may set short values (`200ms`, `2s`) where a test exercises an
  expiry, a lease or a retention for real.
- **Severity:** Low for a new number-with-unit or text-to-parse setting; **Medium** when code converts it in several
  places, or a wrong unit or a missing bound weakens a safeguard (a token lifetime, a lock lease, a timeout of zero);
  **High** for the migration trap (an existing environment value read in another unit).

