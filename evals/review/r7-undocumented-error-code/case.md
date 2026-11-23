# R7: a new error code thrown but absent from its operation's docs

**Defect:** a merge into an inactive tag is refused with a new code, `TAG_MERGE_TARGET_INACTIVE` (409: `ErrorCode`, its
status, its message, thrown in `TagService.lockTarget`), but `TagApi.merge` documents no 409 and no such code.

**Rule:** REST-20 (doc drift: an `@ApiResponse` for every status and code an endpoint can return).

**Passes when** house-review reports REST-20 on `todo/controller/api/TagApi.java` or `todo/service/TagService.java`.
The missing tests for the new refusal are expected noise.

## Commit message

Tags: refuse a merge into an inactive tag
