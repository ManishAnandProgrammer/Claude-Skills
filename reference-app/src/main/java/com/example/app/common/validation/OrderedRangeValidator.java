package com.example.app.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.DirectFieldAccessor;

import java.util.Objects;

class OrderedRangeValidator implements ConstraintValidator<OrderedRange, Object> {

    private String from;
    private String to;

    @Override
    public void initialize(OrderedRange constraint) {
        this.from = constraint.from();
        this.to = constraint.to();
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (Objects.isNull(value)) {
            return true;
        }
        // Fields, not getters: the same for a filter class and a request record.
        DirectFieldAccessor fields = new DirectFieldAccessor(value);
        Object lower = fields.getPropertyValue(from);
        Object upper = fields.getPropertyValue(to);
        if (Objects.isNull(lower) || Objects.isNull(upper) || compare(lower, upper) <= 0) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode(from)
                .addConstraintViolation();
        return false;
    }

    /** The annotation's contract: both bounds have one {@code Comparable} type. */
    @SuppressWarnings("unchecked")
    private static int compare(Object lower, Object upper) {
        return ((Comparable<Object>) lower).compareTo(upper);
    }
}
