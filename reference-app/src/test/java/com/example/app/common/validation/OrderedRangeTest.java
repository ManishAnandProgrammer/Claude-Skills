package com.example.app.common.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.validation.beanvalidation.SpringConstraintValidatorFactory;

import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class OrderedRangeTest {

    /** Validators are created as in the application: by Spring, which reaches package-private classes. */
    private static final ValidatorFactory FACTORY = Validation.byDefaultProvider().configure()
            .constraintValidatorFactory(new SpringConstraintValidatorFactory(new DefaultListableBeanFactory()))
            .buildValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @OrderedRange(from = "from", to = "to")
    @OrderedRange(from = "min", to = "max")
    private record Ranges(LocalDate from, LocalDate to, Integer min, Integer max) {
    }

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    private static Set<String> failures(Ranges ranges) {
        Set<ConstraintViolation<Ranges>> violations = VALIDATOR.validate(ranges);
        return violations.stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("Ordered, equal or open-ended ranges are valid")
    void orderedEqualOrOpenRangesAreValid() {
        LocalDate day = LocalDate.of(2026, 11, 1);

        assertThat(failures(new Ranges(day, day.plusDays(1), 1, 2))).isEmpty();
        assertThat(failures(new Ranges(day, day, 5, 5))).isEmpty();
        assertThat(failures(new Ranges(day, null, null, 5))).isEmpty();
    }

    @Test
    @DisplayName("Each inverted range is one violation naming its lower bound")
    void invertedRangesNameTheirLowerBound() {
        LocalDate day = LocalDate.of(2026, 11, 1);

        assertThat(failures(new Ranges(day.plusDays(1), day, 3, 2)))
                .containsExactlyInAnyOrder("from: must not be after to", "min: must not be after max");
    }
}
