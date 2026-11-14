package com.example.app.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.PastOrPresent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.MessageSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MessageSourceConfigTest {

    private record Dated(@PastOrPresent LocalDate day) {
    }

    @Test
    @DisplayName("A message renders a doubled apostrophe as one, and a {0,number,#} year without grouping; a plain {0} groups it")
    void messageFormatRules() {
        MessageSource messages = MessageSourceConfig.messageSource("classpath:messages-test/format");
        Object[] year = {2026};

        assertThat(messages.getMessage("FORMATTED_YEAR", year, Locale.ENGLISH)).isEqualTo("You can't take more days in 2026.");
        assertThat(messages.getMessage("PLAIN_YEAR", year, Locale.ENGLISH)).isEqualTo("Days left in 2,026.");
        assertThat(messages.getMessage("SINGLE_APOSTROPHE", null, Locale.ENGLISH)).isEqualTo("You cant.");
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
