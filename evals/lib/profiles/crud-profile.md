# CRUD profile

<!--
Written by scripts/eval.sh as /crud-gen bootstrap would leave it for a project copied with @PIECES@ under
@ROOT_PACKAGE@; the owner confirmed it. Placeholders (@...@) are filled by the harness.
-->

## Packages

| Key | Value | Notes |
|---|---|---|
| `appPackage` | `@ROOT_PACKAGE@.{module}` | |
| `modules` | `@MODULE_NAMES@` | |

## Types

| Key | Value | Notes |
|---|---|---|
| `idType` | `UUID` | |
| `urlPrefix` | `/api/{version}` | |
| `ObjectWrapper` | `@ROOT_PACKAGE@.common.wrapper.ObjectWrapper` | |
| `objectFactory` | `ObjectWrapper.withObject` | |
| `BoundedListWrapper` | `@ROOT_PACKAGE@.common.wrapper.BoundedListWrapper` | |
| `BaseRepository` | `org.springframework.data.jpa.repository.JpaRepository` | |
| `NotXss` | `escape-on-output` | JSON API; clients render text as text |
| `NoEmoji` | `none` | |
| `AuditBase` | `@ROOT_PACKAGE@.common.persistence.AuditableEntity` | |
| `auditFields` | `createdAt:Instant, updatedAt:Instant, createdBy:UUID, updatedBy:UUID, version:Long` | |
| `CollectionUtils` | `org.springframework.util.CollectionUtils` | |
| `MapWrapper` | `@ROOT_PACKAGE@.common.wrapper.MapWrapper` | |
| `Limits` / `MaxLength` / `ConfiguredMax` | `@ROOT_PACKAGE@.common.limits.*` | |
| `TextSearch` | `@ROOT_PACKAGE@.common.pagination.TextSearch` | |
| `Permission` / `Role` | `@ROOT_PACKAGE@.common.security.Permission` / `@ROOT_PACKAGE@.user.domain.Role` | |
| `Requires` / `SignedIn` / `Public` | `@ROOT_PACKAGE@.common.security.*` | |

## Error contract

| Key | Value | Notes |
|---|---|---|
| `ErrorCode` | `@ROOT_PACKAGE@.common.exception.ErrorCode` | |
| `AppException` | `@ROOT_PACKAGE@.common.exception.AppException` | |
| `messagesFile` | `src/main/resources/messages/message_en.properties` | |
| `constraintCodeMap` | `<module>/<Module>ConstraintErrorCodes` | |

## Paging and lists

| Key | Value | Notes |
|---|---|---|
| `cursorLists` | `all lists not in offsetLists` | |
| `offsetLists` | `none` | |
| `CursorFilter` / `CursorWrapper` / `Cursor` / `Seek` / `CursorBatch` | `@ROOT_PACKAGE@.common.pagination.CursorFilter` / `@ROOT_PACKAGE@.common.wrapper.CursorWrapper` / `@ROOT_PACKAGE@.common.pagination.Cursor`, `@ROOT_PACKAGE@.common.pagination.Seek`, `@ROOT_PACKAGE@.common.pagination.CursorBatch` | |
| `PageFilter` / `PaginationWrapper` / `OffsetPages` | `@ROOT_PACKAGE@.common.pagination.PageFilter` / `@ROOT_PACKAGE@.common.wrapper.PaginationWrapper` / `@ROOT_PACKAGE@.common.pagination.OffsetPages` | |
| `Paging` | `@ROOT_PACKAGE@.common.pagination.Paging` | |

## Validation, ownership, ids

| Key | Value | Notes |
|---|---|---|
| `noEmojiFields` | `none` | |
| `idStrategy` | `uuidv7` | |
| `instances` | `multiple` | |
| `moduleContracts` | `api-facades` | |
| `repositoryAccess` | `aggregate-service` | |
| `aggregates` | `@AGGREGATES@` | |
| `requestSuffix` | `Request` | |
| `migrationsDir` / `changesetAuthor` | `src/main/resources/db/changelog/changes` / `dev` | |

## Tests and build

| Key | Value | Notes |
|---|---|---|
| `testRoot` | `src/test/java` | |
| `testBase` | `@ROOT_PACKAGE@.support.IntegrationTest` | |
| `buildCommand` | `./mvnw -o -B verify` | JDK 25 (`JAVA_HOME` is set), Docker reachable; offline: every dependency is in the local repository |

## Dropdown

| Key | Value | Notes |
|---|---|---|
| `dropdownAnnotation` | `@ROOT_PACKAGE@.common.dropdown.DropdownCandidate` | |
| `DropdownItem` | `@ROOT_PACKAGE@.common.dropdown.DropdownItem` | |
| `DropdownFilter` | `@ROOT_PACKAGE@.common.dropdown.DropdownFilter` | |
| `dropdownSecurity` | `@SignedIn` + `for` | |
| `authorization` | `@AUTHORIZATION@` | |

## Media

| Key | Value | Notes |
|---|---|---|
| `mediaApi` | `@MEDIA_API@` | |

## Tenancy

| Key | Value | Notes |
|---|---|---|
| `tenancy` | `none` | |

## Bootstrap

| Key | Value | Notes |
|---|---|---|
| `pieces` | `@PIECES@` | |
| `skillsRelease` | `@SKILLS_RELEASE@` | |

## Deviations

## Decisions per entity
