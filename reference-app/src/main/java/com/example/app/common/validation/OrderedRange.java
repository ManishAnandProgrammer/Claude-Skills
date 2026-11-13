package com.example.app.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On a filter or request class: the property {@link #from()} is not greater than {@link #to()} when
 * both are present (both inclusive bounds of one range, of one {@code Comparable} type). An inverted
 * range is a mistake the client should hear about (400 naming {@code from}), not an empty list.
 */
@Documented
@Constraint(validatedBy = OrderedRangeValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(OrderedRange.List.class)
public @interface OrderedRange {

    /** The lower bound's property, e.g. {@code "spentOnFrom"}; the violation names it. */
    String from();

    /** The upper bound's property, e.g. {@code "spentOnTo"}. */
    String to();

    String message() default "must not be after {to}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    @Documented
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @interface List {

        OrderedRange[] value();
    }
}
