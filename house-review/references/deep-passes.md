# Deep passes (SKILL.md Steps 3a and 3b)

Run in path and all reviews (SKILL.md Step 3; with `--focus`, `arch` runs 3a and `config` 3b). A rule
loaded in another mode that points to one item here (SMELL-10's change-blast test, REL-04's timeout
table) reads that item alone.

## Step 3a: Architecture pass (path and all; do it yourself, it needs one view of every module)
1. **5-minute scan:** the package tree, cycles, cross-module DTO imports, the 5 biggest
   services, and a grep for
   `@Transactional|REQUIRES_NEW|SUPPORTS|AFTER_COMMIT|@Async|@EventListener|@TransactionalEventListener`.
2. **In order:**
   1. module map (ARCH-01);
   2. dependency direction (ARCH-02/03);
   3. boundary objects (ARCH-05/06);
   4. orchestration sinks (SMELL-02 collaborator count);
   5. data ownership: who stores, mutates, validates and exposes each key concept (ARCH-09);
   6. transaction semantics (DATA);
   7. `common` / `shared` audit (ARCH-08).
3. **Trace one critical use case end to end:** entry point, service, domain, writes, events,
   async work, response. Answer: who owns the workflow, where the transaction boundary is, where
   the side effects happen, and how many modules took part.
4. **Change-blast test:** simulate adding one field to the module's main entity (request,
   response, mapper, changeset) and count the files and modules it touches.
5. **Output:**
   - a one- or two-sentence **structural verdict**;
   - the **deep pattern** (workflow entanglement, boundary contamination, orchestration sink,
     framework choreography, namespace drift);
   - the **smallest refactoring move**.

   Priority: cycles, then contracts, ownership, orchestration sinks, transaction choreography,
   layout drift.
6. **Questions to ask of each class:**
   - Who owns this concept?
   - Why is this dependency allowed?
   - Why does this DTO cross a boundary?
   - Why does this service know so much?
   - Why is this behaviour hidden in transaction timing?
   - Why is this in `shared`?
   - If this rule changes tomorrow, what breaks?

## Step 3b: Resilience pass (path and all)
1. **Timeout and limit table per layer:**
   - caller or gateway;
   - this service;
   - the pool (`connection-timeout`);
   - SQL (statement, lock, idle);
   - each outbound client (connect, read, attempts × timeout + backoff).

   Each value comes from configuration, the client builder, or the **framework default when
   nothing is set** (say which). Check that the timeouts nest (REL-04) and the order holds
   (DATA-19).
2. **Safeguards table:** one row per safeguard (pool, rate limiter, bulkhead, scheduler, quota,
   retry, outbox, breaker), with:
   - its control action;
   - what it believes;
   - its feedback signal;
   - its flaw: missing feedback, wrong model, or no terminal or corrective action.

   Every mitigation metric must have a consumer (OPS-07).

