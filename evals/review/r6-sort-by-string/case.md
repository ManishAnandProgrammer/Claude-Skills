# R6: a sort by string literals instead of the metamodel

**Defect:** `TagRepositoryImpl.orderOf` orders by `root.get("name")`, `root.get("createdAt")` and `root.get("id")`
instead of `Tag_.name`, `Tag_.createdAt`, `Tag_.id`: a renamed field fails only at run time.

**Rule:** REST-08 (a list's order is an exhaustive switch over the metamodel, never a property string).

**Passes when** house-review reports REST-08 on `todo/repository/impl/TagRepositoryImpl.java`.

## Commit message

Tags: spell out the page's orders
