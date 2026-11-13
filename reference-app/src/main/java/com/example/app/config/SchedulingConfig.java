package com.example.app.config;

import com.example.app.common.logging.MdcTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Scheduled jobs run on their own named platform threads. Under virtual threads Boot's default
 * scheduler ignores {@code spring.task.scheduling.pool.size}; this one is explicit, so one hung job
 * can't stall the others, and shutdown waits a bounded time for running jobs.
 *
 * <p>The {@link TaskDecorator} bean is applied by Boot to its async executor, so {@code @Async}
 * work keeps the submitting request's log context. A scheduler is itself an {@code Executor}, which
 * would make Boot skip its async executor: {@code spring.task.execution.mode: force} keeps it.</p>
 */
@Configuration
@EnableScheduling
class SchedulingConfig {

    private static final int SCHEDULER_THREADS = 2;
    private static final int SHUTDOWN_WAIT_SECONDS = 10;

    @Bean
    ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(SCHEDULER_THREADS);
        scheduler.setThreadNamePrefix("scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(SHUTDOWN_WAIT_SECONDS);
        return scheduler;
    }

    @Bean
    TaskDecorator mdcTaskDecorator() {
        return new MdcTaskDecorator();
    }
}
