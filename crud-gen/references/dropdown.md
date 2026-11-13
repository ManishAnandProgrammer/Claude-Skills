# Dropdown endpoint (Step 4.11): the house dropdown standard

One shape for every dropdown in the API, so the frontend writes **one** dropdown component and
one fetch helper for all of them.

Source: the user's `DropdownCandidate` contract from the Espranza project (attribute names,
filter rules, active/sort/search/cache rules, naming), merged with the house decisions of
2026-11-13:
- `DropdownItem` is a record;
- the service builds the wrapper;
- every dropdown accepts one generic parameter object.

house-review checks it as REST-13.

Applies when the entity carries `{{dropdownAnnotation}}` (`@DropdownCandidate`). Bootstrap
creates the shared types.

---

## Shared types (created once by `/crud-gen bootstrap`, in `{{dropdownPackage}}`)

### `@DropdownCandidate`: the per-entity defaults (the user's contract)
```java
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DropdownCandidate {
    String label() default "";              // human name for OpenAPI docs; default = entity simple name
    String displayField() default "name";   // → DropdownItem.label (display text); persistent, non-null
    String valueField() default "id";       // → DropdownItem.value (Long, UUID or String id)
    String[] mandatoryFilters() default {}; // required filters; dot-notation for associations ("parent.id")
    String[] optionalFilters() default {};  // nullable filters, applied only when supplied
    String activeField() default "";        // boolean "active" flag; "" = the entity has none
    String sortField() default "";          // default order; "" = displayField
    SortDirection sortDirection() default SortDirection.ASC;
    boolean searchable() default false;     // true for large sets (people): enables `query`
    boolean cacheable() default false;      // true only for static master data (roles, countries)

    enum SortDirection { ASC, DESC }
}
```
Keep the user's full Javadoc ("LLM CONTRACT…") on the annotation: it is the in-code contract,
updated to the house rules in this file (service wraps, generic filter, Criteria API).

### `DropdownItem`: the one response shape
```java
@Schema(description = "One dropdown option")
public record DropdownItem(
        @Schema(description = """
                The option's id: a UUID string or a number, depending on the resource.
                Send it back as is.""", example = "42")
        Object value,
        @Schema(description = "Text shown to the user", example = "Grade 5")
        String label) {

    public DropdownItem {
        Objects.requireNonNull(value, "Value cannot be null, it should be of type Long, UUID or String");
        Objects.requireNonNull(label, "Label cannot be null");
    }
}
```
JSON is exactly `{"value": …, "label": "…"}`, the same as the original class.

### `DropdownFilter`: the generic parameters every dropdown accepts
```java
@Getter
@Setter
public class DropdownFilter {

    public static final int MAX_LIMIT = 500;   // {{dropdownMaxItems}}

    @Schema(description = "Which records to list (only for entities with an active flag)", defaultValue = "ACTIVE")
    private DropdownStatus status = DropdownStatus.ACTIVE;

    @Schema(description = "Overrides the dropdown's default sort direction")
    private DropdownCandidate.SortDirection sortDirection;

    @Min(1)
    @Max(MAX_LIMIT)
    @Schema(description = "Maximum number of options returned", defaultValue = "500")
    private int limit = MAX_LIMIT;

    @Size(max = 100)
    @Schema(description = "Case-insensitive 'contains' match on the display text (only for searchable dropdowns)")
    private String query;

    public boolean requireQueryPredicate() {
        return StringUtils.hasText(query);
    }
}

public enum DropdownStatus { ACTIVE, INACTIVE, ALL }
```
How the request adjusts the annotation's defaults:

| Request parameter | Effect |
|---|---|
| `status` | The `activeField` rule: `ACTIVE` (the default) → `activeField = TRUE`; `INACTIVE` → FALSE; `ALL` → no predicate (an edit screen showing a now-inactive current value). Ignored when `activeField` is `""` |
| `sortDirection` | Flips the direction of the annotation's `sortField` (default: the annotation's `sortDirection`). Ties are always broken by `valueField` |
| `limit` | Caps the result (`setMaxResults`); over the maximum → 400 |
| `query` | Only when `searchable = true`: `LOWER(displayField) LIKE %query%`. On a non-searchable dropdown it is a 400 `REQUEST_PARAMETER_INVALID`, never silently ignored |

### Filters beyond the generic ones (`mandatoryFilters` / `optionalFilters`)
When the annotation lists filters, generate `EntityNameDropdownFilter extends DropdownFilter`:
- one field per entry, named after the dot-path (`gradeAcademicStream.id` → `gradeAcademicStreamId`);
- mandatory: `@NotNull` (a missing one is a 400 `VALIDATION_FAILED` naming it);
- optional: nullable, with `requireXPredicate()`.

Never individual `@RequestParam`s, and never a named sub-path (`by-grade-level-id/dropdown`).
The **one** exception is a true child resource, whose parent goes in the path
(`/grades/{gradeId}/academic-streams/dropdown`, REST-02).

---

## What to generate per entity

**Naming (fixed):** repository `findDropdownItems`, service `dropdown`, controller `dropdown`.

1. **Repository**: `List<DropdownItem> findDropdownItems(EntityNameDropdownFilter filter)` in
   `CustomEntityNameRepository`, implemented in the package-private `EntityNameRepositoryImpl` with
   the **Criteria API**. The generic filter makes predicates conditional, so always Criteria,
   never `@Query`:
   - projection, never loading entities:
     `query.select(builder.construct(DropdownItem.class, root.get(EntityName_.<valueField>), root.get(EntityName_.<displayField>)))`.
     If the provider rejects the `Object` constructor parameter, select a `Tuple` and map it in a
     stream pipeline;
   - mandatory and optional filter predicates via the metamodel (associations:
     `root.get(EntityName_.parent).get(Parent_.id)`);
   - the status predicate on `activeField`;
   - the search predicate when `searchable` and `requireQueryPredicate()`;
   - `ORDER BY sortField <direction>, valueField`;
   - `setMaxResults(filter.getLimit())`.

   Indexes on the filter columns and the sort column, as one composite index where they are used
   together (pre-flight, DATA-10).
2. **Service**: `public ListWrapper<DropdownItem> dropdown(EntityNameDropdownFilter filter)` on
   the class-level read-only transaction: `return ListWrapper.fromList(repository.findDropdownItems(filter));`.
   **The service wraps.**
   - Scoping: if the resource's normal reads are scoped to the caller (ownership, role, tenant),
     the dropdown is scoped the same way (the original `dropdownBasedOnUserRole` case).
   - `cacheable = true`: add `@Cacheable(cacheNames = "EntityNameDropdown", key = "#filter")`. The
     filter needs a value-based `equals` / `hashCode` (Lombok `@EqualsAndHashCode` on the filter
     classes, which are DTOs, not entities). Add `@CacheEvict(cacheNames = "EntityNameDropdown",
     allEntries = true)` on every write to the entity. With `{{instances}}` set to `multiple`, an
     in-process cache serves stale options on the other instances: use a shared cache, or confirm
     with the user that the data is truly static. A cache provider is a library choice: present it
     first.
3. **API interface** (`EntityNameApi`): `dropdown` with `@Operation(summary = "Options for the
   <label> dropdown")`. The description lists the defaults from the annotation (active only,
   sort, searchable or not). `@ApiResponse` 200 (`ListWrapper` of `DropdownItem`), 400, 401, 403.
4. **Controller**:
   ```java
   @GetMapping("{{urlPrefix}}/entity-names/dropdown")
   @Override
   public ListWrapper<DropdownItem> dropdown(@Valid EntityNameDropdownFilter filter) {
       return service.dropdown(filter);
   }
   ```
   With no extra filters, the parameter type is `DropdownFilter` itself.

   Security: `{{dropdownSecurity}}` (the user's standard: `@PreAuthorize("isAuthenticated()")`,
   since pickers are used across screens). If the resource is more restricted than "any logged-in
   user", or it's not obvious, **ask**. A dropdown never lists items the caller couldn't fetch.

The response is always `ListWrapper` (a dropdown is bounded by its cap; it is not paginated, so no
`pageInfo`).

---

## Tests (in the entity's integration test class)
- `dropdownListsActiveOptionsInDefaultOrder`: an active and an inactive record with unique
  display values. The active one is present, the inactive one absent, the order follows
  `sortField` / `sortDirection`, and each item has exactly `value` and `label`.
- `dropdownStatusAllIncludesInactive`.
- `dropdownSortDirectionReversesTheOrder`.
- Searchable dropdown: `dropdownQueryMatchesPartOfTheLabelIgnoringCase`. Non-searchable:
  `dropdownQueryIsRefused` (400 + errorCode).
- Per mandatory filter: `dropdownWithoutXIs400` (asserts the field in `failed`). Per optional
  filter: a presence and absence pair.
- `dropdownLimitCapsTheResult` and `dropdownLimitOverTheMaximumIs400`.
- `dropdownWithoutATokenIs401`. When reads are scoped: another user's or role's records never
  appear (a presence check first).
- Cacheable: a write evicts it (the new option appears on the next call).

## Checklist
- [ ] Entity carries `@DropdownCandidate`; the generated code honours every attribute.
- [ ] Filter: `DropdownFilter`, or an `EntityNameDropdownFilter` subclass for mandatory and
      optional filters; no loose `@RequestParam`s, no named sub-paths.
- [ ] Repository: Criteria projection to `DropdownItem`, status / search / filter predicates,
      sort + value tie-breaker, `setMaxResults`; indexes present.
- [ ] Service `dropdown(filter)` returns `ListWrapper<DropdownItem>`, scoped like the resource's
      reads; cache and evict only when `cacheable`.
- [ ] Documented in the API interface; the controller only delegates; security per profile or
      asked.
- [ ] Dropdown tests generated and green.
