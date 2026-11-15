# Media uploads (MEDIA-xx): optional, for projects with the `media` piece

Files people upload and attach to a record (a todo's attachments, a leave's documents, a profile
photo), stored in S3 or an S3-compatible store. Golden code:
`~/.claude/skills-reference/src/main/java/com/example/app/media/` (`Uploads`, `UploadPurpose`,
`MediaUrls`, `AllowedContentTypes`, `MediaVerificationJob`, `MediaCleanupJob`, `MediaFile`,
`MediaFileStatus`, `UploadPolicy`) and, for one owner, the todo attachments (`TodoAttachment`,
`TodoService`, `TodoResponse`). Apply these rules only when the project has that piece.

The flow: `GET /upload-policies` (limits, for hints) → `POST /upload-urls` (a PENDING row and a
signed PUT per file) → the browser PUTs to S3 → the owner's create or update attaches the keys
(`Uploads.claim`, PENDING → IN_REVIEW) → `MediaVerificationJob` confirms (UPLOADED or MISSING) →
`MediaCleanupJob` deletes RELEASED and expired PENDING objects.

### MEDIA-01: The bytes never pass through the application
- **Check:** the browser uploads straight to storage with a presigned PUT and downloads with a URL
  from `MediaUrls`; the application signs, records and checks, nothing else. A 10 MB upload through
  the app holds a request thread and a body buffer for the whole transfer.
- **Detect:** `MultipartFile`, `@RequestPart`, a `byte[]` or `InputStream` request body,
  `RequestBody.fromInputStream`, `getObject` streamed into a response.
- **Not a finding when:** the server makes the file itself (receipts, exports; see *Out of
  scope*).
- **Severity:** High.

### MEDIA-02: The upload URL is signed with the exact size, type and disposition
- **Check:**
  - `PutObjectRequest` sets `contentLength` (the browser's `File.size`), `contentType` and
    `contentDisposition`, so all three are in `X-Amz-SignedHeaders`: storage refuses (403) an
    upload of any other size, type or disposition;
  - the request is checked against the purpose's policy **when the URL is issued**: files ≤
    `maxFiles` (`UPLOAD_FILE_COUNT_OUT_OF_RANGE`), each size within `[minSize, maxSize]`
    (`UPLOAD_SIZE_OUT_OF_RANGE`), the type in the policy (`UPLOAD_TYPE_NOT_ALLOWED`), all 400;
  - the AWS SDK's own presigner; no hand-written signer, no presigned POST library.
- **Detect:** `presignPutObject` with a `PutObjectRequest` missing `contentLength`; a size limit
  checked only in the frontend.
- **Severity:** High (a URL without a signed length accepts a file of any size).

### MEDIA-03: Purposes are code, limits are data
- **Check:** each purpose is an `UploadPurpose` constant (a new purpose always needs code: something
  attaches the file). Its limits (min/max size, min/max files, content types) are an
  `upload_policies` row, changed by an admin through `PUT /upload-policies/{purpose}` without a
  restart, and seeded by the changeset that adds the purpose, for every tenant with tenancy, the
  existing ones included (row mode: a `TenantProvisioned` listener plus a backfill changeset, TEN-34). The frontend reads
  `GET /upload-policies`; it never keeps a copy of the limits. A purpose without a row is 404
  `UPLOAD_POLICY_NOT_FOUND`.
- **Detect:** sizes or types in `application.yml` or in an enum constructor; purposes as free
  strings; limits repeated in a frontend constant or in API docs.
- **Severity:** Medium.

### MEDIA-04: The purpose says who may upload; the owner's write attaches
- **Check:**
  - `POST /upload-urls` checks only "may this person upload for this purpose", which the
    `UploadPurpose` constant names (a role). The purpose arrives in the body, so this one check is
    `purpose.mayUpload(currentUser::hasRole)` in the service (403 `ACCESS_DENIED`), not an annotation
    (REST-25 covers checks a method can name up front). `UploadPurposeTest.onlyTheNamedRolesMayUpload`
    unit-tests the refusal, so it stays tested when a purpose names every role and the endpoint's 403
    tests are skipped;
  - attaching happens inside the owning resource's own create or update (`POST /todos`,
    `PUT /todos/{id}`), which keeps its own checks (whose record, is it editable), takes the count
    range from `Uploads.fileCountRange(purpose)` and calls `Uploads.claim`;
  - the media module has no hook, callback or per-purpose switch into feature modules;
  - **the purpose's role is the role of whoever writes the owning record**, not of the person the
    file is about: the claim requires the uploader to be the claimer (MEDIA-05), so if admins edit
    employees, admins upload their photos (`EMPLOYEE_PHOTO("ADMIN")`). When the record's subject may
    change the file too (an employee's own photo), that is a second write path with its own checks,
    and the purpose names both roles. A purpose lists **every** role that writes the record: roles
    don't include each other (the house ships no hierarchy, REST-25), and with tenancy a
    tenant's owner is its ADMIN, so a record every member edits is `STUDENT_PHOTO("USER", "ADMIN")`;
    a purpose naming `USER` alone shuts the owner out.
- **Detect:** an `attach` endpoint in the media module; an interface in `media` implemented by a
  feature module; `switch (purpose)` in media code.
- **Severity:** High (an attach outside the owner's write skips the owner's rules).

### MEDIA-05: Every issued URL is a PENDING row; the attach claims it in one statement
- **Check:** issuing a URL inserts a `MediaFile` (PENDING, purpose, uploader, `expiresAt`).
  `Uploads.claim` is one `UPDATE … SET status = 'IN_REVIEW' WHERE key IN (…) AND purpose = ? AND
  uploaded_by = ? AND status = 'PENDING' AND expires_at > now()`; fewer rows updated than keys
  sent is 409 `UPLOAD_NOT_AVAILABLE`, one code for unknown, another person's, another purpose's,
  already used or expired (telling them apart helps a guesser). The database records ownership;
  S3 tags, metadata or a client-sent uploader do not.
- **Detect:** a read-then-update claim; object tags or `x-amz-meta-*` read to decide ownership;
  separate codes per refusal reason.
- **Severity:** High (a key from another user or another record becomes yours).

### MEDIA-06: No storage call in a transaction; a scheduler confirms the upload
- **Check:**
  - no S3 call (sign is local and fine; `HEAD`, `PUT`, `DELETE` are not) in the owner's
    transaction (DATA-01); no `HEAD` on attach;
  - `MediaVerificationJob` checks IN_REVIEW files **whose upload URL has expired**
    (`upload_expires_at`, from `app.media.upload-url-expiry`): present →
    UPLOADED, absent → MISSING (final). After expiry no bytes can reach the key, so the answer is
    final and a same-size swap after the attach is ruled out;
  - the job only changes the status: it never removes or unlinks an attachment. A MISSING file
    keeps its slot (MEDIA-07) and the user repairs it with a normal update (remove it, add a new
    key, in one request). MISSING means a broken or dishonest client (the frontend attaches only
    after its PUT returned 200), so it is counted in a metric (`media.files.missing`); its view has
    no read URL;
  - a business step that needs complete files (approve, submit) checks in that transition, under the
    record's lock and before it changes anything, that every attachment is UPLOADED
    (`Uploads.allUploaded`), and answers 409 `<ENTITY>_<X>S_INCOMPLETE` otherwise (a feature code; its
    message says to try again later); saving a draft doesn't. Files stay IN_REVIEW until their
    upload URL expired and the job ran, so **at least `app.media.upload-url-expiry` after the last
    upload**: the action's API text says so, or clients read the refusal as a bug. Tests (statuses set
    through `sql(...)`, the job's schedule is off): IN_REVIEW refused, MISSING refused, all UPLOADED
    accepted, a record without files passes the gate;
  - **both jobs share their rows between instances through a lease:** a short transaction marks a
    batch (`UPDATE … SET lease_until = now() + :lease WHERE id = ANY (ARRAY(SELECT … ORDER BY …, id
    LIMIT :batch FOR UPDATE SKIP LOCKED)) RETURNING …`: computed once, DATA-29; never `id IN (SELECT …)`,
    which may lease more than the batch), the storage calls run with no transaction, a second
    short transaction writes the results. A row lock lasts only as long as its transaction, and a
    storage call must not hold one, so the lock only hands out the lease; a crashed instance's lease
    expires and another picks the rows up. Each job query has a partial index on its status
    (`WHERE status = 'IN_REVIEW'`, `'PENDING'`, `'RELEASED'`), proven by an `EXPLAIN` test, so the
    index holds only the rows the job wants while most files are UPLOADED.
- **Detect:** `headObject`/`deleteObject` reached from a `@Transactional` method; a verification
  that runs before the URL expired; a scheduler that deletes or unlinks attachments; MISSING
  without a metric; a job that selects without `SKIP LOCKED` and a lease, or holds a row lock
  across a storage call; a lease written `id IN (SELECT … SKIP LOCKED)`; a job query with no partial
  index on its status.
- **Severity:** High.

### MEDIA-07: A record's files change by add/remove, inside a count range
- **Check:** create takes `<x>Keys`; update takes `add<X>Keys` and `remove<X>Ids`, never a
  client-sent old/new/deleted list. The server computes
  `minFiles ≤ current − removed + added ≤ maxFiles` from the record's own links (400
  `UPLOAD_FILE_COUNT_OUT_OF_RANGE`), where `current` counts **every** attached file (IN_REVIEW,
  UPLOADED, MISSING): only the record's own writes change the count. Removing below the minimum is
  refused unless the same request adds enough. The record's version check serialises two
  concurrent changes, so the version must advance on a files-only change too. A remove id that
  isn't this record's file is refused, not ignored (404, e.g. `TODO_ATTACHMENT_NOT_FOUND`). The
  range is checked only when a request changes files, so a policy tightened later doesn't block an
  unrelated edit. Deleting the record releases its files (MEDIA-10).
- **The owner is locked first.** A write that changes the record's files (and its delete) loads the
  owner `FOR NO KEY UPDATE` (`findForUpdateBy…`, DATA-29's "owner of a write") before the claim, the
  release or any other row. Without it, two add+remove requests with one version both insert their
  link rows, each insert's foreign key check holding the owner `FOR KEY SHARE`; when the update also
  changes a column with a plain unique index (a code, an email), PostgreSQL needs a key lock for it,
  each waits for the other's key share, and the deadlock answers 503 `DATABASE_CONTENTION` instead
  of 409 (run 6: 40 × 503 in 48 racers, until the lock was added). With the lock the second writer
  queues at the first statement and gets 409. The reference proves the order directly
  (`TodoAttachmentsIntegrationTest.updateLocksTheTodoFirst`), because `todos` has no unique column
  and its replacement race passes even without the lock. An owner **with** a unique column gets a
  race that can fail: each racer changes that column to its own new value (a project's student
  photo race went red, `[503, 200, 503, 503, 503, 503]`, with the lock removed, while a race keeping
  the email passed); a race that keeps every column proves nothing there.
- **Rules of the record on the count** (more than N days of leave needs a document) are the owner's
  code, never a narrowed `FileCountRange`: their own 400 code (`<ENTITY>_<X>_REQUIRED`, the
  threshold in the message), on the final count (`current − removed + added`), checked when their
  inputs change (the fields they read, or the files), after the claim and before the flush, so a
  refusal rolls the claim back. Like the purpose's range, a changed threshold doesn't block an
  unrelated edit. Tests: both sides of the threshold, the inputs changing without files, files
  removed below it.
- **A single-file purpose** (a profile photo: `maxFiles` 1) keeps the list shape (`photoKeys`,
  `addPhotoKeys`, `removePhotoIds`, `photos`): one shape for every purpose, and the count check
  covers it. Replacing is a remove and an add in one request, which the update's `@Schema` says.
- **Detect:** a count that skips MISSING files; `oldIds`/`newIds`/`deletedIds` payloads; a files
  change that doesn't touch the record's version; the count check only in the frontend; a files
  update that loads its owner without a lock, or locks it after the claim; a record rule folded into
  the purpose's count range.
- **Severity:** High (two concurrent removes each pass and together drop below the minimum).

### MEDIA-08: Keys are `{purpose}/{uuid}`; the file name travels only in the signed header
- **Check:** object keys hold no file names, user ids or other personal data (keys appear in logs,
  URLs and CloudTrail). The file name is a column, and reaches the browser only through the signed
  `Content-Disposition: attachment; filename*=UTF-8''…` (RFC 5987), stored on the object at upload.
- **Extraction rule (with tenancy):** the key starts with the tenant's id, `{tenantId}/{purpose}/{uuid}`
  (the tenancy overlay's `MediaFileMapper.newKey`, from the current tenant only), so one tenant's files
  are one prefix: copied to another bucket or deleted with the tenant, while the keys stored in its rows
  stay valid (tenancy.md TEN-19; test `MediaKeysPerTenantTest`).
- **Detect:** a key built from `fileName`, an email or a user id; a file name concatenated into a
  header without RFC 5987 encoding; with tenancy, a key without the tenant prefix first.
- **Severity:** Medium.

### MEDIA-09: Content types are exact, from a code allow-list; never active content
- **Check:** `AllowedContentTypes` (code) lists exact types; a policy may only name types from it,
  and widening it is a code change (a security decision, not a setting). `image/svg+xml`,
  `text/html`, `application/xhtml+xml` and other XML types are never allowed: a browser runs them
  as a document. Downloads are `attachment`, never `inline`.
- **Detect:** `startsWith("image/")`, `contains("pdf")`, a `*` wildcard; SVG or HTML in a seed
  row; `inline` in a disposition.
- **Severity:** High (a stored script served from the app's storage domain).

### MEDIA-10: Removal is a status; a job deletes from storage
- **Check:** removing a file marks its row RELEASED in the owner's transaction. `MediaCleanupJob`
  deletes RELEASED objects whose upload URL has expired (so no PUT can recreate them) and PENDING
  ones past `expires_at` (upload URL expiry + `app.media.pending-lifetime`), then their rows; it
  leases its batches like the verification job (MEDIA-06), and an S3 delete is idempotent, so a
  retried batch is harmless. Deleting in the request either happens before commit (a rollback
  leaves a row pointing at nothing) or after it (a failure leaves an orphan).
- **Detect:** `deleteObject` in a request path; a link row deleted without releasing its file; a
  cleanup that deletes rows before objects.
- **Severity:** Medium.

### MEDIA-11: One class builds read URLs; no URL cache
- **Check:** every read URL comes from `MediaUrls` (presigned GET today; CloudFront later is a
  configuration change plus this class). Responses carry the URL through `Uploads.describe`
  (`MediaFileView`), which the owning module maps into a response record of its own (golden
  `TodoAttachmentResponse`; the view never goes on the wire, ARCH-06). No cache of URLs: signing is a local HMAC. `app.media.read-url-expiry` stays
  below the lifetime of the credentials that sign it: a URL signed with temporary credentials (an
  IAM role) stops working when they expire, whatever its own expiry says.
- **Who gets read URLs:** whoever the owning record's read lets in (a leave request's requester and
  their manager alike); the media module adds no check of its own, so a record visible to two
  parties shows both its files. A read URL is a bearer link: anyone it is forwarded to can download
  until `app.media.read-url-expiry`, so that stays short (minutes to an hour), and URLs are never
  logged.
- **Detect:** `presignGetObject` outside `MediaUrls`; `@Cacheable` on a URL method; a read-URL
  expiry of hours or days with role credentials; a URL in a log line.
- **Severity:** Medium.

### MEDIA-12: The storage client: timeouts, failures, credentials
- **Check:**
  - explicit `app.media.client.api-call-timeout` and `app.media.client.api-call-attempt-timeout`
    (REL-01): the SDK's
    defaults are unset, and a hung endpoint parks the thread for minutes;
  - storage unreachable or timing out → 503 `MEDIA_STORAGE_UNAVAILABLE` with `Retry-After`, as for
    transient database failures (ERR-03);
  - credentials from the SDK's default provider chain (an IAM role in production); never access
    keys in configuration or environment files committed to the repository.
- **Detect:** `S3Client.builder()` without `overrideConfiguration` timeouts;
  `StaticCredentialsProvider`, `AwsBasicCredentials`, `access-key` in yml; a storage exception
  answered 500.
- **Severity:** High.

### MEDIA-13: The bucket is set up for this flow
- **Check** (runbook, not code; ask when it can't be seen): Block Public Access on;
  bucket-owner-enforced object ownership; default encryption; CORS allowing PUT from the app's
  origins with the `Content-Type` and `Content-Disposition` headers; an IAM role limited to
  Put/Get/Delete on this bucket; a budget alarm. Before relying on the signed-header refusals in
  production, one manual run against a real AWS bucket confirms the 403s.
- **Locally:** `docker compose up` starts an S3-compatible store (`media-store`, the image the tests
  use, data on a named volume) that creates `MEDIA_BUCKET`; `.env.example` points the app at it
  (`MEDIA_ENDPOINT`, path-style access, local-only keys). Reference: `reference-app/compose.yaml`.
- **Severity:** High for a public bucket or a wildcard IAM policy; Low otherwise.

### MEDIA-14: Public forms (no sign-in)
- **Check:** a draft created behind a CAPTCHA or OTP gets a short-lived token scoped to that
  draft; URLs are issued to that token with per-IP and per-token rate limits (REST-14) and a
  per-draft quota (files and total bytes); the token claims the uploads on submit; the bucket is
  never public; the files are scanned before staff open them.
- **Detect:** `/upload-urls` in the public paths without a draft token, a rate limit or a quota.
- **Severity:** High.

Not in scope by default (rules and notes only, no golden code):
- **Files over 5 GB:** presigned multipart, plus an `AbortIncompleteMultipartUpload` lifecycle rule.
- **Image variants and scanning:** a job after UPLOADED, never in the request.
- **Server-made files** (receipts, exports): `PutObject` outside the transaction, then the row in a
  short one; the cleanup job removes an object whose row never committed.
- **Purposes defined at runtime** (dynamic form fields): one purpose plus the field id as context,
  its policy from the field's rules.
- **Dynamic permissions:** `UploadPurpose` names a permission instead of a role; pending its own
  design session.
- **Public assets and CloudFront:** a visibility per purpose; short cache times for private files
  (a deleted file is served from the edge until it expires).
