# Media: records that carry files (Step 5)

Applies when the entity's records carry uploaded files (a todo's attachments, a leave's documents)
and the project has the `media` piece (`{{mediaApi}}` is not `none`). **The rules are house-review
`media.md` (MEDIA-01…14)**; this file says what to generate. The upload itself (`POST /upload-urls`,
the browser's PUT, verification, cleanup) is the media piece's job: generate nothing for it.

**Golden code:** `~/.claude/skills-reference/src/main/java/com/example/app/media/api/`
(`Uploads`, `UploadPurpose`, `MediaFileView`) and, for one owner, the todo attachments:
`todo/domain/TodoAttachment`, the attachment fields of the todo create/update requests,
`TodoService` (create and update), `TodoResponse` (`attachments`), their changeset and the
attachment tests in `src/test/java/com/example/app/todo/`. Copy their shape; don't restate it here.

Names below use `<X>` for the files' role on the entity (`Attachment`, `Document`, `Photo`).

## What to generate
1. **Purpose.** A new `UploadPurpose` constant (`<ENTITY>_<X>`, e.g. `TODO_ATTACHMENT`) with the
   role that may upload for it (MEDIA-03, MEDIA-04), and a changeset (next free number) seeding its
   `upload_policies` row: min/max size, min/max files, content types from `AllowedContentTypes`
   only (MEDIA-09). Ask the user for the limits; never SVG, HTML or XML.
2. **Link entity and changeset.** `<Entity><X>` (golden `TodoAttachment`): the owner's id → the
   media file's id, both `@ManyToOne(fetch = LAZY)`, audit columns. Table `<entity>_<x>s` with
   foreign keys to the owner and to `media_files` (named `fk_<table>_<column>`, each indexed),
   `UNIQUE (media_file_id)` (one file, one record). A member of the owner's aggregate (ARCH-15): no
   service or controller of its own.
3. **Requests.**
   - create: `List<String> <x>Keys` (the `uploadKey`s from `POST /upload-urls`);
   - update: `List<String> add<X>Keys` and `List<UUID> remove<X>Ids` (MEDIA-07), never an
     old/new/deleted list. Size bounds from configuration (`@ConfiguredMaxSize`), as for any list
     field.
4. **Service** (the owner's create and update, one transaction each, no S3 call: MEDIA-06):
   1. the owner's own checks first (load, ownership 404, version 409, editable);
   2. every `remove<X>Ids` entry must be one of this record's files (404
      `<ENTITY>_<X>_NOT_FOUND`, golden `TODO_ATTACHMENT_NOT_FOUND`; refused, not ignored);
   3. the count range from `Uploads.fileCountRange(purpose)`, only when the request changes files:
      `minFiles ≤ current − removed + added ≤ maxFiles`, where `current` counts every linked file
      whatever its status, else 400 `UPLOAD_FILE_COUNT_OUT_OF_RANGE` (create: `current` is 0);
   4. `Uploads.claim(purpose, keys)` for the added keys **before any other change to the entity**
      (409 `UPLOAD_NOT_AVAILABLE` on any key it can't claim: MEDIA-05): the claim is a native
      query, and Hibernate flushes pending changes before it, which would advance the version
      twice; then insert the link rows;
   5. the field changes, advancing the owner's version even when only files changed, then flush
      (MEDIA-07);
   6. `Uploads.release` for the removed files after that flush (RELEASED; the cleanup job deletes
      the objects: MEDIA-10) and delete their link rows. Deleting the entity releases all its
      files.
   If the entity has a step that needs complete files (submit, approve), that transition checks
   every file is UPLOADED and answers 409; saving a draft doesn't (MEDIA-06).
5. **Response.** `List<MediaFileView> <x>s` on the entity's response (id, file name, content type,
   size, status, read URL), filled by `Uploads.describe` for the record's files in one batch (no
   N+1: TEST-06). Never build a URL elsewhere (MEDIA-11). On lists, include the files only when
   the golden list does.
6. **ErrorCodes and messages:** none new; the media piece owns `UPLOAD_*` and
   `MEDIA_STORAGE_UNAVAILABLE`. The API interface documents 400, 409 and 503 on create and update.

## Tests (add to the entity's integration test class)
Mirror the golden todo attachment tests. The S3-compatible server comes with the test base
(Testcontainers, the media piece). Each refusal asserts its `errorCode` (TEST-08):
- create with keys from `POST /upload-urls` (after the PUT): 201, the files in the response with
  status and a working read URL;
- count range on create and on update, both sides: `minFiles` and `maxFiles` accepted, one below
  and one above 400 `UPLOAD_FILE_COUNT_OUT_OF_RANGE`; removing below the minimum refused unless
  the same request adds enough; a MISSING file (set through `sql(...)`) still counts;
- claim refusals, 409 `UPLOAD_NOT_AVAILABLE`: a key already attached (here or to another record),
  another user's key, another purpose's key, an expired key, an unknown key; the record unchanged
  after each;
- a remove id that isn't this record's file is refused; another user's record is 404 (TEST-02);
- remove: the file leaves the response and its row is RELEASED;
- update with a stale version is 409 and attaches nothing; two concurrent file changes with one
  version apply once (TEST-05);
- the response's statement count stays constant with 1 and many files (TEST-06).
