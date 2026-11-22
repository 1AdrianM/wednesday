package com.github.wednesday.common;

import com.github.wednesday.testing.RecordingListener;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;
import org.jboss.logmanager.formatters.PatternFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class SessionLoggingTest {

    private static final Logger LOG = Logger.getLogger(SessionLoggingTest.class);

    @ConfigProperty(name = "quarkus.log.console.format")
    String consoleFormat;

    private final List<String> lines = new CopyOnWriteArrayList<>();
    private Handler capture;
    private java.util.logging.Logger root;

    @BeforeEach
    void attachCapturingHandler() {
        PatternFormatter formatter = new PatternFormatter(consoleFormat);
        capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLoggerName().startsWith("com.github.wednesday")) {
                    lines.add(formatter.format(record));
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        root = java.util.logging.Logger.getLogger("");
        root.addHandler(capture);
    }

    @AfterEach
    void detach() {
        root.removeHandler(capture);
    }

    @Test
    void sessionIdAppearsOnLinesFromCallerAndStreamThreads() {
        SessionId id = SessionId.random();
        var listener = new RecordingListener<String>();

        try (var scope = SessionLogContext.open(id)) {
            LOG.info("turn started");
            VirtualThreadStream.<String>start("t", listener, emit -> LOG.info("inside stream"));
        }
        assertTrue(listener.awaitTermination(Duration.ofSeconds(2)));

        assertEquals(2, lines.size(), () -> "captured: " + lines);
        for (String line : lines) {
            assertTrue(line.contains("[sid=" + id.value() + "]"), line);
        }
    }

    @Test
    void scopeRestoresPreviousContext() {
        SessionId outer = SessionId.random();
        SessionId inner = SessionId.random();
        try (var a = SessionLogContext.open(outer)) {
            try (var b = SessionLogContext.open(inner)) {
                assertEquals(inner.value(), MDC.get(SessionLogContext.MDC_KEY));
            }
            assertEquals(outer.value(), MDC.get(SessionLogContext.MDC_KEY));
        }
        assertNull(MDC.get(SessionLogContext.MDC_KEY));
    }
}
