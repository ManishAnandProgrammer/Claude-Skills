# R1: an endpoint missing `@Requires`

**Defect:** `TagController.get` (`GET /tags/{id}`) loses its `@Requires(Permission.TAG_READ_OWN)`: the handler declares
nobody who may call it.

**Rule:** AUTHZ-03 (every handler declares who may call it, exactly once). ArchUnit's `handlersDeclareWhoMayCallThem`
also fails the build, so the review sees a red build too.

**Passes when** house-review reports AUTHZ-03 on `todo/controller/TagController.java`. Other findings are noise.

## Commit message

Tags: tidy the detail handler
