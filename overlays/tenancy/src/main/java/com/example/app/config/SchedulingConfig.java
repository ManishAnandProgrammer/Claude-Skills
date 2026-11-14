package com.example.app.config;

import com.example.app.common.logging.MdcTaskDecorator;
import com.example.app.common.tenancy.TenantTaskDecorator;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.CompositeTaskDecorator;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Scheduled jobs run on their own named platform threads. Under virtual threads Boot's default
 * scheduler ignores {@code spring.task.scheduling.pool.size}; this one is explicit, so one hung job
 * can't stall the others, and shutdown waits a bounded time for running jobs. Each run gets its own
 * log context ({@code ScheduledRunLogContext}).
 *
 * <p>The {@link TaskDecorator} bean is applied by Boot to its async executor, so {@code @Async}
 * work keeps the submitting request's log context (and, with tenancy, its tenant). A scheduler is itself an {@code Executor}, which
 * would make Boot skip its async executor: {@code spring.task.execution.mode: force} keeps it.</p>
 */
@Configuration
@EnableScheduling
class SchedulingConfig {

    @Bean
    ThreadPoolTaskScheduler taskScheduler(Settings settings) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(settings.threads());
        scheduler.setThreadNamePrefix("scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationMillis(settings.shutdownWait().toMillis());
        return scheduler;
    }

    @Bean
    TaskDecorator taskDecorator() {
        List<TaskDecorator> decorators = new ArrayList<>();
        decorators.add(new MdcTaskDecorator());
        decorators.add(new TenantTaskDecorator());
        return new CompositeTaskDecorator(decorators);
    }

    /**
     * {@code app.jobs.scheduler.*}.
     *
     * @param threads      at least two: with one, a hung job would stop every other job
     * @param shutdownWait how long shutdown waits for running jobs
     */
    @Validated
    @ConfigurationProperties(prefix = "app.jobs.scheduler")
    record Settings(@Min(2) int threads, @NotNull Duration shutdownWait) {
    }
}
