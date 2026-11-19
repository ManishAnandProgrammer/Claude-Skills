# Authorization (AUTHZ-xx)

Who may do what: a **permission** says whether a caller may use an action at all, a **policy** says whether
they may use it on this record. The default for every house project is Level 1 below: permissions in code,
roles in code listing them, a policy per owned resource. Level 2 (*With the roles piece*) moves the roles into
tables an admin edits, and changes no endpoint, policy or client. The profile's `authorization` key says which
level a project is on (`permissions`, the default; `roles-piece` once that piece exists).

Golden code, in the reference application (`~/.claude/skills-reference/src/main/java/com/example/app/`):
`common/security/` (`Permission`, `Area`, `Reach`, `Requires`, `SignedIn`, `Public`, `Decision`,
`TokenPermissions`, `CurrentUser.permissions()`/`holds()`), `config/SecurityConfig.jwtAuthenticationConverter`,
`user/domain/Role`, `user/service/RolePermissions`, `GET /users/me/access` (`UserController.access`,
`UserMapper.toAccessResponse`, `AccessResponse`), `todo/service/TodoPolicy` (and its use in `TodoService`,
`TodoRepositoryImpl.findByFilter`, `TodoAllowedActionsResponse`), `common/dropdown/DropdownFor` with the
todo-list and tag dropdowns and the todo dropdown under its list (the dependent one, `TodoService.dropdown`),
`media/api/UploadPurpose`. Tests: `archunit/ConventionArchTest`
(`handlersDeclareWhoMayCallThem`, `methodSecurityIsDeclaredOnce`, `noRoleChecks`) proven on its fixtures in
`ArchRuleScopeTest`, `common/EndpointContractIntegrationTest` (`everyRequiredPermissionIsChecked`,
`everyPublicEndpointAnswersWithoutAToken`, `everyEndpointNeedsAToken`), `common/security/PermissionTest`,
`user/domain/RoleTest`, `user/AccessIntegrationTest`, `todo/service/TodoPolicyTest`,
`common/dropdown/DropdownForTest`, `todo/TodoListIntegrationTest` (the dropdown's `for`),
`todo/TodoDropdownIntegrationTest` (the dependent dropdown: its out-of-reach parent, one statement). With tenancy:
`overlays/tenancy/.../user/service/MembershipService.lockAsCurrentAdmin` (TEN-08).

Names: Spring Security calls a permission an *authority* and a role a *group*; the house says Permission, Role
and Policy, and each of `Permission` and `Role` says so in one javadoc line.

## Level 1: permissions in code

### AUTHZ-01: Permissions are one enum of capabilities
- **Check:**
  - `Permission` is an enum in `common/security`; each constant becomes a Spring authority of exactly its name,
    and nothing else does (no `ROLE_` authority, no scope). Spring Security 7 adds its own `FACTOR_BEARER` (how
    the caller signed in), which grants nothing;
  - a name is `<RESOURCE>_<ACTION>`, by capability (`ACCOUNT_STATUS_CHANGE`, `METADATA_MANAGE`,
    `UPLOAD_POLICY_MANAGE`, `TODO_EDIT_OWN`), never by role or screen (`ADMIN_PAGE`, `MANAGER_STUFF`), with a
    reach suffix only where the business tells reaches apart: `_OWN` (the caller's own records), `_ASSIGNED`
    (records the owning module says are theirs to work), none (every record);
  - the reach is declared again on the constant (`Reach`), and code reads `reach()`, never the name
    (`PermissionTest` keeps the two in step);
  - each constant names its menu `Area` (AUTHZ-06) and its **prerequisites**: the permissions it needs to be
    usable (`TODO_EDIT_OWN` needs `TODO_READ_OWN`), declared on the enum, never in a table. A prerequisite is
    checked (AUTHZ-02), never granted for it: no implied grants;
  - an authority that names no constant grants nothing (`CurrentUser.permissions()` ignores it): unknown codes
    fail closed.
- **Detect:** permission names as string literals (`hasAuthority('TODO_READ')`) outside `common/security`; a
  constant named after a role or a screen; code parsing a suffix (`name().endsWith("_OWN")`) outside the
  enum's own test; a permission granted because another one is held (`if (holds(EDIT)) grant(READ)`);
  prerequisites in a table or a properties file; an unknown authority mapped to a default permission.
- **Not a finding when:** the project is on the roles piece (the enum stays the list of codes; the tables
  assign them, AUTHZ-12).
- **Severity:** Medium; High when an unknown code grants anything.

### AUTHZ-02: A role lists its permissions; nothing checks a role
- **Check:**
  - `Role` (an account's, or with tenancy a membership's: one role each, TEN-08) lists its permissions in code
    (`USER(new Permission[] {TODO_READ_OWN, ...})`); roles don't include each other, so a permission two roles
    share is on both lists;
  - the access token carries the role; the security chain maps it to exactly its permissions
    (`TokenPermissions`, the sign-in piece's `RolePermissions`; a project that signs users in elsewhere
    implements `TokenPermissions` for its identity provider's roles);
  - no `hasRole`, `hasAnyRole`, `@Secured`, `@RolesAllowed`, `RoleHierarchy` or `ROLE_` prefix anywhere,
    `common/security` included (ArchUnit `noRoleChecks`);
  - a check in code that must read the role from the database rather than the token (a change of access,
    AUTHZ-10) asks the role whether it grants the permission (`role.grants(ACCOUNT_STATUS_CHANGE)`), never
    compares role names;
  - every role holds the prerequisites of each permission it holds (`RoleTest`);
  - **freshness** is stated where the role javadoc states it: permissions come from the token's role, so a role
    change reaches the person within one access-token lifetime, or at their next request with the
    `session-version` piece (SEC-07).
- **A new role** needs, in one change: its constant with its list, the changeset widening the role check
  constraint (`ck_users_role`), and the test base's `new<Role>()` (REST-25). **A new permission** needs its
  constant (area, reach, prerequisites) and a line in each role that gets it: crud-gen asks which roles.
- **Detect:** `getRole() == Role.ADMIN` deciding access; a role constant without a permission list; a
  `RoleHierarchy` bean; a role's list missing a prerequisite; role contents published (an endpoint or a
  document listing which role holds which permission: AUTHZ-06).
- **Not a finding when:** a rule is about the role itself, not about access (a tenant keeps one ADMIN,
  `TENANT_MEMBER_LAST_ADMIN`).
- **Severity:** High for a role check deciding access; Medium otherwise.

### AUTHZ-03: Every handler declares who may call it, exactly once
- **Check:**
  - each handler method carries exactly one of `@Requires(Permission.X)` (that permission), `@SignedIn`
    (every signed-in caller: their own account, or a service that checks a permission the request names,
    AUTHZ-08, AUTHZ-10) or `@Public` (no token; the security chain's public paths let it in). ArchUnit
    `handlersDeclareWhoMayCallThem` fails the build for none or two;
  - `@Requires` is a meta-annotation over `@PreAuthorize("hasAuthority('{value}')")`: Spring Security 7's
    annotation scanners resolve the template from the enum attribute by default (`SecurityAnnotationScanners
    .requireUnique`), so no template bean is needed and a misspelt permission fails at compile time. No
    `@PreAuthorize`/`@PostAuthorize` string outside `common/security` (ArchUnit `methodSecurityIsDeclaredOnce`);
  - `@Public` handlers and the chain's public paths agree both ways: every `@Public` handler answers without
    a token, every other one is 401 `AUTHENTICATION_REQUIRED` (`EndpointContractIntegrationTest`);
  - the OpenAPI 403 of every `@Requires` handler names `ACCESS_DENIED` and its permission, written from the
    annotation (`config/PermissionOpenApiCustomizer`), so the document can't drift from the handler (REST-20);
  - a role check on the class is no substitute: the declaration is read on the handler.
- **Know:** method security on a controller runs after the arguments are bound and validated, so an invalid
  body is 400 before a caller without the permission gets 403. It reveals only the request's own shape; a
  test that expects the 403 sends a valid body.
- **Detect:** a handler with no declaration; a class-level `@PreAuthorize`; `@PreAuthorize("isAuthenticated()")`
  written out; a public path in the chain with no `@Public` handler under it, or the reverse; `permitAll()`
  for a path that also has a `@Requires` handler.
- **Severity:** High (a handler without a check is open to every signed-in caller); Medium for a declaration
  written as a string.

### AUTHZ-04: Record rules are the owning module's policy
- **Check:**
  - an owned resource has a `<Resource>Policy` in its module (golden `TodoPolicy`): plain Java `if`s on the
    record and the caller, each rule a method returning a `Decision` (allowed, or refused with an `ErrorCode`
    and its arguments; named apart from Spring's `AuthorizationDecision`, which carries no reason);
  - it holds every record rule that decides an action: reach (ownership, assignment), state (a closed record
    stays closed), amounts, dates, relationships (the requester reads, the owner edits). A state invariant that
    holds for every caller stays the entity's guarded method too (golden `Plan.requirePlanned`), and the policy
    calls it when an `allowedActions` flag or a caller's decision needs it; a rule about the caller (reach, a
    relationship, an amount bounded per permission) is the policy's alone. A limit (a concession up to 2,000) is
    the module's setting or data, **never a column of a role or a permission**;
  - the write path calls `policy.x(record, caller).orThrow()` after loading the record; a write that locks loads
    through the policy's condition (`TodoPolicy.editScope`, a condition of the locking statement), so a refused
    caller never takes the owner's lock (DATA-29), and on an empty result reads the record unlocked for the policy
    to say why (`TodoService.lockEditableTodo`); the policy never picks an HTTP status: the error contract's mapper does, and where the house
    answers another owner's record with 404 (REST-16) the policy refuses with the `<ENTITY>_NOT_FOUND` code;
  - no rule engine, expression strings, reflection or rules as data.
- **Detect:** a record locked by id before its policy decides; ownership compared in the controller or the mapper; an `if (role == ...)` in a service; a limit
  read from the role; the same "is it theirs" written in two services; a policy that throws an HTTP status or
  a Spring exception; a record rule inside a `@PreAuthorize` expression.
- **Not a finding when:** a resource whose only rule about the caller is the owner's reach keeps the owner id in its
  queries (`TodoListService`, `TagService`, `PlanService`, whose state rules are the entity's guarded methods for
  every caller): the policy shape is for a resource with a second party, a caller-dependent state or amount rule,
  or an `allowedActions` flag.
- **Severity:** High when a write skips its policy; Medium otherwise.

### AUTHZ-05: The policy owns its list filter
- **Check:** "own" or "assigned" is defined once, in the policy, for the detail and the list: the policy
  returns the condition the list's Criteria query adds (`TodoPolicy.listScope(callerId)`, a
  `PredicateSpecification` the repository applies in the same statement: Pundit's Scope). A list never shows a
  record its detail would refuse.
- **Detect:** a list query with its own owner condition next to a policy with another; a list filtered in
  memory after the query; a scope that needs a second statement per page.
- **Not a finding when:** a facade's batch lookup for another module states that it is the scope's condition
  written as its own query (`TodoRepository.findViewsByIdInAndOwnerId`).
- **Severity:** High when the list shows what the detail refuses; Medium otherwise.

### AUTHZ-06: `GET /users/me/access` answers the caller's areas and permissions
- **Check:**
  - `GET {{urlPrefix}}/users/me/access` (`@SignedIn`) answers `{"areas": [...], "permissions": [...]}` for the
    caller (with tenancy, the current membership's: its token's role), read from the token, no database read;
  - an area is listed when the caller holds at least one of its permissions; `Area` is an enum of the menu's
    top-level items (in the reference `TODOS`, `ACCOUNTS`, `SETTINGS`), and every area holds a permission
    (`PermissionTest`);
  - both are enum schemas in the OpenAPI document (`@Schema(enumAsRef = true)`), so a client generates their
    types;
  - the client shows a menu item by its area, a tab or a button by its permission (or by a record's
    `allowedActions`, AUTHZ-07); it never decides which permissions make an area, and labels, order and icons
    stay the client's;
  - role contents are never published: no endpoint lists which role holds what.
- **Detect:** a frontend copy of a role → permission table; menus keyed by role names; a role list in a
  response for showing buttons; permissions as free strings in the schema.
- **Severity:** Medium.

### AUTHZ-07: `allowedActions` only where a button depends on the record
- **Check:** a response carries `allowedActions` only for an action whose answer differs between records the
  caller may see (the requester's view of a todo shows `edit: false`), and computes each from the same policy
  method the write calls (`TodoPolicy.edit(...).isAllowed()`). An action every visible record allows, or one
  the permission alone decides, is not repeated per record.
- **Detect:** an `allowedActions` flag computed by its own condition (a copy of the policy); flags for every
  action on every response; a frontend rule that re-derives a record's editability from its fields.
- **Not a finding when:** the answer follows from a documented status alone, the same for every caller who sees
  the record (a DONE plan is final for everyone: its `status` and the API text say so); a flag is added once a
  caller-dependent rule appears.
- **Severity:** Medium.

### AUTHZ-08: Shared dropdowns are named by the action they serve
- **Check:**
  - one dropdown per resource, used by every screen that picks one (REST-13), with a **required**
    `for=<Permission>` query parameter whose OpenAPI schema is the Permission enum (`DropdownFor.PARAMETER`);
  - the server refuses (403 `ACCESS_DENIED`) unless the caller holds that permission, and narrows the
    options to its reach (`Reach`): the reach's condition is the owning module's (the caller's own records for
    `OWN`, an indexed join for `ASSIGNED`, none for `ALL`); a reach the dropdown doesn't list is 400
    `DROPDOWN_REACH_UNSUPPORTED` at `for` (`DropdownFor.check`);
  - **one query**: the permission check is in memory, the reach is one more condition of the same Criteria
    query, and a dependent dropdown takes its parent id as a plain filter in that same `WHERE`, with no separate
    parent check and no walk up the chain: an out-of-reach parent yields an empty page and nothing leaks. Each
    level of a dependent chain is independent. A test counts one statement per call;
  - a dropdown never lists who may call it, never needs a READ permission of its own, and the write it feeds
    re-checks everything. The parallel is the upload purpose: the client names it, the server checks it and
    applies its rules.
- **Detect:** a dropdown per screen (`/classes/for-attendance`, `/classes/for-fees`); a public dropdown; a
  dropdown guarded by a READ permission a write's screen doesn't otherwise need; a `for` accepted but not
  checked, or its reach ignored; a parent looked up first (two statements, and a 404 that tells ids apart).
- **Severity:** High for a dropdown that lists records outside the action's reach; Medium otherwise.

### AUTHZ-09: The authorization tests
- **Check:**
  - **every `@Requires` endpoint, one generated test:** a signed-in caller holding every permission but the
    endpoint's own gets 403 `ACCESS_DENIED` with a valid body; the caller's token is real, only its
    authorities are chosen (`IntegrationTest.holding`), so a new endpoint is covered without anyone writing a
    test (`EndpointContractIntegrationTest.everyRequiredPermissionIsChecked`);
  - `@Public` against the chain's public paths, both ways (AUTHZ-03);
  - **a table-driven unit test per policy**: one row per rule × caller (owner, second party, stranger) × state,
    with the refusal code (`TodoPolicyTest`);
  - **the converter**: each role's token becomes exactly its permissions and nothing else, an unknown role or
    none grants nothing (`AccessIntegrationTest`);
  - every role holds its permissions' prerequisites (`RoleTest`); reach and name agree, every area is used
    (`PermissionTest`);
  - the ArchUnit rules are proven red on fixtures (`ArchRuleScopeTest`): a handler with none or two
    declarations, a string `@PreAuthorize` outside `common/security`, each kind of role check, a role check
    inside `common/security`;
  - the dropdown's `for`: missing, unknown, not held (403, no statement), an unsupported reach (400), one
    statement per call.
- **Detect:** a per-endpoint 403 test where the generated one would do (it drifts); a policy tested only
  through HTTP; a converter test that checks containment instead of equality.
- **Severity:** Medium (TEST-01).

### AUTHZ-10: A permission the request names, and a change of access
- **Check:**
  - a permission the request's data decides (an upload's purpose, a dropdown's `for`) is checked in the
    service with `CurrentUser.holds(permission)` (`UploadPurpose.mayUpload(currentUser::holds)`), on a
    `@SignedIn` handler, never with a role name;
  - a write that grants, keeps or ends access (an account's suspension, a member's role) authorises on the
    caller's role **read from the database under the lock that serialises those changes**
    (`role.grants(ACCOUNT_STATUS_CHANGE)` in `AccountStatusService.changeStatus`, TEN-08's
    `MembershipService.lockAsCurrentAdmin`), on top of its `@Requires`: the token's permissions are up to one
    lifetime old, and a stale token that changes access makes its change permanent.
- **Detect:** `holds(...)` replaced by a role comparison; an access-changing write checked by its annotation
  alone.
- **Severity:** High for an access-changing write on the token alone; Medium otherwise.

## Level 2: with the roles piece

These rules apply once the project has the `roles` piece (profile `authorization: roles-piece`); until then
they describe where Level 1 goes, and a Level 1 project is not reviewed against them.

### AUTHZ-11: The tables, and the effective set
- **Check:**
  - tables `roles`, `role_permissions`, `user_roles (valid_until)` and `user_permissions (ALLOW|DENY)` (the
    shape of Spring Security's own `groups`, `group_members`, `group_authorities`), all four audited (DATA-08);
  - the effective set is the roles' permissions plus the personal ALLOWs minus the personal DENYs: a deny always
    wins, roles only allow (no role-level DENY); an assignment past its `valid_until` grants nothing;
  - a permission whose prerequisite a personal DENY removed is dropped from the effective set too;
  - read per request from PostgreSQL through `TokenPermissions` (the token keeps the user id; no Redis
    permission cache and no revocation keys); the endpoints, the policies and `/users/me/access` stay as they
    are: moving from Level 1 changes no endpoint, policy or client.
- **Detect:** a role-level DENY; permissions cached past the request; an effective set computed in two places;
  a table without its audit trigger.
- **Severity:** High.

### AUTHZ-12: Managing roles
- **Check:**
  - an admin grants only what they hold themselves, and never changes their own grants or roles;
  - saving a role that misses a prerequisite of one of its permissions is refused (400 with its code); no
    implied grant fills it in;
  - a role is deleted only while no one holds it (409 otherwise);
  - built-in roles are seeded from the `Role` enum and **copied**, never linked: editing one changes the copy;
  - a new permission arrives with a changeset that grants it to the built-in roles that should hold it, so
    existing installations get it on upgrade;
  - limits stay out of role tables (AUTHZ-04).
- **Detect:** a grant endpoint without the "holds it" check; self-grants; a cascade delete of assignments;
  seeds that read the enum at run time; a permission constant with no changeset.
- **Severity:** High.
