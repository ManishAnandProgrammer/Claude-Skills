package com.example.app.common.time;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessCalendarTest {

    private static final String PREFIX = "app.business-calendar";
    private static final ValidatorFactory VALIDATION = Validation.buildDefaultValidatorFactory();
    /** 23:30 UTC on 2026-11-23: already the 24th in Kolkata, still the 23rd in New York. */
    private static final Clock LATE_IN_THE_UTC_DAY = Clock.fixed(Instant.parse("2026-11-23T23:30:00Z"), ZoneOffset.UTC);

    @AfterAll
    static void closeValidation() {
        VALIDATION.close();
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"UTC, 2026-11-23", "Asia/Kolkata, 2026-11-24", "America/New_York, 2026-11-23"})
    @DisplayName("Today is the configured zone's calendar day at the clock's instant, whatever zone the clock is in")
    void todayIsTheZonesDay(ZoneId zone, LocalDate today) {
        BusinessCalendar calendar = new BusinessCalendar(new BusinessCalendarProperties(zone), LATE_IN_THE_UTC_DAY);

        assertThat(calendar.today()).isEqualTo(today);
    }

    @Test
    @DisplayName("The zone binds from a region id; an unknown one and none stop the binding")
    void theZoneIsValidated() {
        assertThat(bind(Map.of(PREFIX + ".zone", "Europe/Berlin")).zone()).isEqualTo(ZoneId.of("Europe/Berlin"));
        assertThatThrownBy(() -> bind(Map.of(PREFIX + ".zone", "Mars/Olympus"))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of(PREFIX + ".other", "x"))).isInstanceOf(BindException.class);
    }

    private static BusinessCalendarProperties bind(Map<String, String> values) {
        ValidationBindHandler validation = new ValidationBindHandler(new SpringValidatorAdapter(VALIDATION.getValidator()));
        Binder binder = new Binder(List.of(new MapConfigurationPropertySource(values)), null,
                ApplicationConversionService.getSharedInstance());
        return binder.bindOrCreate(PREFIX, Bindable.of(BusinessCalendarProperties.class), validation);
    }
}
