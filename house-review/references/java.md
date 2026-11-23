# Java rules (JAVA-xx)

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
  - DTOs are records; a record's collection is copied in its compact constructor (`List.copyOf`,
    `Map.copyOf`; a request body's wrapped read-only and null-safe, `Collections.unmodifiableSet`, since
    Jackson builds it before validation and a null element must reach its constraint), which is also what
    the static-analysis gate's `EI_EXPOSE_REP` asks (CFG-11); a record holding a managed entity is the
    exception, its exclude entry saying so;
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
- **Detect:** a lookup that may pass `null` into an immutable collection: `Map.of()` / `Set.of()` /
  `List.of()` (and their `copyOf`) throw on `get(null)`, `containsKey(null)` and `contains(null)`, where a
  `HashMap` answers `null` or `false`. It fails only on the empty case (a batch with no ids gets
  `Map.of()`, and a mapper asks it for an absent reference), so the test that finds it is that case.
- **Severity:** Medium for a `null` collection or `.get()`; Low for `== null`.

### JAVA-09: Exceptions
- **Check:**
  - standard exceptions for their standard meaning (`IllegalStateException` only for bugs, JAVA-20);
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
  (`StringUtils.hasText`), date math (`Duration`; a setting is bound as one, never converted from a number of
  minutes: CFG-12). A library's parser is not the input contract,
  though: what is valid stays the project's own, pinned by tests (SEC-05).
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
- **Also:** `BigDecimal.equals` compares scale (`2` ≠ `2.0`), and records and `Objects.equals`
  inherit it: compare with `compareTo`, or normalise the scale once, and round once at the end.
  A change check that compared `2` with `2.0` recorded a change that wasn't one.
- **Severity:** Medium.

### JAVA-16: Entities: Lombok, equality, collections
- **Check:**
  - class-level `@Getter` + `@SuperBuilder`; `@Setter` only on freely editable fields; never
    `@Data`, class-level `@Setter`, or `@EqualsAndHashCode` / `@ToString` that touch relations;
  - `equals` / `hashCode`: no override (identity), or an id-based override with a constant
    `hashCode`;
  - collections exposed as unmodifiable views, changed only through `add…` / `remove…` methods
    that keep invariants (counts, back-references);
  - **starting values live in the entity** (a new record's status, a boolean's default) with
    `@Builder.Default`: the builder ignores field initialisers, and a default set in the mapper is
    missed by every other creator;
  - **booleans** are primitive `boolean` fields on a `NOT NULL` column with a database default (an
    absent value is never a third state). A flag edited with the rest of the record has `@Setter`; a
    transition with rules or side effects is an entity method.
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
    The same goes for `IllegalArgumentException`: it answers 500 too, so it never rejects a value
    from the request (ERR-04).
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

### JAVA-25: Time without time zones
The backend handles no time zone.
- **Check:** moments are `Instant` (UTC; the client shows them in the user's zone); calendar dates the
  user picks are `LocalDate`, stored and returned as sent, never converted through a zone; "now" and
  "today" come from the injected `Clock` (UTC), also inside validators; a date compared with today
  allows a configured tolerance (1 day: every zone is within +14 h of UTC), so no real user is
  refused, and the client enforces the exact local rule;
- **One configured zone, read in one place:** a business rule a date decides against today (a leave already
  taken, a deadline at midnight; DATA-37) takes the organisation's day from `BusinessCalendar.today()`
  (`common/time`, `app.business-calendar.zone`), never from the server's UTC day nor the client's; nothing else
  reads the zone, and domain code holds none.
- **One boundary, one clock read:** a write that ends one period and starts the next (close a
  version, open its successor; DATA-36) reads the clock **once** at the top of the service method
  and passes that value to both. Two reads leave a gap, and reads taken in the wrong order overlap (23P01). Never mix the
  application `Clock` and the database's `now()` in one boundary: `now()` is the transaction's
  start, earlier than a later application read, so a close with one and an open with the other
  overlapped; `clock_timestamp()` differs per statement. The application `Clock` is the house source
  (auditing reads it too).
- **Detect:** `ZoneId`/`ZonedDateTime`/`OffsetDateTime` in domain code; `LocalDate.now()` or
  `@PastOrPresent`/`@FutureOrPresent` on a `LocalDate` without the application clock; a `LocalDate`
  derived from an `Instant` on the server outside `BusinessCalendar`; `LocalDate.now(clock)` stored as the day an action records
  (returned on, approved on: the server's UTC day is yesterday east of UTC until midnight UTC; the
  client sends the user's own day, `@NotAfterToday`); `LocalDateTime` for a moment; two `clock.instant()` /
  `LocalDate.now(clock)` calls in one close-and-open method; `now()` or `clock_timestamp()` in a
  query that writes a period bound the application also writes.
- **Severity:** Medium (High when a stored date shifts).

### JAVA-26: Constructors are Lombok's
- **Check:** a class whose constructor receives its collaborators (a Spring bean, a class Spring
  instantiates such as a constraint validator, a filter or interceptor a configuration builds with `new`)
  takes them through Lombok's `@RequiredArgsConstructor` over `private final` fields. A hand-written
  constructor that only assigns its parameters to fields is a finding. One that does work is a finding
  unless the work can't be a `@Bean` method or a bean of its own; then it stays, with a why-comment
  saying what it does. Work that stays:
  - a `super(...)` call with arguments, or a `@Value` parameter (below);
  - meters registered at startup, so their series read 0 before the first event;
  - a Resilience4j instance looked up once, failing the startup when it is missing;
  - a value that is expensive or checked once and that only this bean uses: contributions merged with a
    conflict check, a bcrypt decoy hash one sign-in service compares against (golden `AuthService`, whose
    constructor also registers a meter). A value several beans use is a bean of its own, made once by a
    `@Bean` method (a `DecoyHash` record that login and the code checks compare against), never computed
    in each of their constructors;
  - a helper that must stay the bean's own: a `JdbcTemplate` over a second `DataSource`, a `REQUIRES_NEW`
    `TransactionTemplate`. As beans, other classes would be injected with them by type.

  Copying a value out of injected properties (`properties.slowThreshold().toMillis()`) is not such work:
  inject the properties and read them where they are used. A startup check is an `InitializingBean` over
  Lombok fields, not a constructor that checks (golden `StartupChecks`).
- **`@Qualifier` through Lombok:** Lombok copies a field's annotation onto the generated constructor's
  parameter, where Spring reads it, only when `lombok.config` lists it:
  `lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier`, in a
  `lombok.config` at the project root with `config.stopBubbling = true` (golden `lombok.config`; Lombok
  1.18.48 copies none of Spring's annotations by default). Without the line, a `@Qualifier` field loses
  its qualifier: the bean gets the primary candidate (the wrong one), or the startup fails on an
  ambiguous match. A qualifier annotation of its own (`@LiquibaseDataSource`) needs its own line.
  `@Value` never goes on a field, even a Lombok one: that is field injection (ArchUnit's
  `NO_CLASSES_SHOULD_USE_FIELD_INJECTION`, in the house `ConventionArchTest`). A value is a
  `@ConfigurationProperties` record injected like any collaborator; where it can only be a `@Value`
  (`spring.application.name`), the bean keeps a hand-written constructor with `@Value` on the parameter
  and a why-comment (golden `ProblemTypes`).
- **Detect:**
  - constructors in `@Component` / `@Service` / `@Repository` / `@Controller` / `@Configuration` classes,
    filters, interceptors, `*RepositoryImpl` classes and `ConstraintValidator`s whose body is only
    `this.x = x;` lines;
  - constructors that do more, without a why-comment;
  - `@Autowired` on a constructor (a single constructor needs none);
  - `@Qualifier` or a qualifier annotation on a `final` field of a `@RequiredArgsConstructor` class,
    while `lombok.config` is missing or lacks the matching `copyableAnnotations` line;
  - `@Value` on a field.
- **Fix:** delete the constructor and add `@RequiredArgsConstructor` (`access = AccessLevel.PACKAGE` when
  JAVA-27 asks for it). Keep the fields in the old parameter order: tests that call `new` rely on it.
  Move the work out: properties are read where they are used, a check moves to `afterPropertiesSet`.
  Add the missing `lombok.config` lines (`/crud-gen bootstrap` copies the file).
- **Not a finding when:** records (canonical and compact constructors), enums, entities (JAVA-16),
  exceptions, `@ConfigurationProperties` types, test classes; the private constructor of a utility class
  (JAVA-02) or of a value class behind static factories (JAVA-01). A project without Lombok records that
  in the profile's *Conventions*; the rule then doesn't apply.
- **Severity:** Low; High for a `@Qualifier` that Lombok drops (the wrong bean, or a startup failure).

### JAVA-27: No member exposes a type beyond its visibility
- **Check:** no public or protected constructor, method or field of a public class names a
  package-private type, as a parameter, return type, field type or type argument. IntelliJ reports it
  as "Class is exposed outside its defined visibility scope": any package may call the member, but none
  outside can name the type, so the member promises a contract nobody else can use. The common case is
  a public service whose Lombok constructor (public by default) takes a package-private collaborator.
- **Detect:** per package, its package-private top-level types; then each public class of the package:
  `@RequiredArgsConstructor` without `access` over a `final` field of such a type, or a `public` or
  `protected` constructor, method or field that names one. The house `ConventionArchTest
  .membersExposeNoNarrowerType` fails the build on it (in bytecode, so Lombok's constructors too); review
  only a project without that rule.
- **Fix:** narrow the member, never widen the type: `@RequiredArgsConstructor(access =
  AccessLevel.PACKAGE)` (golden `UserService`, `MediaFileService`), a package-private hand-written
  constructor (golden `AuthService`), a package-private method. Spring calls a package-private
  constructor as well. When another package truly needs the member, its types go public as a module
  contract decision (ARCH-04), not as the fix of this rule.
- **Not a finding when:** the member is private, or the class itself is package-private (its members
  can't escape the package); test classes.
- **Severity:** Low.
