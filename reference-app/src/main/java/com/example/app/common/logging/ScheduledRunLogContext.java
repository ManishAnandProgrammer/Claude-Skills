package com.example.app.common.logging;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.slf4j.MDC;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Spring observes each run of a {@code @Scheduled} job ({@code tasks.scheduled.execution}) on the thread that
 * runs it, from just before the method to just after it, so this is the one hook that knows which job is
 * running.
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
