package com.example.app.common.dropdown;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DropdownCandidate {

    /** {@code ""} means the entity's simple name. */
    String label() default "";

    String displayField() default "name";

    String valueField() default "id";

    String[] mandatoryFilters() default {};

    String[] optionalFilters() default {};

    String activeField() default "";

    /** {@code ""} means {@link #displayField()}. Ties are broken by {@link #valueField()}. */
    String sortField() default "";

    SortDirection sortDirection() default SortDirection.ASC;

    boolean searchable() default false;

    boolean cacheable() default false;

    enum SortDirection {
        ASC,
        DESC
    }
}
