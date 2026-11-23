package com.example.app.config;

import com.example.app.common.logging.MdcTaskDecorator;
import com.example.app.common.tenancy.TenantTaskDecorator;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
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
 * Under virtual threads Boot's default scheduler ignores {@code spring.task.scheduling.pool.size}: this one has a
 * pool of its own, at least two threads, so one hung job can't stall the others.
 *
 * <p>Boot applies the {@link TaskDecorator} bean to its async executor. A scheduler is itself an {@code Executor},
 * which would make Boot skip that executor: {@code spring.task.execution.mode: force} keeps it.</p>
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

    @Validated
    @ConfigurationProperties(prefix = "app.jobs.scheduler")
    record Settings(@Min(2) int threads, @NotNull @DurationMin(days = 0) Duration shutdownWait) {
    }
}
