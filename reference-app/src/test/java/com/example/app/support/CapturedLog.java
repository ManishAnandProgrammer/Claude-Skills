package com.example.app.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Every log line written while it is open, with its exception chain: for a test that a value never reaches
 * the log (SEC-04). Attached to the root logger itself, so each event arrives on the logging thread, before
 * any async appender. Open it in a {@code @BeforeEach}, close it in an {@code @AfterEach}.
 */
public final class CapturedLog {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public void open() {
        appender.start();
        rootLogger().addAppender(appender);
    }

    public void close() {
        rootLogger().detachAppender(appender);
        appender.stop();
    }

    /** Each captured line and every message of its logged exception, its causes included. */
    public String text() {
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            lines.add(event.getFormattedMessage());
            for (IThrowableProxy cause = event.getThrowableProxy(); Objects.nonNull(cause); cause = cause.getCause()) {
                lines.add(cause.getMessage());
            }
        }
        return String.join("\n", lines);
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }
}
