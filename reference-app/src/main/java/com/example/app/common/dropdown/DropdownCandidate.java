package com.example.app.common.dropdown;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a JPA {@code @Entity} as the source of a frontend dropdown. One shape for every dropdown,
 * so the frontend has one dropdown component and one fetch helper. The golden example is the todo
 * list dropdown ({@code TodoListRepositoryImpl.findDropdownItems}, {@code TodoListService.dropdown}).
 *
 * <hr>
 * <h2>LLM CONTRACT: READ BEFORE GENERATING DROPDOWN CODE</h2>
 *
 * <p>When an entity carries this annotation, generate, in its module's layers:</p>
 * <ol>
 *   <li><strong>Repository</strong>: {@code CursorBatch<DropdownItem> findDropdownItems(<Filter> filter, <scope>, int size)}
 *       in the {@code Custom<Entity>Repository}, implemented with the <strong>Criteria API</strong>, written
 *       top to bottom with {@code Cursor}, {@code Seek} and {@code CursorBatch.of} (never {@code @Query}: the
 *       generic filter makes every predicate conditional);</li>
 *   <li><strong>Service</strong>: {@code CursorWrapper<DropdownItem> dropdown(<Filter> filter)}: refuses the
 *       parameters that don't apply (below), resolves the batch size with the injected {@code Paging}'s
 *       {@code cursorSize(filter)}, and wraps the repository's batch
 *       ({@code CursorWrapper.of(batch.items(), batch.nextCursor())});</li>
 *   <li><strong>Controller</strong>: {@code dropdown(@Valid <Filter> filter)}, mapped
 *       {@code @GetMapping(path = "/dropdown", version = "v1+")} under the class-level
 *       {@code @RequestMapping("/api/{version}/<resource-plural>")} (never a full or versioned path on the
 *       method), returning the service result as is, and documented in the API interface.</li>
 * </ol>
 *
 * <h2>Response</h2>
 * <p>Always {@code CursorWrapper<DropdownItem>}: one batch under {@code response} and
 * {@code pageInfo {nextCursor, hasMore}}, never a total. {@link DropdownItem} is {@code {value, label}}
 * and nothing else; {@code value = <valueField>}, {@code label = <displayField>}. A large set (thousands
 * of cities) is browsed batch by batch or narrowed with {@code query}.</p>
 *
 * <h2>Parameters</h2>
 * <p>Every dropdown takes {@link DropdownFilter}, which extends {@code CursorFilter}: {@code size} (the
 * default and the maximum come from {@code app.limits.cursor-page}), {@code after} (the previous batch's
 * {@code pageInfo.nextCursor}), {@code status}, {@code sortDirection} and {@code query}. Mandatory and
 * optional filters go in a subclass, {@code <Entity>DropdownFilter extends DropdownFilter}, one field per
 * entry named after its dot-path ({@code "project.id"} → {@code projectId}): mandatory ones
 * {@code @NotNull}, optional ones nullable with a {@code requireXPredicate()}. Never loose
 * {@code @RequestParam}s, never a named sub-path ({@code by-x-id/dropdown}). A true child resource puts
 * its parent in the class-level path ({@code /api/{version}/projects/{projectId}/sections}, then
 * {@code /dropdown}).</p>
 *
 * <p>A parameter that doesn't apply is a 400 {@code REQUEST_PARAMETER_INVALID} naming it
 * ({@code withExtension("parameter", …)}), never silently ignored: a {@code status} other than
 * {@code ACTIVE} on an entity without an {@link #activeField()}, and a {@code query} on a dropdown that
 * isn't {@link #searchable()}.</p>
 *
 * <h2>Query rules</h2>
 * <ul>
 *   <li>A projection: {@code builder.construct(DropdownItem.class, value, label)}, never entities;</li>
 *   <li>{@link #activeField()}: {@code status=ACTIVE} (the default) → {@code = TRUE}, {@code INACTIVE} →
 *       {@code = FALSE}, {@code ALL} → no predicate;</li>
 *   <li>a keyset on {@code (<sortField>, <valueField>)}: {@code Seek.after} and {@code Seek.order} over the
 *       same two columns, the unique {@code valueField} last, and a sort name per direction
 *       ({@code NAME_ASC}, {@code NAME_DESC}) given to {@code Cursor.read} and {@code Cursor.write}, so a
 *       cursor taken in one direction is refused in the other. {@code sortField} defaults to
 *       {@code displayField}; the request's {@code sortDirection} overrides the annotation's;</li>
 *   <li>{@link #searchable()}: {@code TextSearch.contains(builder, <displayField>, query)}, a case-insensitive
 *       "contains" that needs no escaping. Never {@code LIKE};</li>
 *   <li>an index on the scope and filter columns followed by {@code (<sortField>, <valueField>)}, so each
 *       batch is an index range;</li>
 *   <li>scoped like the resource's reads: a dropdown never lists what the caller couldn't fetch.</li>
 * </ul>
 *
 * <h2>Security</h2>
 * <p>{@code @PreAuthorize("isAuthenticated()")}, unless the resource is more restricted: then ask.</p>
 *
 * <h2>Cache</h2>
 * <p>{@link #cacheable()} only for static master data: {@code @Cacheable} on the service method,
 * {@code @CacheEvict(allEntries = true)} on every write, and a shared cache when more than one
 * instance runs.</p>
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DropdownCandidate {

    /** Human name for the API docs, e.g. {@code "Project"}. Defaults to the entity's simple name. */
    String label() default "";

    /** The field shown to the user ({@code DropdownItem.label}): persistent and non-null. */
    String displayField() default "name";

    /** The identifier ({@code DropdownItem.value}): a {@code Long}, {@code UUID} or {@code String} id. */
    String valueField() default "id";

    /** Filters the caller must supply; dot-notation for associations ({@code "project.id"}). */
    String[] mandatoryFilters() default {};

    /** Filters the caller may supply, applied only when present. */
    String[] optionalFilters() default {};

    /** Boolean "active" field, or {@code ""} when the entity has none. */
    String activeField() default "";

    /** Default order; {@code ""} means {@link #displayField()}. Ties are broken by {@link #valueField()}. */
    String sortField() default "";

    /** Default direction of {@link #sortField()}; the request's {@code sortDirection} overrides it. */
    SortDirection sortDirection() default SortDirection.ASC;

    /** {@code true} for large sets: enables the {@code query} parameter. */
    boolean searchable() default false;

    /** {@code true} only for static master data. */
    boolean cacheable() default false;

    enum SortDirection {
        ASC,
        DESC
    }
}
