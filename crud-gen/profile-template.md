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
| `sourceRoot` | `src/main/java` | Multi-module build: include the module dir, e.g. `app/src/main/java` |
| `entityPackage` | `com.example.entities.{module}` | Where entities live. Keep `{module}` only if `modules` is set |
| `appPackage` | `com.example.{module}` | Base package for generated layers (`.controller`, `.service`, ...) |
| `modules` | `none` | e.g. `shared, sales, billing`, the values `{module}` can take |
| `moduleRules` | `none` | Allowed import directions between modules, e.g. "sales/billing may import shared; shared imports neither; sales and billing never import each other" |

## Types

| Key | Value | Notes |
|---|---|---|
| `idType` | `Long` | `Long`, `UUID`, ... |
| `urlPrefix` | `/v1` | Prefix every endpoint path starts with, e.g. `/api/v1` |
| `ObjectWrapper` | `com.example.dto.ObjectWrapper` | Fully-qualified single-object response wrapper |
| `objectFactory` | `ObjectWrapper.withObject` | Static factory that wraps one object (must accept `null`) |
| `objectSetter` | `setResponse` | Setter that replaces the wrapped object (build-first pattern) |
| `ListWrapper` | `com.example.dto.ListWrapper` | Fully-qualified wrapper for lists **bounded by nature** (dropdowns, small fixed sets). Never for paginated lists |
| `listFactory` | `ListWrapper.withList` | Static factory that wraps a list |
| `extraDetails` | `addExtraDetails` | Method to attach extra key/value data to a wrapper, or `none` |
| `BaseRepository` | `org.springframework.data.jpa.repository.JpaRepository` | Project base repository interface (FQN) |
| `NotXss` | `none` | FQN of an anti-XSS constraint annotation |
| `NoEmoji` | `none` | FQN of a no-emoji constraint annotation |
| `AuditBase` | `none` | `@MappedSuperclass` that entities extend for audit columns |
| `auditFields` | `none` | Ordered `name:Type` list, e.g. `createdDate:LocalDateTime, lastModifiedDate:LocalDateTime, createdBy:Long, lastModifiedBy:Long` |
| `CollectionUtils` | `org.apache.commons.collections4.CollectionUtils` | Or `org.springframework.util.CollectionUtils` if commons-collections4 is absent |
| `metamodel` | `true` | `true` if a JPA metamodel annotation processor (`hibernate-jpamodelgen`) is configured |
| `controllerSecurity` | `none` | Annotation existing controllers carry, e.g. `@PreAuthorize("hasRole('ADMIN')")`, or `none` |

## Error contract (house-review error-contract.md; created by `/crud-gen bootstrap`)

| Key | Value | Notes |
|---|---|---|
| `ErrorCode` | `com.example.common.exception.ErrorCode` | Enum; generated codes are added here |
| `ErrorCodeMapper` | `com.example.common.exception.ErrorCodeHttpStatusMapper` | `EnumMap` entries are added here |
| `AppException` | `com.example.common.exception.AppException` | `of` / `supply` / `withCause` |
| `messagesFile` | `src/main/resources/messages/message_en.properties` | Every locale file gets the new keys |
| `constraintCodeMap` | `GlobalExceptionHandler#CONSTRAINT_CODES` | Where unique / FK constraint names map to ErrorCodes |
| `staleErrorCode` | `OPTIMISTIC_LOCK_CONFLICT` | Thrown when the version from the client is stale |

## Paging and lists

| Key | Value | Notes |
|---|---|---|
| `PageFilter` | `com.example.common.pagination.PageFilter` | Base class of every Filter |
| `PageRequests` | `com.example.common.pagination.PageRequests` | Caps the size |
| `CriteriaPages` | `com.example.common.pagination.CriteriaPages` | Count skipped on a partial page |
| `PaginationWrapper` | `com.example.common.wrapper.PaginationWrapper` | Paginated lists: `response` + an explicit `pageInfo` (in the OpenAPI schema) |
| `pagedListFactory` | `PaginationWrapper.fromPage` | |
| `cursorLists` | `none` | Entities whose list uses cursor paging |
| `CursorFilter` | `none` | Cursor filter base class (created by bootstrap when `cursorLists` is set) |
| `dropdownMaxItems` | `500` | Cap on dropdown queries |

## Validation, ownership, ids

| Key | Value | Notes |
|---|---|---|
| `noEmojiFields` | `none` | The only fields that get `{{NoEmoji}}` (codes, identifiers) |
| `ownerField` | `none` | Field that makes an entity user-owned (e.g. `createdBy`): another user's record answers 404 |
| `idStrategy` | `uuidv7` | `uuidv7` (Hibernate `UuidVersion7Strategy`, app-assigned) or `tsid` |
| `moduleContracts` | `service-entity-methods` | Or `api-facades` (modulith); same value as the review profile |
| `repositoryAccess` | `service, mapper` | Whether mappers may use `getReferenceById` |
| `migrationsDir` / `changesetAuthor` | `src/main/resources/db/changelog/changes` / `dev` | Pre-flight changesets go here |

## Tests and build

| Key | Value | Notes |
|---|---|---|
| `testRoot` | `src/test/java` | |
| `testBase` | `com.example.support.IntegrationTest` | One shared context |
| `httpClient` | `MockMvc` | Or `RestAssured` |
| `loginAs` / `createUser` | `loginAs(Role)` / `createUser(...)` | Helpers on the test base |
| `roleAllowed` | per controller | The lowest role allowed; the roles below it get the 403 tests |
| `buildCommand` | `./mvnw -B verify` | Step 7; include the JDK / env prefix |

## Dropdown

| Key | Value | Notes |
|---|---|---|
| `dropdownAnnotation` | `com.example.common.dropdown.DropdownCandidate` | House annotation (references/dropdown.md); created by bootstrap |
| `DropdownItem` | `com.example.common.dropdown.DropdownItem` | Record `{value, label}`: one shape for every dropdown |
| `DropdownFilter` | `com.example.common.dropdown.DropdownFilter` | Generic params (`status`, `sortDirection`, `limit`, `query`); subclassed for the annotation's mandatory and optional filters |
| `dropdownPackage` | `com.example.common.dropdown` | Where bootstrap puts the dropdown types |
| `dropdownSecurity` | `@PreAuthorize("isAuthenticated()")` | The user's standard; ask when the resource is more restricted |

## Media

Set every key to `none` if the project has no file/media module.

| Key | Value | Notes |
|---|---|---|
| `MediaFile` | `none` | FQN of the persisted media-file entity (e.g. `MediaFileS3`) |
| `FileIdsPayload` | `none` | FQN of the payload carrying `newIds` / `deletedIds` / `oldIds` |
| `MediaFileDetail` | `none` | FQN of the response DTO with a pre-signed/download URL |
| `MediaService` | `none` | FQN of the service with `save(request)` and `mediaFileDetail(mediaFile)` |
| `MediaFileCreateRequest` | `none` | FQN of the create-request builder passed to `MediaService.save` |
| `mediaCreateRequestFields` | `none` | Builder calls to make, e.g. `.withS3ObjectKey(key).withOwnerId(CurrentUserDetailProvider.authUserId().toString()).withTenantId(Objects.requireNonNull(TenantContext.getTenantId()))` plus their imports |
| `skipSave` | `none` | Null-safe static check "nothing to save" (on `newIds`), e.g. `FileIdsPayloadS3Utils.skipSavingInS3` |
| `skipDelete` | `none` | Null-safe static check "nothing to delete" (on `deletedIds`), e.g. `FileIdsPayloadS3Utils.skipDeletingInS3` |
| `mediaConstants` | `none` | File + class for module-name / file-usage constants, e.g. `src/main/java/.../S3RelatedConstants.java` |
| `mediaConstraintsConfig` | `none` | Config file + key prefix for media constraints, e.g. `src/main/resources/application.properties`, `media-file.constraints[N]` |
| `mediaObjectKeyGetter` | `none` | Getter on `MediaFile` for the object key, e.g. `getS3ObjectKey` |
| `mediaVerify` | `none` | Storage check run **before** the transaction when `MediaService.save` talks to storage (media.md); `none` if `save` only records rows |

## Deviations

<!--
Free text. Anything this project does differently from the default crud-gen pattern, e.g.
"services are interface + Impl", "controllers are public", "use records for DTOs".
These override the skill's defaults. Leave empty to follow the pattern exactly.
-->

## Fallbacks (reference, don't edit)

- `NotXss` / `NoEmoji` `none` → those annotations are dropped from the validation table.
- `AuditBase` `none` → no audit fields in Response or mapper.
- `extraDetails` `none` → keep the build-first structure; just no extra-details call. Page details
  never go in `extraDetails`: they are `PaginationWrapper.pageInfo`.
- `PaginationWrapper` `none` → run `/crud-gen bootstrap` (it creates `PaginationWrapper` +
  `PageInfo`); never fall back to `ListWrapper` for a paged list.
- Create uses `saveAndFlush` (an app-assigned id plus a null `@Version` means persist); update
  changes the managed entity and calls `flush()`. Both flush so the response carries `version` and
  the audit fields.
- Error-contract keys missing → run `/crud-gen bootstrap` first (never hand-write
  "not found" strings).
- `ownerField` `none` → no ownership checks or other-user tests.
- `CollectionUtils` = Spring's → `isNotEmpty(x)` becomes `!CollectionUtils.isEmpty(x)`.
- `metamodel` `false` → offer to add `hibernate-jpamodelgen`; string paths only if declined.
- `modules` `none` → no `{module}` segment, Step 1.5 skipped.
- Media `none` → Step 5 skipped. Dropdown types missing → run `/crud-gen bootstrap` (never invent
  a per-entity dropdown shape).
- No response wrapper in the project → ask the user (create minimal wrappers vs. return DTOs).
