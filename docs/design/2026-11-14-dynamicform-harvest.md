# DynamicForm harvest (2026-11-14): candidates for the skills

A read-only review of the owner's DynamicForm codebase (about 70,000 lines: shared, auth, form,
submission, webhook, invitation, quiz, ai, demo, notes, playbooks, investigations), looking for
knowledge that recurs across applications. Four reviewers; the high-impact claims were re-checked
in the code (marked ✔). Nothing is adopted until the owner decides; this is the candidate list.

## Tier 1: new knowledge areas (rule files or golden code)

| # | Area | What it adds | Form |
|---|---|---|---|
| 1 | **Tenancy under row-level security** | Session variables transaction-local (`set_config(…, true)`); a bypass by a `BYPASSRLS` role, never a GUC the app role can set itself (that makes RLS no barrier to SQL injection); Hikari `connectionInitSql` is rolled back by the first borrower under `auto-commit=false` unless `isolateInternalQueries`; jobs without a tenant silently see nothing (fail closed, so they must set one); a write takes its tenant from the loaded parent; `current_setting(…, true)` returns `''` (not NULL) on a reused session, so `nullif(…, '')`; only leakproof operators become index conditions under a policy (jsonb `@>` 0.45 ms → 140 ms), so `EXPLAIN` as the app role with the tenant set; a column-free bypass clause wrecks estimates; tests: a raw-JDBC cross-tenant read, a positive escape-valve test, a catalogue test that every `tenant_id` table has RLS. Golden: set the variable once per transaction (a `JpaTransactionManager.doBegin` override), not an aspect. | `tenancy.md` (optional, like `metadata.md`); runbook: RLS role bootstrap; restore needs roles first |
| 2 | **Outbound calls to URLs users choose** | SSRF: scheme allow-list, no userinfo, every resolved address checked (private, loopback, link-local, CGNAT, ULA, NAT64), DNS on a bounded executor with a timeout, redirects never followed, re-checked at send time, and the connection made to the validated address (the JDK `HttpClient` resolves again: DNS rebinding with TTL 0 slips through; needs a library decision). Webhook contract: `t=…,v1=HMAC(t.body)` re-signed per attempt, secret rotation with a grace window and an immediate "regenerate", a stable delivery id, the response body never read (a 192 MB error body killed the JVM). | New SEC-06; webhook rules; golden guard after trimming |
| 3 | **Fenced outbox / delivery poller** | The outbox row written in the business transaction; claim with `SKIP LOCKED` and a random lease token; every result write checks the token (an expired worker can't overwrite a newer claim); the attempt counted when the send starts (a crash mid-send ends INTERRUPTED, not re-sent); attempts capped in the claim (a worker that dies every time otherwise loops forever, never DEAD); DEAD with a metric; processed rows purged; the real error kept; a poll never waits on its slowest destination (✔ today one slow receiver delays every tenant between polls). | Extend DATA-29 / REL-02 / REL-06; golden code (claim + a for-loop of fenced sends; lanes optional) |
| 4 | **Idempotency-Key, fully** | A column on the business row with a partial unique index `(parent, key) WHERE key IS NOT NULL`; a hash of the canonical request + caller beside it (CHECK both or neither); a repeat answered before any gate; same key, other body → 409; a race on the index retried once as a replay. Clients choose idempotency keys, never primary keys that carry meaning (a client-chosen time-ordered id allowed backdating). | Rewrite REL-11; crud-gen option for retried POSTs |
| 5 | **Capability links and signed tokens** | A secret in a URL is ≥ 128 random bits, never an id (time-ordered ids let a recipient work out a neighbour's link), with expiry, revocation, single use by a unique index, never in payloads or logs; stateless HMAC tokens with a per-purpose derived key, constant-time compare, skew and expiry checks, refusals counted by reason. Signing secrets are encrypted at rest, not hashed (SEC-04 today says "hashed", wrong for secrets that must sign). | New REST/SEC rule; DATA-09 note; SEC-04 fix; small golden `SignedTokens` |
| 6 | **LLM calls** | Reserve `max_tokens` + prompt estimate before the call, settle after, count a timeout at its reservation (check-then-call overshoots by concurrency × max tokens; a timed-out call is billed but never recorded); no-tenant accounts fall back to a per-user budget; untrusted text in the user message, delimited and capped, never in the system prompt; templates filled in one pass (✔ chained `.replace` expands a `{{placeholder}}` inside a form name); prompts and answers are personal data, out of logs; model output is untrusted input, validated like the public API; allowed values injected from code enums. | New AI-xx section (lenses.md or its own file) |
| 7 | **Versioned documents and copies** | Immutable snapshots: record N+1 only when the definition record differs; parent locked `FOR NO KEY UPDATE`; `UNIQUE(parent, version)` → 409; a trigger refusing UPDATE/DELETE; results carry the version that produced them; restore re-validates under today's rules. Copies: one copy method per entity and a test that lists the table's columns, each marked copied or skipped (a copy drifted in six hand-written places; a new column now fails the test). Rule engines (scoring, pricing): a pure function over a sealed interface, golden-table tests, immutable rule versions, options stored with the rule. | New DATA-34 + a copy rule; golden later |

## Tier 2: existing rules that are wrong or too weak (text only)

| Rule | Change | Evidence |
|---|---|---|
| DATA-15 | Widen to any exception crossing a joined `@Transactional`; a write that must persist though the request is refused needs `noRollbackFor` or its own transaction, and a test asserts the row | ✔ `AuthService.refresh` deletes the token, throws `AppException`, the delete rolls back: the token survives (works again after a tenant is reactivated); tests assert only the status |
| SEC (new line) | A security predicate's "unknown" is a refusal | `JwtService`: a missing `userId` skips the lock and token-version checks; `isTenantActiveForUser` → `orElse(true)` |
| REST-15 | Each cached security fact states its staleness bound; refresh-reuse detection; a token version for role changes | Revocation cached 30 s, account status 60 s per instance |
| DATA-24 | A bulk UPDATE repeats every precondition checked in Java, not only the selecting one | ✔ the scheduler's `bulkActivateAndClearSchedule` re-checks only the due time: a form can go ACTIVE with no live question |
| REST-12 | Batch ids are a `Set` | ✔ duplicate ids in batch stats → `findFirst().orElseThrow()` → 500 |
| house-review Step 8 | When fixing a finding, search its twins (single/batch, user/admin, copy variants) | The batch-stats endpoint kept the old authorisation after the single one was fixed; the invitation cap kept count-then-insert after the webhook cap was fixed |
| REL-09 | The virtual-thread exemption needs a bounded backlog or a durable pending row + a gauge | A semaphore bounds concurrency, not the queue: parked threads hold the work in memory, lost on shutdown |
| REL-13 | The stop grace exceeds the drain plus every `awaitTermination` in sequence | 10 s phase + two 10 s waits, Docker kills at 10 s |
| REL-11 | (see Tier 1 #4) | |
| ARCH/REL (new) | With more than one instance, state a later request depends on lives in the database or Redis, or the flow is declared single-instance | The AI plan → execute, backdating allowance in a local cache |
| DATA-07 | Un-delete re-checks partial uniqueness; a "trash with recover" recipe (status + purge job) | Restore from trash → generic duplicate error at flush |
| DATA-09 | TSID needs a unique node per instance, checked at startup (or UUIDv7) | The configured JVM flag is not the property the library reads |
| DATA-29 | Detect `hashtext(` (32-bit); use `hashtextextended(…, 0)` | ✔ two places |
| DATA-12 | Truncate `Instant` to microseconds before it leaves; text keys `COLLATE "C"` | PostgreSQL rounds nanoseconds: a stamp sent before the read differs from every later read |
| DATA-21 | Reads that must agree come from one statement | Under READ COMMITTED each statement has its own snapshot |
| DATA-27 | Stored JSON record, wire DTO and module contract are three types | Renaming a wire field orphaned stored rows |
| DATA-13 | Data-migration changesets check every row maps and stop rather than drop | |
| REST-02 | The path names the target; the body never chooses it | Request DTOs carry ids controllers overwrite |
| REST-09 | Data rebuilt from stored rows (copy, seed, import, restore, LLM output) passes the same validation as a request | Several incidents of writers skipping the gate |
| New | Values that stored records reference are retired, not deleted | Options, statuses, categories |
| JAVA-15 | `BigDecimal.equals` is scale-sensitive (records inherit it); round once at the end | 2 vs 2.0 recorded a new version |
| SEC-05 / JAVA-12 | Own the input grammar (what is valid is a product decision, pinned by tests; accept only what every layer reads alike); no overlapping regex quantifiers on user input | A library widened `isParsable` in a minor release; Unicode digits passed Java, failed PostgreSQL; a quadratic regex |
| ERR-04 | Bound what an error message echoes back | Unknown fields echoed without a limit |
| ERR (owner's call) | `IllegalArgumentException` → 400 hides programmer errors as client errors; alternative: map client errors explicitly, let IAE be 500 | |
| CACHE (new) | Evict after commit (`@CacheEvict` evicts before it unless transaction-aware); a local cache refused at startup when instances > 1; immutable values by id need no eviction | |
| PERF-04 | CSV formula injection neutralised; write to a temp file, then send (never a truncated "complete" file); a CSV library, not a hand-written RFC 4180 | |
| SEC-03 / REST-14 | Credentials off in CORS when auth is a header; forwarded headers via `server.forward-headers-strategy=native` + trusted proxies; profile guards as allow-lists; management port metrics secured | |
| Detect line | An entity `@Index` that no changeset creates | A claimed unique index never existed |

## Tier 3: test support, reference and runbook

- Catalogue tests: every FK has an index (allow-list with reasons); every tenant table has RLS.
- A test that counts connections held during network calls (turns DATA-01 into a test).
- Random test order with a logged, replayable seed; one database per Surefire fork; PostgreSQL
  durability off in tests (commit 1.65 ms → 0.12 ms); `max_connections` via `-c`; a fixed DNS
  resolver; cleanup by DELETE with `session_replication_role=replica` over tables from the
  catalogue (TRUNCATE cost 48 ms per test and deadlocked); pin the exact PostgreSQL image.
- TEST-05 technique: hold a lock on a second connection and wait on `pg_locks … NOT granted`, no sleeps.
- Reorder golden code: PUT the full list of ids, refused unless it is exactly the current set, one
  `UPDATE … FROM unnest(…) WITH ORDINALITY`, parent locked.
- Bulk CSV import: a check endpoint sharing the reader, problems by line through the same Bean
  Validation, duplicates within the file and existing rows in one query, all-or-nothing import.
- Runbook: `DISABLE TRIGGER USER` (ALL disables FK checks too), `pg_repack` instead of `CLUSTER`
  / `VACUUM FULL`, offsite backups (roles are cluster-level: restore them first),
  `JAVA_TOOL_OPTIONS` is read by every JVM in the container (use `JAVA_OPTS`).

## DynamicForm's own bugs (for the owner; not skill changes)

✔ `AuthService.refresh` delete rolled back; ✔ scheduler bulk activate without the activation
guards; ✔ batch stats 500 on duplicate ids; batch stats still owner-only (editors/analysts 403);
invitation caps count-then-insert without a lock; shared outbox unfenced and never DEAD on repeated
lease expiry; email sent inside a transaction (latent); `JwtService` missing-claim fail-open;
`isTenantActiveForUser` → `orElse(true)`; `RequestBodySizeLimitFilter` error not Problem Details;
management port permits all incl. prometheus; possible: backfill cursor advanced without a lock,
DNS rebinding past the SSRF guard, `FormVersionBaselineBackfill` at every startup in one
transaction, template copy sharing the cover's S3 key; question names unique only in memory;
restore-from-trash name check; personal data (email) in a DEBUG log; stale comments.

## Notes in DynamicForm that contradict the skills (the skills are right)

`notes/Coding_Standards.md` §Spring Boot (class-level `@Transactional`, public controllers,
`@Data` properties with defaults, `Long` ids, non-Problem-Details errors);
`notes/eight-lenses.md` Pattern 1 (403 for another user's record; the rule is 404);
`notes/postgres-row-level-security.md` §4 (`current_setting` returns `''`, not NULL, on a reused session).
