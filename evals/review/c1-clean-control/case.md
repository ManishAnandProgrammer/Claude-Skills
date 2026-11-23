# C1: a clean control

**Change:** a correct small refactoring: `TagService.lowestMissing` is renamed `lowestMissingId`, for what it
returns. Behaviour, API and tests are unchanged.

**Passes when** house-review reports nothing at Medium or above (Low findings are recorded). A Medium+ finding here is
a false positive: the review invents problems in correct code.

## Commit message

Tags: name the merge's missing-id helper for what it returns
