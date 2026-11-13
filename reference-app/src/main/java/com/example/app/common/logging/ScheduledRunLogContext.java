package com.example.app.common.logging;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.slf4j.MDC;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Gives every run of a {@code @Scheduled} job the log context a request gets: {@code requestId} is a
 * fresh id for this run, so one run's lines can be found together, and {@code endpoint} names the job
 * ({@code JOB RefreshTokenPurgeJob.purgeExpired}). Micrometer Tracing starts a trace per run on the
 * same observation, so {@code traceId} is set too.
 *
 * <p>Spring observes each run ({@code tasks.scheduled.execution}) on the thread that runs it, from
 * just before the method to just after it, so this is the one hook that knows which job is running.
 * Its {@code .active} long-task timer is the signal for a job that runs too long or hangs.</p>
 */
@Component
class ScheduledRunLogContext implements ObservationHandler<ScheduledTaskObservationContext> {

    private static final String JOB = "JOB ";

    @Override
    public void onStart(ScheduledTaskObservationContext context) {
        MDC.put(LogContext.REQUEST_ID, UUID.randomUUID().toString());
        MDC.put(LogContext.ENDPOINT, JOB + context.getTargetClass().getSimpleName() + "." + context.getMethod().getName());
    }

    @Override
    public void onStop(ScheduledTaskObservationContext context) {
        LogContext.clearRequest();
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ScheduledTaskObservationContext;
    }
}
