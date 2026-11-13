# CRUD profile — espranza IB module

<!--
Reference example: a profile for an existing (pre-house-standard) project, the Espranza IB module.
Copy to <ib-project>/.claude/crud-profile.md. Values marked (verify) were not stated in the
original ib-crud skill: confirm them in the codebase on first run.
Keys set to `none` (error contract, PaginationWrapper, dropdown filter) don't exist in that
codebase yet: the first run offers `/crud-gen bootstrap` to add them, it doesn't fall back to old
patterns.
-->

## Packages

| Key | Value |
|---|---|
| `sourceRoot` | `src/main/java` |
| `entityPackage` | `com.espranza.shared.entities.ib.{module}` |
| `appPackage` | `com.espranza.ib.{module}` |
| `modules` | `shared, pyp, dp` (`shared` = master data across IB programmes, `pyp` = Primary Years Programme, `dp` = Diploma Programme) |
| `moduleRules` | `pyp` and `dp` MAY import from `shared`. `shared` MUST NEVER import from `pyp` or `dp`. `pyp` and `dp` MUST NEVER import from each other. Programme-filtered reads of shared data go in the programme's service, composed by a controller in `com.espranza.ib.controller` (canonical example: ActionController / PypUnitPlanActionService). |

## Types

| Key | Value |
|---|---|
| `idType` | `Long` |
| `urlPrefix` | `/v1` |
| `ObjectWrapper` | `com.espranza.shared.dto.wrapper.ObjectWrapper` |
| `objectFactory` | `ObjectWrapper.withObject` |
| `objectSetter` | `setResponse` |
| `ListWrapper` | `com.espranza.shared.dto.wrapper.ListWrapper` |
| `listFactory` | `ListWrapper.withList` |
| `extraDetails` | `addExtraDetails` |
| `BaseRepository` | `BaseJpaRepository` (verify FQN) |
| `NotXss` | `com.espranza.dynamicform.validations.NotXss` |
| `NoEmoji` | `com.espranza.dynamicform.validations.NoEmoji` (only on `noEmojiFields`) |
| `AuditBase` | `AuditSupport` (every IB entity extends it) |
| `auditFields` | `createdDate:LocalDateTime, lastModifiedDate:LocalDateTime, createdBy:Long, lastModifiedBy:Long` |
| `CollectionUtils` | `org.apache.commons.collections4.CollectionUtils` |
| `metamodel` | `true` |
| `controllerSecurity` | `none` |

## Error contract, paging, tests

| Key | Value |
|---|---|
| `ErrorCode` / `ErrorCodeMapper` / `AppException` / `messagesFile` | `none`: the project throws `ApplicationException.badRequest` (`com.espranza.shared.exception.custom.ApplicationException`); run bootstrap to add the house contract |
| `PaginationWrapper` / `pagedListFactory` | `none`: run bootstrap |
| `PageFilter` / `Paging` | (verify) |
| `noEmojiFields` | (verify) e.g. codes |
| `idStrategy` | `long-sequence` (existing Long ids) |
| `testBase` / `loginAs` | (verify) |

## Dropdown

| Key | Value |
|---|---|
| `dropdownAnnotation` | `com.espranza.shared.annotate.DropdownCandidate` (the original of the house annotation) |
| `DropdownItem` | `com.espranza.shared.dto.response.DropdownItem` (a class today; the house standard is a record with the same JSON) |
| `DropdownFilter` | `none`: run bootstrap to add the generic parameters |
| `dropdownPackage` | `com.espranza.shared.dto.response` |
| `dropdownSecurity` | `@PreAuthorize("isAuthenticated()")` |

## Media

| Key | Value |
|---|---|
| `MediaFile` | `com.espranza.shared.entities.MediaFileS3` |
| `FileIdsPayload` | `com.espranza.s3.dto.request.FileIdsPayloadS3` |
| `MediaFileDetail` | `com.espranza.s3.dto.response.MediaFileDetailS3` |
| `MediaService` | `com.espranza.s3.service.MediaService` |
| `MediaFileCreateRequest` | `com.espranza.s3.dto.internal.MediaFileCreateRequest` |
| `mediaCreateRequestFields` | `.withS3ObjectKey(objectKey).withOwnerId(CurrentUserDetailProvider.authUserId().toString()).withTenantId(Objects.requireNonNull(TenantContext.getTenantId()))`, imports `com.espranza.auth.service.CurrentUserDetailProvider`, `com.espranza.shared.tenant.tenent.TenantContext` |
| `skipSave` | `FileIdsPayloadS3Utils.skipSavingInS3` (import `com.espranza.shared.utils.FileIdsPayloadS3Utils`) |
| `skipDelete` | `FileIdsPayloadS3Utils.skipDeletingInS3` |
| `mediaConstants` | `src/main/java/com/espranza/s3/constant/S3RelatedConstants.java` (class `S3RelatedConstants`) |
| `mediaConstraintsConfig` | `src/main/resources/application.properties`, prefix `media-file.constraints[N]` |
| `mediaObjectKeyGetter` | `getS3ObjectKey` |

## Deviations

None. Follow the pattern exactly.
