# CRUD profile

<!--
Per-project configuration for the crud-gen skill. Save as <project-root>/.claude/crud-profile.md.
Every key maps to a {{key}} token in SKILL.md / references/*.md. Use `none` for anything the
project doesn't have; see "Fallbacks" at the bottom for what happens then.
Fill values from the codebase, not from memory.
-->

## Packages

| Key | Value | Notes |
|---|---|---|
| `appPackage` | `com.example.{module}` | Base package for generated layers (`.controller`, `.service`, ...) |
| `modules` | `none` | e.g. `shared, sales, billing`, the values `{module}` can take |

## Types

| Key | Value | Notes |
|---|---|---|
| `idType` | `Long` | `Long`, `UUID`, ... |
| `urlPrefix` | `/api/{version}` | Class-level `@RequestMapping("{{urlPrefix}}/<resource-plural>")`; methods relative with `version = "v1+"` (REST-07) |
| `ObjectWrapper` | `com.example.dto.ObjectWrapper` | Fully-qualified single-object response wrapper |
| `objectFactory` | `ObjectWrapper.withObject` | Static factory that wraps one object (must accept `null`) |
| `ListWrapper` | `com.example.dto.ListWrapper` | Fully-qualified wrapper for unpaged lists: ones the code bounds (an enum's values, a configured maximum enforced on create, a fixed relation such as a student's subjects this session) or ones the review profile's `unpagedLists` names (house-review REST-11). Never for paginated lists or dropdowns (cursor-paged) |
| `BaseRepository` | `org.springframework.data.jpa.repository.JpaRepository` | Project base repository interface (FQN) |
| `NotXss` | `escape-on-output` | A JSON API whose clients render text as text (no annotation generated); or the FQN of an anti-XSS constraint annotation; same value as the review profile |
| `NoEmoji` | `none` | FQN of a no-emoji constraint annotation |
| `AuditBase` | `none` | `@MappedSuperclass` that entities extend for audit columns |
| `auditFields` | `none` | Ordered `name:Type` list, e.g. `createdDate:LocalDateTime, lastModifiedDate:LocalDateTime, createdBy:Long, lastModifiedBy:Long` |
| `CollectionUtils` | `org.springframework.util.CollectionUtils` | Or commons-collections4's, if the project already uses it |
| `MapWrapper` | `com.example.common.wrapper.MapWrapper` | Values keyed by name (`MapWrapper.fromMap`) |
| `Limits` / `MaxLength` / `ConfiguredMax` | `com.example.common.limits.*` | Configurable limits: `@MaxLength("<entity>.<field>")` on request Strings, numbers in `app.limits.*` (`config/limits.yml`) |
| `TextSearch` | `com.example.common.pagination.TextSearch` | Case-insensitive contains (`position()`), no LIKE escaping |
| `Permission` / `Role` | `com.example.common.security.Permission` / `com.example.user.domain.Role` | house-review AUTHZ-01, AUTHZ-02: every generated handler declares `@Requires(Permission.<ENTITY>_<ACTION>[_OWN])` (or `@SignedIn` for the dropdown, `@Public` never without the owner's word); the constants and each role's lines are generated with the entity, from the developer's answers (Step 1, *Permissions*), recorded in *Decisions per entity*. Never a role annotation or a `hasRole` string |
| `Requires` / `SignedIn` / `Public` | `com.example.common.security.*` | The three handler declarations (AUTHZ-03) |

## Error contract (house-review error-contract.md; created by `/crud-gen bootstrap`)

| Key | Value | Notes |
|---|---|---|
| `ErrorCode` | `com.example.common.exception.ErrorCode` | Enum; generated codes are added here |
| `AppException` | `com.example.common.exception.AppException` | `of` / `supply` / `withCause` |
| `messagesFile` | `src/main/resources/messages/message_en.properties` | Every locale file gets the new keys |
| `constraintCodeMap` | `<module>/<Module>ConstraintErrorCodes` | A `ConstraintErrorCodes` bean per module maps its unique / FK constraint names to ErrorCodes; the handler aggregates them |

## Paging and lists

| Key | Value | Notes |
|---|---|---|
| `cursorLists` | `all lists not in offsetLists` | Keyset paging, the default (house-review REST-08's consumer rule): `CursorFilter`, `CursorWrapper`, `pageInfo {nextCursor, hasMore}`, no count |
| `offsetLists` | `none` | Offset lists by REST-08's consumer rule (SKILL.md Step 3), as **entity class names** (`TodoList`), each with its consumer: `PageFilter`, `PaginationWrapper`, count on a full page |
| `CursorFilter` / `CursorWrapper` / `Cursor` / `Seek` / `CursorBatch` | `com.example.common.pagination.CursorFilter` / `…wrapper.CursorWrapper` / `…pagination.Cursor`, `…pagination.Seek` | Cursor lists |
| `PageFilter` / `PaginationWrapper` / `OffsetPages` | `com.example.common.pagination.PageFilter` / `…wrapper.PaginationWrapper` / `…pagination.OffsetPages` | Offset lists |
| `Paging` | `com.example.common.pagination.Paging` | Default sizes from configuration (`offset(filter, sort)`, `cursorSize(filter)`) |

## Validation, ownership, ids

| Key | Value | Notes |
|---|---|---|
| `noEmojiFields` | `none` | The only fields that get `{{NoEmoji}}` (codes, identifiers) |
| `idStrategy` | `uuidv7` | `uuidv7` (Hibernate `@UuidGenerator(style = VERSION_7)`, app-assigned) or `tsid` |
| `instances` | `multiple` | `single` or `multiple`, same value as the review profile (dropdown caching) |
| `moduleContracts` | `api-facades` | Or `service-entity-methods`; same value as the review profile |
| `repositoryAccess` | `aggregate-service` | ARCH-15: only the aggregate's service uses its repositories; mappers receive resolved entities |
| `aggregates` | `none` | Same value as the review profile: `Root (OwningService): Member, ...` |
| `requestSuffix` | `Request` | `<Entity><Action>{{requestSuffix}}`: `Request` (default), `Payload` or `Command`, one per project |
| `migrationsDir` / `changesetAuthor` | `src/main/resources/db/changelog/changes` / `dev` | Pre-flight changesets go here |

## Tests and build

| Key | Value | Notes |
|---|---|---|
| `testRoot` | `src/test/java` | |
| `testBase` | `com.example.support.IntegrationTest` | One shared context |
| `buildCommand` | `./mvnw -B verify` | Step 7: the full gate command (every test, ArchUnit, the coverage minimum and static analysis, house-review CFG-11), never `test` alone: green means this command; include the JDK / env prefix |

## Dropdown

| Key | Value | Notes |
|---|---|---|
| `dropdownAnnotation` | `com.example.common.dropdown.DropdownCandidate` | House annotation (references/dropdown.md); created by bootstrap |
| `DropdownItem` | `com.example.common.dropdown.DropdownItem` | Record `{value, label}`: one shape for every dropdown |
| `DropdownFilter` | `com.example.common.dropdown.DropdownFilter` | Extends `CursorFilter` (`size`, `after`) with `status`, `sortDirection`, `query`; subclassed for the annotation's mandatory and optional filters |
| `dropdownSecurity` | `@SignedIn` + `for` | house-review AUTHZ-08: the handler is `@SignedIn`, the service checks the required `for=<Permission>` (`DropdownFor.check`: 403 unless held, 400 `DROPDOWN_REACH_UNSUPPORTED` for a reach it doesn't list) and narrows to its reach in the same query |
| `authorization` | `permissions` | house-review AUTHZ-xx: `permissions` (Level 1: a new permission is a constant and a line in each `Role` that gets it) or `roles-piece` (Level 2: also its grant changeset for the built-in roles the developer names, AUTHZ-12) |

## Media

| Key | Value | Notes |
|---|---|---|
| `mediaApi` | `none` | Package of the media piece's facade (`Uploads`, `UploadPurpose`, `MediaFileView`), e.g. `com.example.media.api`; `none` without the `media` piece (references/media.md) |

## Tenancy

| Key | Value | Notes |
|---|---|---|
| `tenancy` | `none` | `none`, `row` or `schema`, as bootstrap chose (house-review `references/tenancy.md`). `schema`: generated tables go into the tenant changelog (`db/changelog/tenant.yaml`), carry no `tenant_id`, and no `@Table` or native query names a schema; each entity also gets its cross-tenant test class. `row`: one master, tenant entities extend `TenantOwnedEntity`, each tenant table's changeset has `tenant_id`, tenant-carrying keys and its forced policy (Step 2's row-mode table); each entity also gets its cross-tenant test class |

## Deviations

<!--
Free text. Anything this project does differently from the default crud-gen pattern, e.g.
"services are interface + Impl", "controllers are public", "use records for DTOs".
These override the skill's defaults. Leave empty to follow the pattern exactly.
-->

## Decisions per entity

<!--
One section per generated entity, "### <Entity> (<date>)", with the owner's answers to the skill's
questions and their reasons: aggregate root or member, owned or shared and who writes and reads, its
permissions (the actions, their reach, which roles get each, the prerequisites) and its policy's rules, the list
kind and its consumer, retired or deleted, how a person is erased, kept or accumulating (and for accumulating
rows the period and the column the purge reads), each picked parent's picker, the
answers to Step 1's "Ask, don't guess" list (each marked assumed when nobody could be asked), the open
items. A choice no house rule forbids is recorded here and in the code's javadoc, never as a
review-profile Conventions row (that reads as an override of a rule).
-->

## Fallbacks (reference, don't edit)

- `NotXss` `escape-on-output` and `NoEmoji` `none` → those annotations are dropped from the validation table.
- `AuditBase` `none` → no audit fields in Response or mapper.
- `PaginationWrapper` `none` → run `/crud-gen bootstrap` (it creates `PaginationWrapper` +
  `PageInfo`); never fall back to `ListWrapper` for a paged list.
- Create uses `saveAndFlush` (an app-assigned id plus a null `@Version` means persist); update
  changes the managed entity and calls `flush()`. Both flush so the response carries `version` and
  the audit fields.
- Error-contract keys missing → run `/crud-gen bootstrap` first (never hand-write
  "not found" strings).
- `CollectionUtils` = Spring's → `isNotEmpty(x)` becomes `!CollectionUtils.isEmpty(x)`.
- `Limits` missing → run `/crud-gen bootstrap` (never fall back to literal `@Size(max = N)`).
- `modules` `none` → no `{module}` segment; Step 1's module paragraph is skipped.
- `tenancy` `none` → single-tenant code, one Liquibase master; no cross-tenant tests (owner isolation
  still has its row 15 tests).
- `tenancy` `row` → Step 2's row-mode pre-flight (TenantOwnedEntity, `tenant_id`, the forced policy,
  tenant-carrying keys), one master; tests rows 15b and 15c.
- `mediaApi` `none` → Step 5 skipped (offer bootstrap's `media` piece when the records carry files). Dropdown types missing → run `/crud-gen bootstrap` (never invent
  a per-entity dropdown shape).
- No response wrapper in the project → ask the user (create minimal wrappers vs. return DTOs).
