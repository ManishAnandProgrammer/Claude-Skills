# Code smells (SMELL-xx)

Thresholds come from the profile
(`{{maxMethodLines}}` default 30, `{{maxParams}}` default 4, `{{maxClassLines}}` default 300).
Lines are counted without blank or comment lines.

---

### SMELL-01: Duplicate code
- **Check:** the same logic or the same piece of knowledge (a rule, a format, a regex, a
  constant) appears more than once.
- **Detect:** grep for repeated regexes, format strings (`"#PP-"`), hashing or normalisation
  code, identical validation ranges, near-identical classes (copy-paste exception types,
  summary endpoints); a derived value (days between two dates, the days left of a balance)
  computed both in the entity and in a list projection's constructor: one static method of the
  entity, called by both.
- **Email (owner decision):** one normaliser, core's `common.validation.Emails.normalize`, for
  every write and lookup by email, and one constraint, `@AsciiOnly`; a hand-written
  `strip().toLowerCase(…)` of an email, or an ASCII `@Pattern` on one, is a finding (Medium: a lookup that
  normalises differently from the write misses the account).
- **Severity:** Medium; Low for 2 copies of a trivial line.
- **Fix:** one helper, constant or value type. Prefer a library method (JAVA-12).

### SMELL-02: God class / divergent change
- **Check:** a class over `{{maxClassLines}}` lines, or one that changes for several unrelated
  reasons (HTTP transport + resilience + message wording + parsing).
- **Detect:**
  - lines are code lines, without blank lines and comments (javadoc included), never the file's length:
    `grep -cvE '^\s*($|//|/\*|\*)' <File>.java`;
  - list the class's responsibilities, one line each; 4 or more is a finding;
  - injected collaborators: 8 or more is a finding; 5–7 sends you to the responsibility list above;
  - ask "if two unrelated rules change, do they both hit this class?".
- **Severity:** Medium.
- **Fix:** split by use case or workflow slice, within the same module. Don't extract random
  helpers while keeping the orchestration in one place.

### SMELL-03: Long method
- **Check:** a method over `{{maxMethodLines}}` lines, or one that does more than one thing.
- **Fix:** Extract Method, named after *what* the part does.
- **Severity:** Medium (Low just over the limit).

### SMELL-04: Long parameter list
- **Check:** more than `{{maxParams}}` parameters, or several booleans or Strings of the same
  type next to each other (easy to swap).
- **Not a finding when:** a record's canonical constructor, or a framework callback.
- **Fix:** a parameter object (a record) or a builder.
- **Severity:** Medium.

### SMELL-05: Data clumps
- **Check:** the same group of fields or parameters travels together (headline + body per
  language; three sentiment percentages; street/city/zip).
- **Fix:** a value object (a record, or `@Embeddable` for entities, keeping the column names).
- **Severity:** Low.

### SMELL-06: Primitive obsession
- **Check:** domain concepts held as raw `String` / `int`: email, phone, money, codes,
  outcomes and statuses compared as strings (`"FAILED".equals(x)`).
- **Fix:** an enum for closed sets; a value type that validates and normalises (email
  lower-cased, phone in E.164) for open ones.
- **Severity:** Medium when a string is a contract between two classes; otherwise Low.

### SMELL-07: Switch on type code
- **Check:** the same `switch` / `if` chain on a type or status repeated in several places,
  or behaviour selected by a type code.
- **Fix:** polymorphism, an enum with behaviour, or a strategy map.
- **Not a finding when:** a single exhaustive `switch` expression over an enum in one place.
- **Severity:** Medium.

### SMELL-08: Train wrecks / Law of Demeter
- **Check:** `a.getB().getC().getD()` chains that reach through objects, including through
  API wrappers (`service.x().getResponse().size()`).
- **Fix:** Hide Delegate: ask the nearest object for what you need, or expose an internal
  method that returns the domain value.
- **Severity:** Low.

### SMELL-09: Feature envy / inappropriate intimacy
- **Check:** a method that mostly reads and writes another class's fields (a service setting 4
  fields of an entity to perform a transition), or classes that change each other's internals.
- **Fix:** Move Method into the class that owns the data (see DATA / JAVA rich-entity rules).
- **Severity:** Medium.

### SMELL-10: Shotgun surgery
- **Check:** one change needs edits in unrelated classes (e.g. adding a payload field in 3
  hand-copied places; a workflow rule split between two modules).
- **Detect:** the change-blast test (SKILL.md Step 3a): the change stays in one module; one that
  needs edits in two or more modules, their DTOs, mappers, listeners or transactions included, is
  High.
- **Fix:** put the knowledge in one place. (The "reuse the entity as a DTO / reflection mapping"
  fix in Coding_Standards §10 is **not** used: DTOs at boundaries and hand-written mappers are
  house rules.)
- **Severity:** Medium.

### SMELL-11: Speculative generality / lazy class
- **Check:** abstractions, parameters, hooks or classes with one trivial use or none: generic
  frameworks for one case, a class that only wraps a one-liner, interfaces with one
  implementation (JAVA-05).
- **Not a finding when:** the profile's generator pattern requires the artifact and it is used.
- **Severity:** Low.

### SMELL-12: Middle man
- **Check:** a class whose methods only delegate to another class, without adding anything.
- **Not a finding when:** a controller (thin by design, REST-21), or a facade that adds
  coordination, transactions or translation.
- **Severity:** Low.

### SMELL-13: Temporary field
- **Check:** fields set only in some code paths, or used as scratch space between method
  calls in a singleton bean (also a thread-safety bug, CONC-01).
- **Severity:** Medium (High in a singleton).

### SMELL-14: Refused bequest / parallel hierarchies
- **Check:** a subclass that overrides parent behaviour with "unsupported"; a new subclass in
  one hierarchy that forces a new subclass in another.
- **Severity:** Medium.

### SMELL-15: Alternative classes with different interfaces
- **Check:** classes that do the same job with different method names or signatures.
- **Fix:** one interface (that is the "more than one implementation" case, JAVA-05).
- **Severity:** Low.

### SMELL-16: Data class / anemic entity
- **Check:** an **entity** whose state transitions and invariants live in services: public
  setters for status, timestamps or version; services setting several related fields; no
  methods such as `publish()`, `fail(reason)`.
- **Not a finding when:** a DTO or record. Those are meant to be pure data.
- **Severity:** Medium.

### SMELL-17: Magic numbers, and tunables in code
- **Check:**
  - unexplained literals: HTTP status numbers, `/ 60000`, `* 24 * 60 * 60`, `"localhost"`;
  - repeated header names or error messages;
  - defaults written in both an annotation and configuration;
  - **a tunable in code**: page sizes, batch sizes, text lengths, retry
    ranges, grace windows, cron expressions, path prefixes Boot already configures
    (`/actuator` vs `management.endpoints.web.base-path`), the application's own name. These live
    in configuration (`app.limits.*`, `app.jobs.*`, `spring.application.name`): one source of
    truth that ops can change without a build;
  - the application name repeated in code (error URNs, metric names, pool names): derive it from
    `spring.application.name`; metric names stay app-neutral (the `application` tag names the app).
- **Not a finding when:** an invariant of an algorithm, format or protocol, kept with a
  why-comment (bcrypt reads 72 bytes; a UUID has 36 characters; a 256-bit token).
- **Fix:** a configuration property read through a typed record or `Limits`; a named constant
  only for invariants. The record binds each setting as the type of what it means (a `Duration`, not a
  number of minutes; a `URI`, a `Cron`): CFG-12.
- **Severity:** Medium for a tunable in code; Low for other literals (Medium when the same literal
  is a contract across classes).

### SMELL-18: Dead code
- **Check:** unused classes, methods, fields, record components, constants, imports,
  annotations (`@Slf4j` with no log call, `@RequiredArgsConstructor` with no fields),
  commented-out code, fields that are never written but are exposed in the API, and deprecated or
  renamed library APIs (e.g. Jackson 3 `isString()`, not `isTextual()`).
- **Detect:** grep every candidate across `src/main` **and** `src/test` before reporting. Also
  check compiler warnings from the build.
- **Severity:** Low; Medium when a never-written field is shown to clients as if it meant
  something.

### SMELL-19: Comments
- **Check:** comments are for *why*, on logic the code can't explain (a lock, a security choice, a
  database or framework behaviour, a non-obvious contract). A finding is:
  - a comment that restates the code, **including class and method Javadoc that repeats the name**
    (`/** Accounts. */` on `UserService`, `@param` lists that repeat `@Schema` text). A *why* is
    fine in Javadoc form on a class or method when it explains a contract the signature can't
    (a lock order, a transaction rule, a security choice);
  - a historical "no longer …" note;
  - a stale or misleading comment;
  - a `TODO` without an owner or ticket;
  - a journal or closing-brace comment;
  - a manual or runbook in code.
- **Also:** enum constants in a module's `api/` package (a published contract) carry Javadoc.
  Remove stale Javadoc, and fix broken `{@link}`s.
- **Severity:** Low; Medium when the comment says what the code doesn't do.

### SMELL-20: Names
- **Check:** names are descriptive, pronounceable and searchable, with no encodings or
  prefixes, and a meaningful distinction (`resolvedBy` that also means "dismissed by" is a
  finding). Parameter order consistent across sibling methods.
  - **HTTP input names:** request bodies are `<Entity><Action>{{requestSuffix}}` (default
    `Request`: `TodoCreateRequest`, `TodoStatusRequest`; a project may choose `Payload` or
    `Command`), GET parameter objects `<Entity>Filter`, responses `<Entity>Response`; **one suffix
    per codebase**, never `*Payload` beside `*Request`;
  - one name per concept across layers (a filter's `todoListId`, not `listId`, when the payloads
    and responses say `todoListId`);
  - **package names are lower-case words** (`transportstaff`, not `transportStaff`).
- **Severity:** Low (Medium when a name says what the code doesn't do, as `resolvedBy` for a dismissal).

### SMELL-21: Flag arguments
- **Check:** a `boolean` parameter that selects behaviour (`setEnabled(id, false)`,
  `applyFields(..., isNew)`, `new X(msg, true, false)`).
- **Fix:** separate methods, or named factories.
- **Severity:** Low.

### SMELL-22: Hidden side effects / query mixed with modifier
- **Check:** a method whose name promises a read but writes (a `get…` that INSERTs), or that
  both returns data and changes state without saying so.
- **Severity:** Medium.

### SMELL-23: Conditionals
- **Check:** negative conditionals (`if (!isNotValid)`), deep nesting where a guard clause would
  do, complex boolean expressions without an explanatory variable or method.
- **Severity:** Low.

### SMELL-24: Inconsistency
- **Check:** similar things done differently in the same codebase: two blank-check helpers,
  two time-zone meanings of "today", one module on `== null` and another on `Objects.isNull`,
  some not-found messages with the id and some without.
- **Severity:** Low (Medium when the meaning differs, as with "today").

### SMELL-25: Vertical structure
- **Check:** variables declared far from their first use; callers above callees (read
  top-down); related methods scattered; very long lines; horizontal alignment.
- **Severity:** Low.

### SMELL-26: Incomplete library class
- **Check:** a missing library capability worked around in two or more places.
- **Fix:** wrap it once in an adapter.
- **Severity:** Low.

### SMELL-27: One meaning per field or column
- **Check:** a field or column answers exactly one question. A `null` used as a flag while the
  value means something else, sentinel values (`-1`), or a column read with two meanings is a
  finding. "Two questions, two columns."
- **Severity:** Medium.

### SMELL-28: No helpers for one-off operations; no single-use temporaries
- **Check:** an operation used once is inlined, not turned into a helper. A local used once that
  adds no clarity is inlined.
- **Not a finding when:** the name documents intent that an expression would hide.
- **Severity:** Low.

### SMELL-29: Accidental complexity (KISS, SRP)
The house's top priority: code a Java engineer with 2–3 years of experience understands
perfectly, without deep generics knowledge or reflection. Complexity from the domain is accepted;
complexity added to look smart or save keystrokes is a finding.
- **Check:**
  - a **toolbox, not a framework**: shared code is plain helper methods called from straightforward
    code (a repository writes its Criteria query top to bottom and calls `Cursor.read`,
    `Seek.after`); a helper that takes the caller's logic as lambdas or functional interfaces and
    assembles the work itself is a finding when a plain call would do;
  - **generics**: type parameters the caller must decode to use a method (nested wildcards,
    self-referencing bounds of our own, a type parameter whose meaning isn't obvious from its
    argument), or type parameters and type tokens that exist only to make a generic helper or
    framework work, are findings. Type parameters that mirror a JDK or Jakarta signature are fine,
    however many: `Seek.after`'s `<A extends Comparable<? super A>, B extends …>`, one per key
    column, is exactly what `CriteriaBuilder.greaterThan` asks for;
  - **reflection** (field access, `Class.forName`, generic type resolution) in application code;
  - **a hook added for one caller** (a parameter, flag or interface method that only one use needs)
    is speculative generality: the helper is growing into a framework;
  - **DRY means knowledge**: one place for a rule, a format or a security check; similar-looking
    query code that differs in what it selects and filters is not duplication.
- **Detect:** `Function<Root<T>, …>` or `(builder, root, query) -> …` passed into a house helper;
  a record or class whose type parameters only a generic helper needs; a helper whose signature changed for one new caller; a
  reader who needs a debugger to see what SQL a method runs.
- **Severity:** Medium (High when the indirection hides a security or correctness rule).
