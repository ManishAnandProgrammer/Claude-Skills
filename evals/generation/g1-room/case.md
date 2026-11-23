# G1 Room: plain CRUD of a shared catalogue

**Pattern:** a shared root nobody owns (SEC-01), written by admins and read by everyone signed in; an offset list
with a sort and a filter; a searchable dropdown. Golden twins: `Tag` (offset list, dropdown, the active flag left
out), `TodoList` (offset list), `UploadPolicyController` (a write permission only ADMIN holds).

**Run:** `/crud-gen src/main/java/com/acme/g1/room/domain/Room.java` on a `core,sign-in` copy whose baseline
holds `Room` and its changeset `0100-rooms.sql` (fixture/).

## Business rules

- `Room` is the aggregate root of a module of its own, `room` (its own service and controller). Nothing references a
  room yet.
- A room is **shared**: nobody owns one; it is the organisation's catalogue of rooms.
- **Permissions** (Level 1, `Permission` and `Role` in code), in a new menu area `ROOMS`:
  - `ROOM_READ` (every room): the list, the detail and the dropdown. Held by `USER` and `ADMIN`.
  - `ROOM_CREATE`, `ROOM_EDIT`, `ROOM_DELETE` (every room): held by `ADMIN` only (signup is public, so a USER never
    writes the catalogue). Each has `ROOM_READ` as its prerequisite.
- **Fields:**
  - `name`: required text, at most 100 characters; unique ignoring case and surrounding spaces (409
    `ROOM_NAME_TAKEN` on create and update); stored trimmed.
  - `building`: required text, at most 100 characters; not unique.
  - `capacity`: required whole number of seats, at least 1 and at most a configured maximum of 500.
- **Retired or deleted:** a room is hard-deleted; nothing references it, so no `ROOM_IN_USE` yet.
- **Personal data:** none.
- **Retention:** rooms are kept (a business record); no purge.
- **The list** is offset-paged: its consumer is the admin screen of rooms, which shows page numbers and a total.
  - Sorts: `NAME` (A to Z, the default) and `CAPACITY` (fewest seats first).
  - Filters: `minCapacity` (rooms with at least that many seats; 1 to the configured maximum), and `query`, a
    case-insensitive contains search on the name.
- **The dropdown:** searchable on the name, the label is the name, not cached. It lists every room (reach `ALL`).
  No screen picks rooms for a write yet: its screens name `ROOM_READ` in `for` until a booking exists.
- **Update:** a full replace (PUT) of name, building and capacity, with the version the client read.

## Expected outcomes

The gates decide the verdict (README.md); this is what a reader checks by hand in the work copy:
- `RoomApi` / `RoomController` with `GET /rooms` (offset), `GET /rooms/{id}`, `POST`, `PUT /rooms/{id}`,
  `DELETE /rooms/{id}`, `GET /rooms/dropdown?for=`; each handler `@Requires` its permission, the dropdown
  `@SignedIn`;
- `ROOM_READ`, `ROOM_CREATE`, `ROOM_EDIT`, `ROOM_DELETE` in `Permission` (area `ROOMS`, reach `ALL`), `Role.USER`
  holding `ROOM_READ` only, `Role.ADMIN` all four;
- `RoomFilter extends PageFilter` with `ids`, `minCapacity`, `query`, `sort`; `RoomRepositoryImpl.orderOf` an
  exhaustive switch over the metamodel; one index per order in `0100-rooms.sql` (the changeset is not applied, so
  it is edited, not followed by a second one);
- `ROOM_NOT_FOUND`, `ROOM_NAME_TAKEN` with statuses, messages and the constraint mapping in
  `RoomConstraintErrorCodes`; `room.name`, `room.building` in `app.limits.text`, the capacity maximum in
  `limits.yml`; the label's key in `LimitsIntegrationTest.CURSOR_TEXT_KEYS` if the cursor limit needed raising;
- `Room (RoomService)` in `AggregateArchTest.AGGREGATES` and in both profiles; `Room` under `offsetLists`;
- the integration test classes of references/tests.md (CRUD, the 403 of a USER on every write, the list, the index
  test, the dropdown), the controller's `SPRING_ENDPOINT` exclude entry;
- *Decisions per entity* in `.claude/crud-profile.md` with these answers.
