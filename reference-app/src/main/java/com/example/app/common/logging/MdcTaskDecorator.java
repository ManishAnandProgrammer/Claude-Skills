package com.example.app.common.logging;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;
import java.util.Objects;

/**
 * Carries the submitting thread's log context (request id, user, trace) into an async task, and
 * restores the worker's own afterwards, so a task's lines can be found by the request that
 * started it.
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable task) {
        Map<String, String> submitted = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            setContext(submitted);
            try {
                task.run();
            } finally {
                setContext(previous);
            }
        };
    }

    private static void setContext(Map<String, String> context) {
        if (Objects.isNull(context)) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
