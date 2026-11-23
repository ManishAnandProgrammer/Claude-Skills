# Authorization (AUTHZ-xx)

Who may do what: a **permission** says whether a caller may use an action at all, a **policy** says whether
they may use it on this record. The default for every house project is Level 1 below: permissions in code,
roles in code listing them, a policy per owned resource. Level 2 (*With the roles piece*) moves the roles into
tables an admin edits, and changes no endpoint, policy or `/users/me/access` (the account's `role` field goes: a
breaking change of the account's response). The profile's `authorization` key says which
level a project is on (`permissions`, the default; `roles-piece` with that piece).

Golden code, in the reference application (`~/.claude/skills-reference/src/main/java/com/example/app/`):
`common/security/` (`Permission`, `Area`, `Reach`, `Requires`, `SignedIn`, `Public`, `Decision`,
`AllowedAction`, `AllowedActionReason`, `AllowedActionMapper`, `TokenPermissions`, `CurrentUser.permissions()`/`holds()`), `config/SecurityConfig.jwtAuthenticationConverter`,
`user/domain/Role`, `user/service/RolePermissions`, `GET /users/me/access` (`UserController.access`,
`UserMapper.toAccessResponse`, `AccessResponse`), `todo/service/TodoPolicy` (and its use in `TodoService`,
`TodoRepositoryImpl.findByFilter`, `TodoAllowedActionsResponse`), `planning/service/PlanPolicy` (state rules with
every reason at once, `PlanAllowedActionsResponse` on the detail and the list's rows), `common/dropdown/DropdownFor` with the
todo-list and tag dropdowns and the todo dropdown under its list (the dependent one, `TodoService.dropdown`),
`media/api/UploadPurpose`. Tests: `archunit/ConventionArchTest`
(`handlersDeclareWhoMayCallThem`, `methodSecurityIsDeclaredOnce`, `noRoleChecks`) proven on its fixtures in
`ArchRuleScopeTest`, `common/EndpointContractIntegrationTest` (`everyRequiredPermissionIsChecked`,
`everyPublicEndpointAnswersWithoutAToken`, `everyEndpointNeedsAToken`), `common/security/PermissionTest`,
`user/domain/RoleTest`, `user/AccessIntegrationTest`, `todo/service/TodoPolicyTest`, `planning/service/PlanPolicyTest`,
`common/security/DecisionTest`, `planning/PlanAllowedActionsIntegrationTest`, `todo/TodoRequesterIntegrationTest`,
`common/dropdown/DropdownForTest`, `todo/TodoListIntegrationTest` (the dropdown's `for`),
`todo/TodoDropdownIntegrationTest` (the dependent dropdown: its out-of-reach parent, one statement). With tenancy:
`overlays/tenancy/.../user/service/MembershipService.lockAsCurrentAdmin` (TEN-08).

Names: Spring Security calls a permission an *authority* and a role a *group*; the house says Permission, Role
and Policy.

## Level 1: permissions in code

### AUTHZ-01: Permissions are one enum of capabilities
- **Check:**
  - `Permission` is an enum in `common/security`; each constant becomes a Spring authority of exactly its name,
    and nothing else does (no `ROLE_` authority, no scope). Spring Security 7 adds its own `FACTOR_BEARER` (how
    the caller signed in), which grants nothing;
  - a name is `<RESOURCE>_<ACTION>`, by capability (`ACCOUNT_READ`, `ACCOUNT_STATUS_CHANGE`, `METADATA_MANAGE`,
    `UPLOAD_POLICY_MANAGE`, `TODO_EDIT_OWN`), never by role or screen (`ADMIN_PAGE`, `MANAGER_STUFF`), with a
    reach suffix only where the business tells reaches apart: `_OWN` (the caller's own records), `_ASSIGNED`
    (records the owning module says are theirs to work), none (every record);
  - the reach is declared again on the constant (`Reach`), and code reads `reach()`, never the name
    (`PermissionTest` keeps the two in step);
  - each constant names its menu `Area` (AUTHZ-06) and its **prerequisites**: the permissions it needs to be
    usable (`TODO_EDIT_OWN` needs `TODO_READ_OWN`, `ACCOUNT_STATUS_CHANGE` needs `ACCOUNT_READ`), declared on the
    enum, never in a table. A prerequisite is checked (AUTHZ-02), never granted for it: no implied grants;
  - a read that only serves a change (finding the account to suspend) is still its own permission, the change's
    prerequisite, never folded into the change: an assignment screen needs to find people without the right to
    suspend them (`ACCOUNT_READ` guards `GET /users?email=`);
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
  - **freshness** is known and tested: permissions come from the token's role, so a role
    change reaches the person within one access-token lifetime, or at their next request with the
    `session-version` piece (SEC-07) or the `roles` piece (which reads the tables on every request, AUTHZ-11).
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
  - an owned resource has a `<Resource>Policy` in its module (golden `TodoPolicy`, `PlanPolicy`): plain Java `if`s
    on the record and the caller, each rule a method returning a `Decision` (allowed, or refused with every unmet
    precondition, each an `ErrorCode` and its arguments; named apart from Spring's `AuthorizationDecision`, which
    carries no reason). A rule with several preconditions collects them in a `List<Decision.Refusal>` with plain
    `if`s, most fundamental first (the record's own state before its parent's: `PlanPolicy.reschedule`), and
    returns `Decision.of(refusals)`: no builder, no rules as lambdas. A reach refusal (not yours, not found)
    returns at once, alone (`Decision.refused`): listing what else is missing to someone who may never act is
    noise and can leak. A rule takes the fields it reads when the list's row and the detail's entity both need it
    (`PlanPolicy.reschedule(id, status, todo)`);
  - it holds every record rule that decides an action: reach (ownership, assignment), state (a closed record
    stays closed), amounts, dates, relationships (the requester reads, the owner edits). A state invariant that
    holds for every caller stays the entity's guarded method too (golden `Plan.requirePlanned`, inside
    `Plan.reschedule`), and the policy names the same code for it (a guard throws, so the policy mirrors it with
    an `if` on the same field; the policy's table test pins the code); a rule about the caller (reach, a
    relationship, an amount bounded per permission) is the policy's alone. A limit (a concession up to 2,000) is
    the module's setting or data, **never a column of a role or a permission**;
  - the write path calls `policy.x(record, caller).orThrow()` after loading the record (and whatever else the
    rule reads, a parent's locked view included), and before the entity's change; `orThrow` throws the first
    refusal, so the error body keeps one `errorCode` (no list in error responses); a write that locks loads
    through the policy's condition (`TodoPolicy.editScope`, a condition of the locking statement), so a refused
    caller never takes the owner's lock (DATA-29), and on an empty result reads the record unlocked for the policy
    to say why (`TodoService.lockEditableTodo`); the policy never picks an HTTP status: the error contract's mapper does, and where the house
    answers another owner's record with 404 (REST-16) the policy refuses with the `<ENTITY>_NOT_FOUND` code;
  - no rule engine, expression strings, reflection or rules as data.
- **Detect:** a record locked by id before its policy decides; ownership compared in the controller or the mapper; an `if (role == ...)` in a service; a limit
  read from the role; the same "is it theirs" written in two services; a policy that throws an HTTP status or
  a Spring exception; a record rule inside a `@PreAuthorize` expression.
- **Not a finding when:** a resource whose only rule about the caller is the owner's reach, and no action whose
  answer differs between its records (AUTHZ-07), keeps the owner id in its queries (`TodoListService`,
  `TagService`; `PlanService` keeps its owner condition in its queries too, and its state rules are
  `PlanPolicy`'s because they are `allowedActions` flags): the policy shape is for a resource with a second party,
  a state, amount or date rule that decides an action, or an `allowedActions` flag.
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
    caller (with tenancy, the current membership's: its token's role), read from the token, no database read
    (with the roles piece, the set the per-request read gave the security chain: still no read of its own);
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

### AUTHZ-07: `allowedActions` for every action the record decides, with every reason
- **Check:**
  - a response carries `allowedActions` for each action whose answer can differ between records the caller sees,
    state rules included (a DONE plan can't be rescheduled, a returned loan can't be edited): the client shows a
    button by its flag and **never re-derives one from a record's fields** (`status === 'ACTIVE'`). Actions the
    permission alone decides, and create buttons, stay on `GET /users/me/access` (AUTHZ-06), not repeated per
    record;
  - each action is an `AllowedAction` (`common/security`): `{"allowed": false, "reasons": [{"errorCode":
    "PLAN_CANCELLED", "message": "..."}, ...]}`, made from the `Decision` of the policy method the write calls,
    in one place (`AllowedActionMapper.toAllowedAction(name, decision)`), never assembled by hand. `reasons` lists
    **every** unmet precondition at once (shown one at a time, a person fixes one on another screen, comes back
    and meets the next), in the policy's order, empty when allowed; a reach refusal stands alone. `errorCode` is
    the code the write would answer, `message` that code's message from the existing message files in the
    request's language with the same arguments (the write's Problem Details `detail`): no new message texts. The
    write still answers one Problem Details, the first refusal's;
  - the flags of a list's rows come from fields the row already has; a rule needing another table's data comes from
    the list's own query (an aggregate in the projection, DATA-26) or the page's one facade lookup (ARCH-04's
    `get<Parent>Views`, golden `PlanService.withTodos`), never a query per row;
  - **an option a rule can refuse right now** (a done todo for a new plan, a booked room, an assigned driver) is
    flagged the same way when its screen shows it disabled with its reasons: a write's selectable options (AUTHZ-08),
    each `{value, label, selectable}`, `selectable` the `AllowedAction` of the policy method the write calls; and
    days a constraint holds (DATA-35) are shown by an availability read whose taken spans carry the write's reason.
    The UI never derives either from a record's fields (`book.status === 'WITHDRAWN'` labelling an option);
  - **what a reason may say** (this holds for the write's error message too: both are the same message line):
    only what the caller is entitled to know and can act on. Its message arguments are values the caller can
    already see (the record's own id, its own status; never another person's data: "reserved by <name>"); a
    sensitive rule (a threshold one could game, a fraud or risk flag, a tip-off) refuses with a generic code,
    its detail staying in the logs; a reach refusal looks like not-found and stands alone; no internal ids or
    table names. Each policy test asserts the refusal's arguments, not only its code;
  - **the authorization log** (Spring Security's spirit: off by default, one switch): one named logger,
    `{{rootPackage}}.authorization`, so `logging.level.{{rootPackage}}.authorization: DEBUG` (or `TRACE`) turns it
    all on, and nothing else does (the dev profile keeps it at INFO under the app's DEBUG). DEBUG: each role's
    permissions once at startup, a line per role (`RolePermissions.logRolePermissions`), and a write its policy
    refused, by its codes (`Decision.orThrow`); TRACE: every decision, allowed ones too (`Decision`'s factories),
    and each flag computed for a response, by its action's name (`AllowedActionMapper`). Codes only: no message
    text, no message arguments, no email or name (the log line's context already carries the request id, trace
    id, user id and endpoint); parameterized SLF4J, free when off; written in those places only, never a log line
    per policy method. It is not an audit (the change history is, DATA-08) and not a metric. A test proves a
    refused write writes its DEBUG line (`PlanAllowedActionsIntegrationTest.aRefusedWriteIsLoggedAtDebug`).
- **Detect:** an `allowedActions` flag computed by its own condition (a copy of the policy), or a boolean flag
  with no reasons; an option labelled or disabled by the client from its fields (`[withdrawn]` from a status); a reasons list cut to the first; a reason's message written for the flag instead of the code's
  message; a client that shows a record's button by its status or fields; flags for actions the permission alone
  decides; a flag that reads another table per row; message arguments taken from another person's record, or a
  threshold in a message; an authorization log line with a message, a name or an email, or one written in a policy
  method; the authorization logger on by default.
- **Not a finding when:** a refusal only the database decides at the write (`*_IN_USE` from a foreign key, a unique
  key's `*_TAKEN`) has no flag: it is answered when it happens. A record's delete that nothing but such a key
  refuses stays on the permission alone (golden: a todo's delete, `TODO_IN_USE`).
- **Severity:** Medium; High for a reason that shows another person's data or a sensitive rule's detail.

### AUTHZ-08: Shared dropdowns are named by the action they serve
- **Check:**
  - one dropdown per resource, used by every screen that picks one (REST-13), with a **required**
    `for=<Permission>` query parameter whose OpenAPI schema is the Permission enum (`DropdownFor.PARAMETER`);
  - the server refuses (403 `ACCESS_DENIED`) unless the caller holds that permission, and narrows the
    options to its reach (`Reach`): the reach's condition is the owning module's (the caller's own records for
    `OWN`, an indexed join for `ASSIGNED`, none for `ALL`); a reach the dropdown doesn't list is 400
    `DROPDOWN_REACH_UNSUPPORTED` at `for` (`DropdownFor.requireReach`);
  - **one query**: the permission check is in memory, the reach is one more condition of the same Criteria
    query, and a dependent dropdown takes its parent id as a plain filter in that same `WHERE`, with no separate
    parent check and no walk up the chain: an out-of-reach parent yields an empty page and nothing leaks. Each
    level of a dependent chain is independent. A test counts one statement per call;
  - a dropdown never lists who may call it, never needs a READ permission of its own, and the write it feeds
    re-checks everything. The parallel is the upload purpose: the client names it, the server checks it and
    applies its rules;
  - **a write's selectable options** (only where a domain rule can refuse an option right now and the screen
    shows it disabled with its reasons; a plain filter on the shared dropdown, the refused ones left out, is the
    other answer, the developer's choice per screen): a second item type, `SelectableDropdownItem {value, label,
    selectable}` with its own OpenAPI schema, `selectable` an `AllowedAction` (AUTHZ-07) from **the policy method
    the write calls**; `DropdownItem` stays exactly `{value, label}`, never with an optional flag whose absence
    means allowed. Its flags are the write's rules, so the write's module serves it, beside the write:
    `GET /<write's resources>/<resource>-options` (golden `GET /plans/todo-options`, `PlanService.todoOptions`),
    `@Requires(<the write's permission>)` and that permission's reach, no `for` (it serves one action). **One
    statement**: the batch with each rule's data in it, a column of the projection (`TodoView.open`) or an
    `EXISTS` on the module's own table (an overlap with the asked dates); when the candidates are another
    module's (through its facade's batch, `Todos.getOwnedTodoViewBatch`) and the rule's data the write's module's
    own table, one more statement for the whole batch (ARCH-04 forbids the join), never one per option. The write
    still decides (its checks, its lock, its constraint: DATA-35), answering the same code and message.
- **Detect:** a dropdown per screen (`/classes/for-attendance`, `/classes/for-fees`; not a write's selectable
  options above, which serve one write's rules); a `selectable` flag on `DropdownItem`, or one computed by a
  condition of its own instead of the write's policy method, or by a statement per option; a public dropdown; a
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
    with every refusal in order, each code with its arguments (`TodoPolicyTest`, `PlanPolicyTest`); `Decision`'s
    own test keeps two refusals' order and `orThrow` throws the first (`DecisionTest`);
  - **the allowed actions' shape**: an integration test per resource with flags, on the detail and a list row,
    asserting `allowed`, every reason's `errorCode` and that a reason's `message` equals the write's `detail`
    under the same `Accept-Language` (`PlanAllowedActionsIntegrationTest`, `TodoRequesterIntegrationTest`);
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
    lifetime old, and a stale token that changes access makes its change permanent. With the roles piece the
    caller's effective set is read again under that lock (`PersonAccessService.lockToManage`), the same rule.
- **Detect:** `holds(...)` replaced by a role comparison; an access-changing write checked by its annotation
  alone.
- **Severity:** High for an access-changing write on the token alone; Medium otherwise.

## Level 2: with the roles piece

These rules apply once the project has the `roles` piece (profile `authorization: roles-piece`, bootstrap's `roles`,
which needs `sign-in` and `change-history`); a Level 1 project is not reviewed against them. Golden code, in the
skills repository's `overlays/roles/` (`scripts/bootstrap-copy.sh --with ...,roles` copies it over the reference):
`user/domain/AccessRole`, `RolePermission`, `RoleAssignment`, `PersonalGrant`, `GrantEffect`, `EffectiveAccess`,
`PermissionCodes`, `AccessRow`; `user/repository/impl/RoleAssignmentRepositoryImpl` (the per-request read);
`user/service/RolePermissions` (`TokenPermissions` from the tables), `PersonAccessService`, `AccessRoleService`,
`AccessRoleManager`; the controllers `AccessRoleController`, `PersonAccessController`,
`PermissionCatalogueController`; changeset `0037-roles.sql`; the reference's `roles` blocks (`SecurityConfig`'s
converter and entry point, `AccountStatusService`, `UserService.signup`, `TokenService`, `User`). With tenancy also
`user/service/TenantMemberManager` (a member's add and removal, the owner's ADMIN at provisioning), every `roles` block
of the tenancy overlays and of the reference's tenancy blocks (`grep -rl roles:begin overlays/tenancy*`:
`MembershipService`, `TenantMembership`, `TokenService`, `ImpersonationService`, the members' controller, API, requests,
filter, responses, mapper and repository, the platform APIs, `AccountStatusService`, the runbooks), schema per tenant's platform changesets
`0038-memberships-role-expand.sql` and `0039-memberships-role-contract.sql`. Tests:
`RolePermissionsIntegrationTest`, `RolesIntegrationTest`, `PersonAccessIntegrationTest`,
`BuiltInRolesIntegrationTest`, `EffectiveAccessTest`, `service/PersonAccessLockTest`, `service/RolePermissionsTest`, with
tenancy `TenantAccessIntegrationTest` (in place of Level 1's `TenantMemberIntegrationTest`), and every other Level 1
test, unchanged but where it asserted a role column or claim.

### AUTHZ-11: The tables, the one source, and the effective set
- **Check:**
  - tables `roles` (a code, a name, `built_in`: the `Role` constant a built-in role was seeded from, or null;
    `version`), `role_permissions (role_id, permission)`, `user_roles (user_id, role_id, valid_until)` and
    `user_permissions (user_id, permission, effect ALLOW|DENY, valid_until)`, one grant per person and permission
    (Spring Security's own `groups`, `group_members`, `group_authorities`): entities with the audit columns, real
    foreign keys with the default ON DELETE, the reads' indexes leading with the person and the role, all four
    audited (DATA-08: the piece requires `change-history`);
  - **one source of a person's access:** the account's single `role` column and the token's `roles` claim are gone
    (the piece's changeset copies each account's role into `user_roles`, onto its built-in role, then drops the
    column; `TokenService` writes no claim); every endpoint that set the role became the assignment endpoints.
    A role field in a response, a role claim, or a second table holding a person's roles is a finding;
  - permission codes are text the application checks: a code that names no `Permission` grants nothing and is
    logged once per code (`PermissionCodes.known`), never an exception and never a default;
  - **the effective set** (`EffectiveAccess`): the current roles' permissions plus the current personal ALLOWs
    minus the current personal DENYs; a deny always wins; roles only allow (no role-level DENY); an assignment or a
    grant past its `valid_until` gives nothing; a permission whose prerequisite isn't held (a DENY took it, or
    nothing gave it) is dropped too, until nothing changes: no implied grant ever;
  - **read on every authenticated request** through `TokenPermissions` (the Level 2 `RolePermissions`), in **one
    indexed statement** joined with the account's status (a suspended account holds nothing on its next request),
    inside a short read-only transaction that returns its connection before the handler runs, after admission
    control, through the lazy proxy, and invisible to Hibernate's statistics (plain JDBC), so the handlers'
    statement counts don't change. A test counts it (`RolePermissionsIntegrationTest.theReadIsOneStatement`);
  - **the read's TRACE line** (the authorization log, AUTHZ-07): with `{{rootPackage}}.authorization` at TRACE,
    the per-request read writes one line, `Effective access: roles [...], allowed [...], denied [...], held [...]`
    (`PersonAccessService.effectivePermissions`, `EffectiveAccess.describe`): the role codes that gave a
    permission, the personal ALLOWs and DENYs, and the result, codes only (the line's context names the user, the
    request and, with tenancy, the tenant), so "why may this person do X" is answered from the log. Written once,
    in that read (every tenancy mode goes through it), and built only when TRACE is on (`isTraceEnabled()`: free
    when off). Level 1's startup line per role doesn't apply here (roles are data). A test turns TRACE on and finds
    the line, and finds none when it is off (`RolePermissionsIntegrationTest.thePerRequestReadIsLoggedAtTrace`);
  - a database outage during that read is the request's 503 with its code and `Retry-After` (an
    `AuthenticationException` the entry point answers, `SecurityConfig.PermissionsUnreadable`), never a 401 (the
    client would sign out) nor a 500;
  - no Redis permission cache, no permissions in the token, no revocation keys: a change applies on the person's
    next request, and `/users/me/access` and every check read the same set;
  - the endpoints, the policies, `/users/me/access` and the dropdowns' `for` are Level 1's, unchanged (the account's
    `role` field goes, a breaking change of its response, announced): the Level 1
    tests pass in a copy with the piece, but the ones that asserted the role column or claim (those are marked
    `roles:else` and have Level 2 counterparts);
  - **with tenancy** (TEN-08): roles belong to the tenant and assignments and grants to the person's member row
    (`members`, TEN-16): row mode with `tenant_id`, forced policies, keys that keep a row in its tenant and the
    member-tenant dependency statistics (TEN-33), schema per tenant in each tenant schema (the tenant changelog). The
    per-request read runs in the token's tenant (`RolePermissions` scopes it with `TenantScope`, since the security
    context isn't set yet) and joins the platform's account, membership and tenant register in the same statement, so
    a suspended account, a removed member and a tenant that isn't ACTIVE hold nothing on their next request (schema
    per tenant names the platform's tables there, the one exception to TEN-21, `TenancyArchTest`); a token without a
    tenant (platform staff) holds no tenant permission and reads nothing (`RolePermissionsTest`). One source per
    tenant too: the membership's `role` column, the members' `role` and `version` fields, the add request's `role`, the
    members list's `role` filter, `GET /users/me/tenants`' `role` and the role change (`PUT /tenant-members/{id}`) go,
    staff's suspension answers `ACCESS_LAST_MANAGER` where Level 1 said `TENANT_MEMBER_LAST_ADMIN` (breaking changes,
    announced), and with tenancy the account's `role` column goes (it never decided anything there).
    Row mode copies each membership's role in its changeset and drops the columns; schema per tenant copies them
    **per schema**, emptying the platform's copy as it goes, makes the column nullable first (`0038`, expand) and
    drops it once no schema with a role left to copy remains (`0039`, contract: a precondition that skips it until
    then, tried again at every start);
- **Detect:** a role-level DENY; permissions cached past the request or put into the token; an effective set
  computed in two places; a second read per request; the role column kept beside the tables; a table without its
  audit trigger; an unknown code that grants or throws; with tenancy, a read of a tenant's access outside its scope,
  a membership's role column or role field beside the tables, a schema-per-tenant column dropped without a precondition
  that no tenant schema still has a role to copy.
- **Severity:** High.

### AUTHZ-12: Managing roles and people's access
- **Check:** each rule is a refusal with its error code, and a test:
  1. **grant only what you hold** (403 `ACCESS_NOT_HELD`): a role you create holds only permissions you hold, and you
     edit only a role whose permissions you hold before and after (an edit never takes from everyone who holds the
     role a permission you lack); you give a role only if you hold all its permissions; a personal ALLOW or DENY (or
     its removal) only of a permission you hold, and an ALLOW without an end only of what you hold for good (a grant of
     yours that ends never lives on in someone else's); and checked on the result too, after the change: what the person
     would hold afterwards holds nothing you don't (taking a DENY away would otherwise hand back a permission that
     needed the denied one);
  2. **never your own** roles or grants (403 `ACCESS_OWN_CHANGE`), nor adding to a role you hold (a grant of yours
     that ends would outlive its end through the role);
  3. **never someone above you** (403 `ACCESS_TARGET_HOLDS_MORE`): you change the access of (or suspend, or
     reactivate, without tenancy; with it, remove from the tenant: staff's suspension holds no tenant permission and
     keeps rule 6 per tenant) only a person who holds nothing you don't, a suspended person's grants counted (suspended, a
     superior is still above), which a DENY could otherwise use to sabotage them;
  4. a role saved without a **prerequisite** of one of its permissions is refused (400
     `ROLE_PREREQUISITE_MISSING`), nothing filled in;
  5. a role is **deleted only while nobody holds it** (409 `ROLE_IN_USE`, its assignment's key), assignments never
     cascade; a built-in role is never deleted (409 `ROLE_BUILT_IN`: signup gives it);
  6. **never leave nobody** holding `ROLES_MANAGE` or `ACCESS_GRANT` for good among active accounts (409
     `ACCESS_LAST_MANAGER`): counted after the change, through grants without an end only (an expiring one is
     never the last), every current DENY counted. Rules 2 and 3 already keep the caller of a person's change a
     holder, so in practice it is a role's edit that trips it; the runbook names the operator's SQL for the case it
     happened outside the application. **With tenancy** it is per tenant, among its active members, and keeps
     `TENANT_MEMBERS_MANAGE` too (Level 1's "every tenant keeps an ADMIN", whose `TENANT_MEMBER_LAST_ADMIN` goes with
     the piece): a member's removal and staff's suspension of an account are counted like any change, the
     suspension in every open tenant of the person's (each tenant's count is its own data, read in its scope in a
     short read-only transaction of its own while the suspension holds that tenant's row);
  7. **concurrency:** `version` on the role (409 `OPTIMISTIC_LOCK_CONFLICT`); every change of someone's access (a
     person's change, a role's edit, a suspension, with tenancy a member's add and removal; a new role gives nobody
     anything) first takes a lock every such change shares (DATA-29), then checks rules 1 to 3 and 6 on what the
     database holds under that lock, never on the request's earlier read: any two changes that could each take the
     last holder away queue. **The lock set is stable:** what a change counts can't move once it holds the lock.
     Without tenancy that is, in id order, the account rows of the caller, the person it changes and every account
     holding the kept permissions for good, those holders **read again once locked, and any new one locked too,
     until the set stays the same** (read before their lock, every holder could be replaced in between and two
     changes lock disjoint sets; `PersonAccessLockTest`). With tenancy it is one fixed row, the tenant's
     (`Tenants.lockForMemberChange`), which every access change in the tenant locks first; staff's suspension locks
     the account's row, then each open tenant's of the person, in id order.
  - the API: the catalogue (`GET /permissions`: each permission's code, area, reach and prerequisites, and the
    areas; `@SignedIn`, the same list the OpenAPI enum publishes, never who holds what), roles (`/roles`: paged
    list, detail with permissions, create, update with `version`, delete; `ROLES_MANAGE`), the roles dropdown
    (`GET /roles/dropdown?for=<Permission>`, AUTHZ-08: reach `ALL`, in the same statement only the roles whose every
    permission the caller holds, rule 1 as a condition; so someone who gives roles with `ACCESS_GRANT` but doesn't
    manage them picks one; tested with its one statement), a person's access
    (`GET /users/{id}/access`: roles, grants, what they hold with the roles or the ALLOW that give it and what a
    DENY or a missing prerequisite removed) and its changes (`PUT`/`DELETE /users/{id}/roles/{roleId}` with an
    optional `validUntil`, `PUT`/`DELETE /users/{id}/permissions/{permission}` with the effect; `ACCESS_GRANT`,
    which needs `ACCOUNT_READ`);
  - built-in roles are seeded from the `Role` enum's contents **at the time of writing**, as literal SQL in the
    changeset, and **copied**, never linked: an admin's edit changes the copy. A test on the test database (the
    tests that edit a built-in role restore it) compares each built-in role's rows with its constant
    (`BuiltInRolesIntegrationTest`), so a permission added to a constant without its grant changeset fails the build;
  - **a new permission** arrives with a changeset that grants it to the built-in roles that should hold it
    (`INSERT INTO role_permissions ... SELECT ... FROM roles WHERE built_in = 'ADMIN'`), so existing installations get
    it on upgrade (crud-gen asks which built-in roles);
  - **with tenancy** the grant changeset gives every tenant's copy (row mode lifts `FORCE` for its own transaction,
    TEN-34; schema per tenant is a tenant changeset), and a new tenant gets its built-in roles at its provisioning:
    schema per tenant from the tenant changelog's literal seed (it runs into every new schema), row mode by copying
    the enum in the activation's transaction, only what is missing (`AccessRoleService.copyBuiltInsFromTheEnum`: the
    changeset ran before the tenant existed, and seeded every tenant that existed then, a PENDING one included).
    That copy reads the enum at run time, which is right for a new tenant's data; the Detect line below is about
    migrations of existing data. `BuiltInRolesIntegrationTest` compares every tenant's copies, an upgraded tenant's
    (the changesets') and a new one's alike; in row mode a fresh database's tenants all come after the changesets, so
    there the guard bites on the upgrade run (a release's tests on a database seeded at the previous release). The owner gets the tenant's built-in `ADMIN` in the same transaction
    (`TenantMemberManager.provisioned`), a member added later its built-in `USER` (the caller holding all of it, rule
    1), and a removal takes every role and grant of the person in the tenant (added again, they start with `USER`
    alone: a re-add never hands back what a stranger to the caller gave). Staff's impersonation acts, unless they name
    a member, as the oldest member who manages the tenant's members for good (Level 1's oldest ADMIN, read in the
    tenant's scope);
  - limits stay out of role tables (AUTHZ-04).
- **Detect:** a grant endpoint without the "holds it" check; a self-grant; a change of someone who holds more; a
  check on the request's earlier read instead of under the lock; a cascade delete of assignments; seeds that read
  the enum at run time in a migration; a permission constant with no grant changeset; an access change that can
  leave nobody holding the management permissions.
- **Severity:** High.
