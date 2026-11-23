# Media uploads: design (2026-11-14)

Files people upload and attach to a record (a todo's attachments, a leave's documents, a profile
photo), stored in S3 or an S3-compatible store. Distilled from three of the owner's projects (ERP,
admission module, DynamicForm) and an experiment against two S3-compatible servers. This document
is the reasoning; the rules are house-review `references/media.md` (MEDIA-xx), the golden code the
reference app's `media` module and todo attachments.

## Decisions and why

| # | Decision | Why |
|---|---|---|
| 1 | **The bytes never pass through the application.** The browser uploads straight to S3 with a presigned URL; the app signs, records and checks. | Memory, threads and bandwidth: a 10 MB upload through the app holds a request thread and a body buffer for the whole transfer. |
| 2 | **Presigned PUT from the AWS SDK, signed with the file's exact size, its content type and `Content-Disposition`.** | The SDK has no presigned POST (checked in `s3` 2.59.1, 2026-11-13). A PUT signed with `contentLength` puts `content-length;content-type;content-disposition` in `X-Amz-SignedHeaders`: an upload of any other size (one byte more or less, empty), type or disposition is refused 403 (experiment, versitygw and Scality CloudServer). The browser knows the exact size (`File.size`); the policy's range is checked when the URL is issued. No third-party library, no hand-written signer. |
| 3 | **A purpose is a code constant; its limits are data.** `UploadPurpose` (enum) names each purpose and who may upload for it; `upload_policies` holds min/max size, max files and content types, changed by an admin without a restart. | A new purpose always needs code (something attaches the file). Limits change often and must not need a deploy. One source of truth: the frontend reads the policies, never a copy. |
| 4 | **One generic endpoint issues URLs; the attach happens in the owning resource's own write.** `POST /upload-urls` signs; `POST/PATCH /todos/...` attaches. | The owner's write already checks its own rules (whose record, is it editable). The generic endpoint needs only "may this person upload for this purpose", which the purpose names. The frontend keeps one upload component. |
| 5 | **Every issued URL is a `media_files` row (UNCLAIMED); attaching claims it in one statement.** `UPDATE … SET status = 'ATTACHED' WHERE key IN (…) AND purpose = ? AND uploaded_by = ? AND status = 'UNCLAIMED' AND claimable_until > now()`; fewer rows than keys is 409. | The database, not S3 tags, records who uploaded what for which purpose. One atomic statement refuses reuse, another person's upload, another purpose's upload and an expired one; no tag reads, no S3 call in the write. |
| 6 | **No `HEAD` on attach; a scheduler confirms.** Attaching makes the file ATTACHED (usable); `MediaVerificationJob` checks ATTACHED files whose upload URL has expired: present → VERIFIED, absent → MISSING (final, counted in a metric). | The signature fixes size, type and disposition, so an object that exists has exactly the declared facts; the only open fact is whether it arrived. Checking only after the URL expired makes the answer final (no bytes can reach that key any more) and also rules out swapping a file's content, same size, in the minutes after it was attached. Every S3 call stays out of the owner's transaction, and no file API waits on S3. |
| 6b | **The scheduler never removes an attachment; a MISSING file keeps its slot.** The count range counts every attached file (ATTACHED, VERIFIED, MISSING); the user repairs a MISSING one with a normal update (remove it and add a new key in the same request). A step that needs complete files (approving, submitting) checks that every attachment is VERIFIED and answers 409 otherwise. | Only the record's own writes change its files, so `minFiles ≤ count ≤ maxFiles` can't break behind the owner's back. Whether a missing file blocks the business is the owning module's decision, at the step where it matters; a draft can be saved with one. The frontend attaches only after its PUT returned 200, so MISSING means a broken or dishonest client, and the metric shows when it happens. |
| 7 | **Keys are `{purpose}/{uuid}`; the file name lives in a column.** | No personal data in keys (they appear in logs, URLs and CloudTrail), no escaping, no path tricks. The name reaches the browser only through the signed `Content-Disposition` (`filename*=UTF-8''…`, RFC 5987). |
| 8 | **Content types are exact, from a fixed allow-list in code; SVG, HTML and XML are never allowed.** | A substring or family match lets `image/svg+xml` through, which a browser runs as a document. The allow-list is code because widening it is a security decision, not a setting. |
| 9 | **Changing a record's files is add/remove, not old/new/deleted, with a count range.** Create takes `attachmentKeys`; update takes `addAttachmentKeys` and `removeAttachmentIds`; the policy has `minFiles` and `maxFiles`, and the server checks `minFiles ≤ current − removed + added ≤ maxFiles`. | The update loads the record anyway, and its files are one query; a client-sent "old" list is a second opinion that can be wrong (a payload that only agrees with itself passes). Removing below the minimum is refused unless the same request adds enough (5 files, minimum 2: removing 4 needs 1 added). The record's version check serialises two concurrent changes, so they can't each pass and together drop below it; a change of files alone therefore advances the record's version too. |
| 10 | **Removal is a status; a job deletes from S3.** Statuses: UNCLAIMED (URL issued), ATTACHED (usable, awaiting the check), VERIFIED (confirmed), MISSING (never arrived), RELEASED (removed from its record). Removing marks the row RELEASED in the owner's transaction; `MediaCleanupJob` deletes RELEASED objects and UNCLAIMED ones past their expiry, then the rows. | Deleting from S3 inside the request either happens before commit (a rollback leaves a row pointing at nothing) or after it (a failure leaves an orphan). S3 deletes are idempotent, so the job is safe on every instance at once (`@MultiInstanceSafe`) and simply retries. |
| 10b | **Several instances share the jobs' rows through a lease taken with `SKIP LOCKED`.** A short transaction marks a batch `lease_until = now() + lease` (`… FOR UPDATE SKIP LOCKED`), the S3 calls run with no transaction, a second short transaction writes the results. Each job query has a partial index on its status (`WHERE status = 'ATTACHED'`, `'UNCLAIMED'`, `'RELEASED'`). | A row lock lasts only as long as its transaction, and S3 calls must not hold one (DATA-01), so the lock only hands out the lease. Two instances never pick the same rows; a crashed instance's lease expires and another takes over. Partial indexes hold only the rows a job wants, so they stay small while most files are VERIFIED. |
| 11 | **One class builds read URLs** (`MediaUrls`): presigned GET today, CloudFront later. | Switching delivery (CloudFront public or signed URLs) changes configuration and this class only. `Content-Disposition` is stored on the object at upload, so it survives the switch (CloudFront ignores S3's `response-content-disposition`). |
| 12 | **No URL cache.** | Signing is a local HMAC (microseconds). The SDK can't fix the signing time (a URL signed twice differs), so a cache's only gain, stable URLs, comes with CloudFront's rounded expiry instead. |
| 13 | **The S3 client has explicit timeouts** (`app.media.client.api-call-timeout`, `…attempt-timeout`). | The SDK's defaults are unset: a hung endpoint parks the thread for minutes. |

## The flow

```
1. GET  /upload-policies                      → each purpose's limits + canUpload (frontend hints)
2. POST /upload-urls {purpose, files:[{fileName, contentType, sizeBytes}]}
        checks: may upload for the purpose; files ≤ maxFiles; each size within [minSize, maxSize];
                type in the policy; then per file: an UNCLAIMED media_files row + a signed PUT
        → [{uploadKey, url, headers:{Content-Type, Content-Disposition}, expiresAt}]
3. PUT  <url> with exactly those headers and the file           (browser → S3)
4. POST /todos {…, attachmentKeys:[uploadKey…]}                 (or PATCH with add/remove)
        the owner's write: its own checks, the count range, then Uploads.claim(purpose, keys)
        in its transaction (UNCLAIMED → ATTACHED)
5. GET  /todos/{id} → attachments [{id, fileName, contentType, sizeBytes, status, url}]   (MediaUrls)
6. MediaVerificationJob: ATTACHED past the upload URL's expiry → HEAD → VERIFIED or MISSING
7. MediaCleanupJob: RELEASED and expired UNCLAIMED → S3 delete → row delete
```

## Names (golden code)

- `media/api/UploadPurpose` (enum; `TODO_ATTACHMENT`, each with the role that may upload),
  `media/api/Uploads` (`issue`, `claim`, `release`, `describe`), `media/api/MediaFileView`
  (id, fileName, contentType, sizeBytes, status, url), `media/service/MediaUrls`,
  `media/service/AllowedContentTypes`, `media/service/MediaVerificationJob`,
  `media/service/MediaCleanupJob`,
  `media/domain/MediaFile` (+ `MediaFileStatus`: UNCLAIMED, ATTACHED, VERIFIED, MISSING, RELEASED),
  `media/domain/UploadPolicy`; controllers `UploadUrlController` (`POST /upload-urls`) and
  `UploadPolicyController` (`GET /upload-policies`, `PUT /upload-policies/{purpose}`, ADMIN).
- Todo side: `todo/domain/TodoAttachment` (todo id → media file id), the create/update fields
  above, attachments in `TodoResponse`.
- Config: `app.media.bucket`, `app.media.region`, `app.media.endpoint` (empty for AWS),
  `app.media.upload-url-expiry` (5m), `app.media.read-url-expiry` (1h; below the credentials'
  lifetime), `app.media.claim-grace-period` (1h), `app.media.client.*` timeouts,
  `app.jobs.media-verification.cron`, `app.jobs.media-cleanup.cron`. Credentials: the SDK's default provider chain (an IAM role in
  production), never keys in configuration.
- Errors: `UPLOAD_POLICY_NOT_SEEDED` (404), `UPLOAD_SIZE_OUT_OF_RANGE` and `UPLOAD_TYPE_NOT_ALLOWED`
  (400), `UPLOAD_FILE_COUNT_OUT_OF_RANGE` (400: a request's files above `maxFiles`, or a record's files
  outside `[minFiles, maxFiles]` after a change), `UPLOAD_NOT_AVAILABLE` (409: unknown, someone else's, another
  purpose's, used or expired, deliberately one code), `MEDIA_STORAGE_UNAVAILABLE` (503).
- Changesets: `media_files`, `upload_policies` (min/max size, min/max files, content types; + the todo
  purpose's seed row), `todo_attachments`.
- Tests: an S3-compatible server in Testcontainers (`versity/versitygw:v1.11.0`); the experiment's
  403 cases as integration tests.

## Not in the golden code (rules and notes only)

- **Public forms (no sign-in):** a draft created behind a CAPTCHA or OTP gets a short-lived token
  scoped to that draft; URLs are issued to the token with per-IP and per-token rate limits and a
  per-draft quota (files and total bytes); the uploads are claimed by the token on submit; the
  bucket is never public and the files are scanned before staff open them.
- **Dynamic permissions:** `UploadPurpose` names a permission instead of a role; who holds it is
  data. Pending the permission design session.
- **Purposes defined at runtime** (dynamic form fields): one purpose plus the field id as context,
  its policy from the field's rules.
- **Server-made files** (receipts, exports): `PutObject` outside the transaction, then the row
  (VERIFIED: the server wrote it) in a short one; the cleanup job removes an object whose row never committed.
- **Public assets and CloudFront:** a visibility per purpose; public purposes from a public prefix
  behind CloudFront (stable URLs), private ones as CloudFront signed URLs or cookies. Short cache
  times for private files (a deleted file is served from the edge until it expires).
- **Image variants, scanning, files over 5 GB** (presigned multipart and an
  `AbortIncompleteMultipartUpload` lifecycle rule).
- **Bucket setup** (runbook): Block Public Access, bucket-owner-enforced ownership, default
  encryption, CORS for PUT from the app's origins allowing `Content-Type` and `Content-Disposition`,
  an IAM role limited to Put/Get/Delete on the bucket, a budget alarm. Before relying on the
  experiment's results in production, one manual run against a real AWS bucket confirms the 403s.

## Open, for the owner

- Decided (2026-11-14): decision 6 with the scheduler and the ATTACHED status (the owner's
  design), decision 9 with the count range; the dependencies `software.amazon.awssdk:s3` through
  the SDK's BOM and `versity/versitygw:v1.11.0` for tests are approved.
