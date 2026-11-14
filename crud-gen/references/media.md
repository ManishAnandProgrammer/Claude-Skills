# Media: records that carry files (Step 5)

Applies when the entity's records carry uploaded files (a todo's attachments, a leave's documents)
and the project has the `media` piece (`{{mediaApi}}` is not `none`). **The rules are house-review
`media.md` (MEDIA-01…14)**; this file says what to generate. The upload itself (`POST /upload-urls`,
the browser's PUT, verification, cleanup) is the media piece's job: generate nothing for it.

**Golden code:** `~/.claude/skills-reference/src/main/java/com/example/app/media/api/`
(`Uploads`, `UploadPurpose`, `MediaFileView`) and, for one owner, the todo attachments:
`todo/domain/TodoAttachment`, `Todo.attach` / `detach`, the attachment fields of
`TodoCreateRequest` / `TodoUpdateRequest`, `TodoService` (create, update, delete), `TodoResponse`
(`attachments`), changeset `0010-todo-attachments.sql` and
`src/test/java/com/example/app/todo/TodoAttachmentsIntegrationTest`. Copy their shape; don't restate it here.

Names below use `<X>` for the files' role on the entity (`Attachment`, `Document`, `Photo`).

## What to generate
1. **Purpose.** A new `UploadPurpose` constant (`<ENTITY>_<X>`, e.g. `TODO_ATTACHMENT`) with the
   roles that may upload for it (MEDIA-03, MEDIA-04): **every role that writes the owning record**,
   since only the uploader can claim the upload (an admin edits employees, so
   `EMPLOYEE_PHOTO("ADMIN")`; ask when the record's subject should change the file too). Roles don't
   include each other: a record that members and their tenant's ADMIN owner both edit is
   `STUDENT_PHOTO("USER", "ADMIN")`. For a
   shared root that is the writing role Step 1 decided, never `USER` while signup is public. A
   changeset (next free number) seeds its `upload_policies` row: min/max size, min/max files,
   content types from `AllowedContentTypes` only (MEDIA-09). Ask the user for the limits; never SVG,
   HTML or XML. With the **project's first purpose**, the media piece's own endpoint tests
   (`UploadUrlIntegrationTest`, `UploadPolicyIntegrationTest`, `PresignedUploadIntegrationTest`)
   stop being skipped: they run against `UploadPurpose.values()[0]` with its role; run them.
2. **Link entity and changeset.** `<Entity><X>` (golden `TodoAttachment`): the owner as a
   `@ManyToOne(fetch = LAZY)` relation (same module), the media file as a plain `UUID mediaFileId`
   column (the media module's: an id with a database foreign key, ARCH-04), audit columns, inserted
   and deleted, never updated. The owner's mapped-by `Set<<Entity><X>>` carries
   `@OptimisticLock(excluded = false)`, so a files-only change moves the owner's version (DATA-30).
   Table `<entity>_<x>s` with foreign keys to the owner and to `media_files` (named
   `fk_<table>_<column>`, each indexed), `UNIQUE (media_file_id)` (one file, one record). A member of
   the owner's aggregate (ARCH-15): no service or controller of its own.
3. **Requests** (golden `TodoCreateRequest`, `TodoUpdateRequest`):
   - create: `Set<@NotBlank @MaxLength("media.upload-key") String> <x>Keys` (the `uploadKey`s from
     `POST /upload-urls`);
   - update: `Set<@NotBlank @MaxLength("media.upload-key") String> add<X>Keys` and
     `Set<@NotNull UUID> remove<X>Ids` (MEDIA-07), never an old/new/deleted list;
   - a `Set`, so a repeated key counts once; each list `@ConfiguredMaxSize("upload-policy.max-files")`
     (the most files any policy may allow: no per-entity duplicate of the limit);
   - **a single-file purpose** (a photo, `maxFiles` 1) keeps the same list shape; the update's
     `@Schema` says that replacing is a remove and an add in one request.
4. **Service** (the owner's create, update and delete, one transaction each, no S3 call: MEDIA-06):
   1. the owner's own checks first. **Update and delete load the owner `FOR NO KEY UPDATE` first**
      (golden `getOwnedTodoForUpdate`), then check the version: before its parents, the claim, the
      release or any other row. Two concurrent file changes then queue at that lock and the second
      answers 409; without it, an owner whose update changes a uniquely indexed column deadlocks
      (503) under concurrent add+remove (MEDIA-07 says why in full);
   2. every `remove<X>Ids` entry must be one of this record's files (404
      `<ENTITY>_<X>_NOT_FOUND`, golden `TODO_ATTACHMENT_NOT_FOUND`; refused, not ignored);
   3. the count range from `Uploads.fileCountRange(purpose)`, only when the request changes files:
      `minFiles ≤ current − removed + added ≤ maxFiles`, where `current` counts every linked file
      whatever its status, else 400 `UPLOAD_FILE_COUNT_OUT_OF_RANGE` (create: `current` is 0);
   4. `Uploads.claim(purpose, keys)` for the added keys **before any other change to the entity**
      (409 `UPLOAD_NOT_AVAILABLE` on any key it can't claim: MEDIA-05): the claim is a native
      query, and Hibernate flushes pending changes before it, which would advance the version
      twice; then attach the link rows;
   5. **a rule of the record on its files** (more than N days of leave needs a document) is the
      owner's own check with its own 400 code (`<ENTITY>_<X>_REQUIRED`), on the final count, when
      its inputs (the fields it reads, or the files) change, before the flush; never a narrowed
      `FileCountRange` (MEDIA-07);
   6. the field changes, advancing the owner's version even when only files changed, then flush;
   7. `Uploads.release` for the removed files after that flush (RELEASED; the cleanup job deletes
      the objects: MEDIA-10), their link rows deleted with them. Deleting the entity releases all
      its files.

   If the entity has a step that needs complete files (submit, approve), that transition checks,
   under the record's lock, that every file is UPLOADED (`Uploads.allUploaded`) and answers 409
   `<ENTITY>_<X>S_INCOMPLETE`; saving a draft doesn't. Its API text says files are checked only
   after their upload URL expired (`app.media.upload-url-expiry`), so the step is refused for at
   least that long after the last upload (MEDIA-06).
5. **Response.** `List<<Entity><X>Response> <x>s` on the entity's response (id, file name, content
   type, size, status as text, read URL): a record of the owning module (golden
   `TodoAttachmentResponse`), mapped from the `MediaFileView`s `Uploads.describe` returns for the
   record's files in one batch (no N+1: TEST-06). The view itself never goes on the wire (ARCH-06):
   a change of the media contract must not reshape the owner's API. Never build a URL elsewhere (MEDIA-11). Whoever the record's read lets in gets
   the URLs, both parties of a shared record included; a URL is a bearer link until
   `read-url-expiry`. On lists, include the files only when the golden list does (it doesn't).
6. **ErrorCodes and messages:** `<ENTITY>_<X>_NOT_FOUND`, plus the record's own count rule and gate
   codes when it has them; the media piece owns `UPLOAD_*` and `MEDIA_STORAGE_UNAVAILABLE`. The API
   interface documents 400, 404, 409 and 503 on create and update.

## Tests (a class of their own)
`<Entity><X>sIntegrationTest` (golden `TodoAttachmentsIntegrationTest`), next to the entity's class,
holding `@ResourceLock(IntegrationTest.UPLOAD_POLICIES_LOCK)`: in `@BeforeEach` it reads the
purpose's stored limits (`uploadPolicyOf`) and sets known ones (`setUploadPolicy`), in `@AfterEach`
it puts the stored ones back (`restoreUploadPolicy`). Both: each test starts from known limits
whatever ran before, and no other class meets a test's limits (the owner's create checks the count
range even with no files, so a `minFiles` of 2 left behind fails every create elsewhere). The S3-compatible server comes with the test base. Each refusal asserts
its `errorCode` (TEST-08):
- create with keys from `POST /upload-urls` (after the PUT): 201, the files in the response with
  status and a working read URL;
- count range on create and on update, both sides: `minFiles` and `maxFiles` accepted, one below
  and one above 400 `UPLOAD_FILE_COUNT_OUT_OF_RANGE`; removing below the minimum refused unless
  the same request adds enough; a MISSING file (set through `sql(...)`) still counts; a policy
  shrunk below a record's files doesn't block an edit that leaves the files alone;
- the request bounds (TEST-03), on create and on each update field: a key of `media.upload-key`
  characters passes validation (then 409 `UPLOAD_NOT_AVAILABLE`, being unknown), one more is 400
  with the limit; `upload-policy.max-files` keys or ids pass validation, one more is 400 (random
  values suffice: validation runs before the claim);
- claim refusals, 409 `UPLOAD_NOT_AVAILABLE`: a key already attached (here or to another record),
  another user's key, another purpose's key, an expired key, an unknown key; on create **and** on
  update (`add<X>Keys`); the record unchanged after each;
- a remove id that isn't this record's file is refused; another user's record is 404 (TEST-02);
- remove: the file leaves the response and its row is RELEASED; delete releases every file;
- update with a stale version is 409 and attaches nothing; concurrent removals with one version
  apply once (the RELEASED count exact); **concurrent replacements** (remove the same file, add each
  its own) with one version: one 200, the rest 409 and never 503, the version moved once, the
  winner's key IN_REVIEW and the losers' still PENDING (TEST-05);
- **the owner lock comes first** (golden `updateLocksTheTodoFirst`): with the owner row held by the
  test, the waiting update holds no lock on the parents, `media_files` or the link table; removing
  the lock turns it red, which the race alone may not (TEST-09);
- the response's statement count is the same with 1 and many files (TEST-06);
- a rule of the record on its files: both sides of its threshold, its inputs changing alone;
  an approval gate: IN_REVIEW refused, MISSING refused, all UPLOADED accepted, no files passes;
- the API docs list the attachment fields and their codes.
