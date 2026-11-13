# Media file handling (Step 5)

Applies when the entity owns a `@OneToMany` collection to a media join entity (an entity linking
the parent to `{{MediaFile}}`, optionally with a `Purpose` enum).

## How to detect it

Look for a field on the entity like:
```java
@OneToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY,
           mappedBy = "parent", orphanRemoval = true)
@SQLRestriction("purpose = 'SOME_PURPOSE'")   // optional; scopes by purpose
private Set<XMedia> xMediaCollection = new LinkedHashSet<>();
```

Also look for existing helper methods on the entity:
```java
public void addXMedia({{MediaFile}} mediaFile) { ... }
public void removeXMedia(String objectKey) { ... }
```

If those helpers are missing, **add them to the entity** before going further.

---

## 5.1 Entity helper methods (add to the parent entity if absent)

```java
public void addXMedia({{MediaFile}} mediaFile) {
    XMedia media = new XMedia();
    media.setMediaFile(mediaFile);
    media.setParent(this);
    // media.setPurpose(XMedia.Purpose.SOME_PURPOSE);  // only if the join entity has a Purpose enum
    xMediaCollection.add(media);
}

public void removeXMedia(String objectKey) {
    xMediaCollection.removeIf(
        media -> media.getMediaFile().{{mediaObjectKeyGetter}}().equals(objectKey)
    );
}
```

---

## 5.2 Media constants: add two

**File:** `{{mediaConstants}}`

Add at the bottom (before the closing `}`):
```java
public static final String ENTITY_NAME = "ENTITY_NAME";           // module name
public static final String ENTITY_NAME_MEDIA = "ENTITY_NAME_MEDIA"; // file usage
```

Convention: `MODULE_NAME` is the domain concept in UPPER_SNAKE_CASE; `FILE_USAGE` is
`MODULE_NAME_PURPOSE`.

---

## 5.3 Config: add a media-file constraint entry

**File / prefix:** `{{mediaConstraintsConfig}}`

Every new media type **must** be registered here. The upload-URL endpoint uses it to validate
file type, size, and count before it issues upload URLs.

Find the last `[N]` index used with this prefix and append the next block at `N+1`, copying the
shape of the existing entries exactly. Example (properties style):

```properties
media-file.constraints[N].module=ENTITY_NAME
media-file.constraints[N].file-usage=ENTITY_NAME_MEDIA
media-file.constraints[N].allowed-content-types=image        # image / pdf / video / pdf,image / etc.
media-file.constraints[N].min-file-size=1KB
media-file.constraints[N].max-file-size=10MB                 # adjust per use case
media-file.constraints[N].min-files=1
media-file.constraints[N].max-files=1                        # 1 for single-file (cover image, logo); more for galleries
media-file.constraints[N].allowed-roles=any
```

`module` and `file-usage` must exactly match the string values of the two constants from 5.2.

---

## 5.4 Payload: embed `{{FileIdsPayload}}` for inline media management

The create/update payload carries a `{{FileIdsPayload}}` field, so the client can supply (or
replace) media in the same request as the other fields. **Never use a plain `String` key; always
use `{{FileIdsPayload}}`.**

```java
// a component of the EntityNamePayload record:
{{FileIdsPayload}} xMediaPayload   // optional, no @Valid; the payload handles its own null-safety
```

`{{FileIdsPayload}}` contains:
- `newIds`: object keys just uploaded (to persist)
- `deletedIds`: object keys to remove
- `oldIds`: previously saved keys (client-side bookkeeping, not used on the backend)

---

## 5.5 Mapper: accept a pre-resolved `Set<{{MediaFile}}>`

The mapper **must not** call any storage or repository logic. The service first resolves
`{{FileIdsPayload}}` → `Set<{{MediaFile}}>`, then passes the result to the mapper.

Update `toEntity` and `update` to take a second parameter:

```java
public EntityName toEntity(EntityNamePayload payload, Set<{{MediaFile}}> xMediaFiles) {
    EntityName entity = new EntityName();
    // ... set scalar fields ...
    for ({{MediaFile}} file : xMediaFiles) {
        entity.addXMedia(file);
    }
    return entity;
}

public void update(EntityName entity, EntityNamePayload payload, Set<{{MediaFile}}> xMediaFiles) {
    // ... set mutable scalar fields (skip updatable=false FKs) ...
    for ({{MediaFile}} file : xMediaFiles) {
        entity.addXMedia(file);
    }
}
```

---

## 5.6 Service additions

Add `{{MediaService}} mediaService` to the service's `@RequiredArgsConstructor` fields.

### Two private helpers (shared by create, update, and the PATCH endpoint)

```java
private Set<{{MediaFile}}> saveXMediaIfProvided({{FileIdsPayload}} xMediaPayload) {
    if ({{skipSave}}(xMediaPayload)) {
        return Set.of();
    }
    Set<{{MediaFile}}> result = new HashSet<>();
    for (String objectKey : xMediaPayload.getNewIds()) {
        result.add(saveMediaFile(objectKey));
    }
    return result;
}

private void removeExistingXMediaIfRequested(EntityName entity, {{FileIdsPayload}} xMediaPayload) {
    if ({{skipDelete}}(xMediaPayload)) {
        return;
    }
    for (String objectKey : xMediaPayload.getDeletedIds()) {
        entity.removeXMedia(objectKey);
    }
}
```

> ⚠️ **The delete guard MUST be `{{skipDelete}}`, never `{{skipSave}}`.**
> `{{skipSave}}` checks `newIds` (the *save* path); `{{skipDelete}}` checks `deletedIds` (the
> *delete* path). A delete-only request (the client removes media without uploading anything)
> carries `deletedIds` but **no** `newIds`. Guarding removal with `{{skipSave}}` short-circuits
> and silently skips it: the join row stays in the collection, no orphan is produced, and **no
> `DELETE` is ever issued**. `{{skipDelete}}` is null-safe and already covers the empty
> `deletedIds` case.
>
> If the project has no `{{skipDelete}}` helper, write the guard as
> `Objects.isNull(xMediaPayload) || CollectionUtils.isEmpty(xMediaPayload.getDeletedIds())`.
> Never reuse the save check.

### No storage call inside the transaction (house-review DATA-01, REL-06)

Check whether `{{MediaService}}.save` (or anything it calls) talks to the object store: an
existence or size check, a copy, a tagging call.
- **If it does,** verify the keys **before** the transaction, then do only database work inside
  it. The service's public `create` / `update` / `manageXMedia` become non-transactional
  orchestrators (`@Transactional(propagation = Propagation.NOT_SUPPORTED)`), running:
  1. `{{mediaVerify}}(newIds)`, with no transaction (a storage outage answers 503
     `MEDIA_STORAGE_UNAVAILABLE` + `Retry-After`, and nothing is written);
  2. a `TransactionTemplate` (or a separate `@Transactional` bean method) that saves the
     `{{MediaFile}}` rows and the entity together.

  That way a storage failure after commit can't leave an entity pointing at missing files
  (MEDIA-LINK-LOST-AFTER-COMMIT).
- **If `save` only records rows** (no storage I/O), the code below is correct as is.

Building the response with `mediaFileDetail` must not call storage either (pre-signing a URL is
local and fine; fetching metadata is not).

### Updated `create` and `update` (when `save` records rows only)

```java
@Transactional
public {{ObjectWrapper}}<EntityNameResponse> create(EntityNamePayload payload) {
    Set<{{MediaFile}}> xMediaFiles = saveXMediaIfProvided(payload.xMediaPayload());
    EntityName entity = mapper.toEntity(payload, xMediaFiles);
    EntityName saved = repository.saveAndFlush(entity);
    return {{objectFactory}}(mapper.toResponse(saved));
}

@Transactional
public {{ObjectWrapper}}<EntityNameResponse> update(ID id, EntityNameUpdatePayload payload) {
    EntityName entity = getEditableEntityName(id, payload.version());
    removeExistingXMediaIfRequested(entity, payload.xMediaPayload());
    Set<{{MediaFile}}> xMediaFiles = saveXMediaIfProvided(payload.xMediaPayload());
    mapper.update(entity, payload, xMediaFiles);
    repository.flush();
    return {{objectFactory}}(mapper.toResponse(entity));
}
```

### Standalone PATCH handler (dedicated media sub-resource endpoint)

```java
@Transactional
public void manageXMedia(ID entityId, {{FileIdsPayload}} payload) {
    if ({{skipSave}}(payload) && {{skipDelete}}(payload)) {
        return;
    }

    EntityName entity = getEntityName(entityId);

    removeExistingXMediaIfRequested(entity, payload);

    if (!{{skipSave}}(payload)) {
        for (String objectKey : payload.getNewIds()) {
            entity.addXMedia(saveMediaFile(objectKey));
        }
    }

    repository.flush();
}
```

### GET handler: returns a pre-signed URL

```java
public {{ObjectWrapper}}<{{MediaFileDetail}}> xMediaDetail(ID entityId) {
    EntityName entity = getEntityName(entityId);

    Set<XMedia> mediaFiles = entity.getXMediaCollection();
    if (CollectionUtils.isEmpty(mediaFiles)) {
        return {{objectFactory}}(null);   // absent is not an error; caller gets a null response
    }

    XMedia media = IterableUtils.get(mediaFiles, 0);
    return {{objectFactory}}(mediaService.mediaFileDetail(media.getMediaFile()));
}
```

(`IterableUtils` is from commons-collections4; without it, use `mediaFiles.iterator().next()`.)

### Shared private `saveMediaFile`

```java
private {{MediaFile}} saveMediaFile(String objectKey) {
    {{MediaFileCreateRequest}} request = {{MediaFileCreateRequest}}.builder()
            // {{mediaCreateRequestFields}}, with the object key substituted
            .withModuleName(<MediaConstants>.ENTITY_NAME)
            .withFileUsage(<MediaConstants>.ENTITY_NAME_MEDIA)
            .build();
    return mediaService.save(request);
}
```

Import every FQN used above from the profile, plus `java.util.HashSet`, `java.util.Objects`,
`java.util.Set`, and `{{CollectionUtils}}`.

---

## 5.7 Controller additions: two new endpoints

Declare both in `EntityNameApi` with `@Operation` / `@ApiResponse` (200, 204, 404
`ERROR_PREFIX_NOT_FOUND`, 503 `MEDIA_STORAGE_UNAVAILABLE`) and implement them in the controller:

```java
@GetMapping("{{urlPrefix}}/entity-names/{entityId}/media-name")
@Override
public {{ObjectWrapper}}<{{MediaFileDetail}}> xMediaDetail(@PathVariable ID entityId) {
    return service.xMediaDetail(entityId);
}

@PatchMapping("{{urlPrefix}}/entity-names/{entityId}/media-name")
@ResponseStatus(HttpStatus.NO_CONTENT)
@Override
public void manageXMedia(@PathVariable ID entityId,
                         @RequestBody {{FileIdsPayload}} payload) {
    service.manageXMedia(entityId, payload);
}
```

Tests (in the entity's integration test class): attach, then detail (presence); delete-only
request removes; an unknown entity → 404; a wrong role → 403.

For the `media-name` URL segment, use the purpose or collection name in kebab-case
(e.g. `cover-image`, `subject-icon`, `fee-receipt`).

No `@Valid` on `{{FileIdsPayload}}`; the payload handles its own null-safety.

---

## 5.8 The three-step frontend contract (for reference)

1. **Get a pre-signed upload URL** from the project's upload-URL endpoint. The frontend receives
   a URL plus form fields.
2. **Upload directly to storage.** The backend isn't in this path.
3. **Send `{{FileIdsPayload}}`**, either nested in the create/update payload OR directly to the
   PATCH sub-resource endpoint, with `newIds` / `deletedIds` / `oldIds`.

---

## Checklist

- [ ] Media constants file has two new constants; the constraints config has a new `[N]` block whose `module`/`file-usage` match them; the entity has `add/remove` helpers
- [ ] Payload has `{{FileIdsPayload}} xMediaPayload` (NOT a plain `String` key)
- [ ] Mapper `toEntity` and `update` take `Set<{{MediaFile}}>` as a second parameter, with no storage logic inside the mapper
- [ ] Service has `saveXMediaIfProvided` + `removeExistingXMediaIfRequested` private helpers, plus `manageXMedia` + `xMediaDetail` + `saveMediaFile`
- [ ] `create` and `update` return `{{ObjectWrapper}}<EntityNameResponse>` wrapping `mapper.toResponse(...)`
- [ ] `create` calls `saveXMediaIfProvided` before `mapper.toEntity`
- [ ] `update` calls `removeExistingXMediaIfRequested`, then `saveXMediaIfProvided`, before `mapper.update`
- [ ] `removeExistingXMediaIfRequested` guards with `{{skipDelete}}` (based on deletedIds), **never** `{{skipSave}}`
- [ ] `manageXMedia` returns early only when there is nothing to save AND nothing to delete
- [ ] `xMediaDetail` returns `{{ObjectWrapper}}<{{MediaFileDetail}}>`: it wraps `null` when the collection is empty and the detail otherwise
- [ ] Controller `xMediaDetail` returns `service.xMediaDetail(entityId)` directly (no wrapping)
