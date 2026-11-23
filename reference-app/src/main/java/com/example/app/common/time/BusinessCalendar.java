package com.example.app.common.time;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Today as the business counts it, for a rule a date decides (a leave already begun, a deadline that ends at
 * midnight): the organisation's calendar day in {@code app.business-calendar.zone}, the one place the backend reads a
 * zone (house-review JAVA-25, DATA-37). A date a user picks is still stored as sent, and a moment is an Instant.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class BusinessCalendar {

    private final BusinessCalendarProperties properties;
    private final Clock clock;

    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), properties.zone());
    }
}
