# Java rules (JAVA-xx)

Sources: `Effective Java Summary.md`, plus the house conventions the user stated while
designing this skill (DESIGN.md).

---

### JAVA-01: Construction
- **Check:** static factories (`of`, `from`, `withX`) or builders for complex construction.
  Entities use `@SuperBuilder`. No telescoping constructors.
- **Severity:** Low.

### JAVA-02: Utility classes
- **Check:** a stateless helper class is `final` with a private constructor, and holds only
  static pure functions. **Static methods are allowed only** for factories and pure stateless
  utilities. A static method that hides dependencies, state, time or I/O is a finding: inject
  it instead.
- **Severity:** Medium for hidden dependencies; Low otherwise.

### JAVA-03: Minimal visibility and mutability
- **Check:**
  - types are package-private unless another package uses them: controllers, `*RepositoryImpl`
    classes and helpers used within one package;
  - mappers and services are public only because the layered layout puts them in different
    packages;
  - fields are `private final` where possible;
  - DTOs are records;
  - `final` is **not** required on local variables, parameters or classes (Spring proxies need
    non-final classes).
- **Severity:** Low.

### JAVA-04: Composition over inheritance
- **Check:** inheritance only for bases designed for it (`AuditableEntity`, `PageFilter`).
  Otherwise compose.
- **Severity:** Medium.

### JAVA-05: Interfaces
- **Check:** an interface only when the type has, or truly needs, **more than one
  implementation**: real production implementations, or an external-system boundary with a dev
  or mock implementation (HTTP client, SMS/email sender). A test fake or Mockito mock is **not**
  a reason (Mockito mocks classes). No interface-per-service. Also allowed: a published module
  contract (`<module>.api`) that breaks a dependency cycle. Not allowed: "we might switch the
  store later" (add the interface when the second implementation arrives).
- **Detect:** interfaces with exactly one implementing class in `src/main`.
- **Severity:** Low.

### JAVA-06: Enums
- **Check:** enums instead of String or int constants for closed sets. `EnumSet` / `EnumMap`
  instead of ordinals. Behaviour that varies by constant goes on the enum.
- **Severity:** Medium when strings are compared across classes; otherwise Low.

### JAVA-07: Check parameters
- **Check:** validate at the boundary (bean validation, REST-09) and fail fast in domain
  methods (`Objects.requireNonNull`, guard clauses).
- **Severity:** Medium.

### JAVA-08: Nulls and Optional
- **Check:**
  - return empty collections, never `null`;
  - `Optional` **only as a return type**: never a field, a parameter, or wrapping a collection;
  - prefer `orElseThrow` (with `AppException.supply(...)`), `map` and `ifPresentOrElse`;
    `isPresent()` + `get()` only around a block with side effects (the same reasoning as the
    stream rule);
  - null checks are `Objects.isNull` / `Objects.nonNull`, not `== null` / `!= null`.
- **Severity:** Medium for a `null` collection or `.get()`; Low for `== null`.

### JAVA-09: Exceptions
- **Check:**
  - standard exceptions for their standard meaning (not `IllegalStateException` as a domain
    signal another class catches);
  - exceptions fit the layer (translate low-level ones);
  - failure messages contain the values that failed;
  - never swallow (an empty catch, or a catch that only logs without a reason);
  - unchecked exceptions for programming errors and API errors;
  - a small hierarchy carrying an `ErrorCode` (REST-06), with a new class only when callers must
    catch it specifically.
- **Severity:** Medium; High for a swallowed exception on a data path.

### JAVA-10: Catch-all
- **Check:** `catch (Exception | Throwable)` only at documented boundaries: scheduler loops,
  "never throw" clients, the per-item loop of a batch. It must log with the cause, and must not
  hide a poison item forever (CONC / REL rules).
- **Severity:** Medium.

### JAVA-11: Thread safety
- **Check:** singleton beans are stateless, or their state is immutable or thread-safe
  (`final`, `volatile`, atomics, concurrent collections, synchronised access). No mutable
  scratch fields. Document thread safety where it isn't obvious.
- **Severity:** High.

### JAVA-12: Libraries first
- **Check:** no hand-written infrastructure where a maintained library or the platform does it:
  retry, circuit breaker, rate limit, caching, hashing (`DigestUtils`), string checks
  (`StringUtils.hasText`), date math (`Duration`).
- **Fix:** present the candidate libraries to the user before adding one (house rule).
- **Severity:** Medium.

### JAVA-13: Readability conventions
- **Check:**
  - **explicit types, no `var`**: reviews on GitHub can't hover to see a type;
  - **text blocks (`"""`) for every multi-line string**: SQL, JSON fixtures, messages, OpenAPI
    descriptions. Never `+`-concatenated lines.
- **Severity:** Low.

### JAVA-14: Streams
- **Check:** streams are welcome for **data pipelines** (map, filter, collect, reduce). Findings:
  - **side effects inside the stream:** `forEach` that mutates outer state or does I/O, `peek`
    with side effects, lambdas that modify captured collections. When a side effect is the
    point, use a `for` loop so the intent is explicit;
  - **unreadable pipelines:** a complex step should be a named method passed as a method
    reference (`.map(this::toSummary)`), not a multi-line lambda packed into the pipeline.
- **Severity:** Medium for side effects; Low for readability.

### JAVA-15: Exact values
- **Check:** no `float` / `double` for money or values that must add up exactly. Use
  `BigDecimal` or integer minor units; percentages that must total 100 use one rounding rule.
- **Severity:** Medium.

### JAVA-16: Entities: Lombok, equality, collections
- **Check:**
  - class-level `@Getter` + `@SuperBuilder`; `@Setter` only on freely editable fields; never
    `@Data`, class-level `@Setter`, or `@EqualsAndHashCode` / `@ToString` that touch relations;
  - `equals` / `hashCode`: no override (identity), or an id-based override with a constant
    `hashCode`;
  - collections exposed as unmodifiable views, changed only through `add…` / `remove…` methods
    that keep invariants (counts, back-references).
- **Severity:** Medium (High for `@Data` on an entity with relations).

### JAVA-17: Mappers
- **Check:** mappers are hand-written `@Component`s (never MapStruct): mapping only, no business
  rules (workflow status decisions, aggregations) and no flag arguments.
- **Severity:** Medium.

### JAVA-18: Enums are compared with `==`
- **Check:** `==` for enums, never `.equals()` / `Objects.equals`. It is null-safe and checked at
  compile time.
- **Severity:** Low.

### JAVA-19: Pattern-matching `switch` instead of `instanceof` chains
- **Check:** three or more `instanceof` branches become a `switch` with type patterns (sealed
  where possible, so the compiler checks exhaustiveness).
- **Severity:** Low.

### JAVA-20: Fail fast on broken invariants
- **Check:** an impossible state throws `IllegalStateException` with the failing values. Never
  return `null`, `0` or a default that hides a bug.
  - ISE is for **bugs** only: it answers 500 and is never caught as a domain signal.
  - Anything a client can cause is an `AppException` with an `ErrorCode` (error-contract.md).
- **Not a finding when:** a documented fallback (fail-open).
- **Severity:** Medium.

### JAVA-21 (profile-optional: commons-lang3 ≥ 3.17): Explicit case sensitivity
- **Check:** `Strings.CS.equals` / `Strings.CI.equals` instead of `StringUtils.equals` /
  `equalsIgnoreCase`.
- **Severity:** Low.

### JAVA-22: No redundant null checks
- **Check:** no null check before an API that already handles null (`StringUtils.hasText`,
  `NumberUtils.isParsable`, `Objects.equals`).
- **Severity:** Low.

### JAVA-23: Methods in records and data classes are written on several lines
- **Check:** no crammed one-line method bodies in records and data classes.
- **Not a finding when:** a compact constructor with one guard.
- **Severity:** Low.

### JAVA-24 (Jackson 3): The single-constructor trap
- **Check:** a *class* DTO with exactly one non-default constructor is auto-detected as the
  `@JsonCreator`, and breaks when the parameter types differ from the field types. DTOs are
  records.
- **Severity:** Medium.
