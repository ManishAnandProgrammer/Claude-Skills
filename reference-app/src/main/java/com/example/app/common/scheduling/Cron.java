package com.example.app.common.scheduling;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

public record Cron(String expression) {

    private static final String DISABLED = Scheduled.CRON_DISABLED;

    public Cron {
        if (!DISABLED.equals(expression)) {
            CronExpression.parse(expression);
        }
    }
}
