package com.example.app.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.PastOrPresent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MessageSourceConfigTest {

    private record Dated(@PastOrPresent LocalDate day) {
    }

    @Test
    @DisplayName("The validator's now is the application Clock: a day after its today violates, though it is in the real past")
    void validatorReadsTheApplicationClock() {
        Clock fixed = Clock.fixed(Instant.parse("2001-01-01T12:00:00Z"), ZoneOffset.UTC);
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("clock", fixed));
        try (LocalValidatorFactoryBean validator = MessageSourceConfig.validator(MessageSourceConfig.messageSource(),
                beans.getBeanProvider(Clock.class))) {
            validator.afterPropertiesSet();

            Set<ConstraintViolation<Dated>> later = validator.validate(new Dated(LocalDate.of(2001, 1, 2)));
            Set<ConstraintViolation<Dated>> today = validator.validate(new Dated(LocalDate.of(2001, 1, 1)));

            assertThat(later).extracting(violation -> violation.getPropertyPath().toString()).containsExactly("day");
            assertThat(today).isEmpty();
        }
    }
}
