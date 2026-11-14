package com.example.app.common.dropdown;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a JPA {@code @Entity} as the source of a frontend dropdown. One shape for every dropdown
 * ({@code CursorWrapper<DropdownItem>} over {@link DropdownFilter}), so the frontend has one dropdown
 * component and one fetch helper. The golden example is the todo list dropdown
 * ({@code TodoListRepositoryImpl.findDropdownItems}, {@code TodoListService.dropdown}).
 *
 * <p>The rules are house-review's REST-13; how to generate the endpoint is crud-gen's
 * {@code references/dropdown.md}.</p>
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
