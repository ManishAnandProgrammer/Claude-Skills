# R3: Javadoc that restates its method's name

**Defect:** `TagService.create`, `findById` and `update` get Javadoc that says nothing the names and types don't
("Creates a tag. @param request the create request @return the created tag").

**Rule:** SMELL-19 (comments only for a why the names can't carry).

**Passes when** house-review reports SMELL-19 on `todo/service/TagService.java`, at any severity.

## Commit message

Tags: document the service
