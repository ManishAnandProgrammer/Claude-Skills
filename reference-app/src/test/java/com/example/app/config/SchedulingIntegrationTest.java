package com.example.app.config;

import com.example.app.support.IntegrationTest;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The job scheduler as configured: isolated runs, each with its own log context (REL-08, OPS-05). */
class SchedulingIntegrationTest extends IntegrationTest {

    @Autowired
    private ThreadPoolTaskScheduler taskScheduler;

    @Autowired
    private ObservationRegistry observationRegistry;

    @Test
    @DisplayName("A hung job doesn't stop another job from running")
    void aHungJobDoesNotStopTheOthers() throws Exception {
        CountDownLatch hung = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch otherRan = new CountDownLatch(1);
        ScheduledFuture<?> hangingJob = taskScheduler.schedule(() -> hangUntil(hung, release), Instant.now());
        try {
            assertThat(hung.await(5, TimeUnit.SECONDS)).isTrue();
            taskScheduler.schedule(otherRan::countDown, Instant.now());

            assertThat(otherRan.await(5, TimeUnit.SECONDS)).as("the second job ran while the first hung").isTrue();
        } finally {
            release.countDown();
            hangingJob.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Each scheduled run gets a fresh run id and names its job in the log context, and leaves none behind")
    void eachRunHasItsOwnLogContext() throws Exception {
        RecordingJob job = new RecordingJob();
        ScheduledMethodRunnable run = new ScheduledMethodRunnable(job, RecordingJob.class.getMethod("record"), null,
                () -> observationRegistry);

        run.run();
        Map<String, String> first = job.seen.get();
        run.run();
        Map<String, String> second = job.seen.get();

        assertThat(first).containsEntry("endpoint", "JOB RecordingJob.record").containsKey("requestId");
        assertThat(second.get("requestId")).isNotEqualTo(first.get("requestId"));
        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("endpoint")).isNull();
    }

    private static void hangUntil(CountDownLatch hung, CountDownLatch release) {
        hung.countDown();
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public static class RecordingJob {

        private final AtomicReference<Map<String, String>> seen = new AtomicReference<>();

        public void record() {
            seen.set(MDC.getCopyOfContextMap());
        }
    }
}
