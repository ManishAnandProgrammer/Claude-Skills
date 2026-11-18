package com.example.app.common.scheduling;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

/**
 * A job's schedule as a settings value ({@code app.jobs.<job>.cron}): a cron expression, or {@code "-"}, which
 * switches the job off as it does in {@code @Scheduled(cron = "${...}")}. Checked when the settings bind, so a bad
 * expression stops startup naming its key; Spring's converter binds the text through {@link #of(String)}
 * ({@code SettingsBindingTest}).
 *
 * <p>The job's {@code @Scheduled} still reads the raw property: the annotation keeps the scheduler's run
 * observation, its log context and the {@code @MultiInstanceSafe} check; this record keeps the setting typed.</p>
 *
 * @param expression what {@link CronExpression#parse} accepts (six fields, or a macro such as {@code @daily}), or
 *                   {@code "-"}
 */
public record Cron(String expression) {

    /** The job never runs (the test profile, where tests call it). */
    public static final String DISABLED = Scheduled.CRON_DISABLED;

    public Cron {
        if (!DISABLED.equals(expression)) {
            CronExpression.parse(expression);
        }
    }

    public static Cron of(String expression) {
        return new Cron(expression);
    }
}
