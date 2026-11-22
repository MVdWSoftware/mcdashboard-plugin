/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.diagnostics;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mcdashboard.json.Json;
import org.mcdashboard.platform.Log;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorReporterTest {
    private final List<String> sent = new ArrayList<>();
    private final List<String> endpoints = new ArrayList<>();
    private final List<String> auth = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(1_000_000);

    private ErrorReporter reporter() {
        return new ErrorReporter("https://tracker.example/api/1/store/", "key", "1.2.3", now::get,
                (endpoint, body, header) -> {
                    endpoints.add(endpoint);
                    auth.add(header);
                    sent.add(body);
                });
    }

    private final Log log = new Log() {
        @Override
        public void info(String message) {
        }

        @Override
        public void warning(String message) {
        }

        @Override
        public void severe(String message) {
        }
    };

    @AfterEach
    void restoreHandler() {
        Thread.setDefaultUncaughtExceptionHandler(null);
    }

    @Test
    void readsAValidDsn() {
        ErrorReporter reader = ErrorReporter.forDsn("https://abc@monitor.example.com/12", "1", () -> 0, (e, b, a) -> {
        });
        assertNotNull(reader);
        assertEquals("https://monitor.example.com/api/12/store/", reader.endpoint());
        assertNull(ErrorReporter.forDsn("https://monitor.example.com/12", "1", () -> 0, (e, b, a) -> {
        }));
        assertNull(ErrorReporter.forDsn("https://abc@monitor.example.com/", "1", () -> 0, (e, b, a) -> {
        }));
        assertNull(ErrorReporter.forDsn("not a dsn", "1", () -> 0, (e, b, a) -> {
        }));
    }

    @Test
    void isOffUntilTheOwnerOptsIn() {
        assertNull(ErrorReporter.start(false, "1", log));
    }

    @Test
    void startsAndStopsTheOptInReporter() {
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        ErrorReporter reporter = ErrorReporter.start(true, "1", log);
        assertNotNull(reporter);
        assertTrue(Thread.getDefaultUncaughtExceptionHandler() != before);
        reporter.close();
        assertEquals(before, Thread.getDefaultUncaughtExceptionHandler());
    }

    @Test
    void sendsTheExceptionWithMaskedValuesAndTheStack() {
        ErrorReporter reporter = reporter();
        Error error = new Error("failed for maxim@example.com at https://mc.example.com/x from 10.0.0.5");
        error.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("org.mcdashboard.Boom", "explode", "Boom.java", 12),
                new StackTraceElement("java.lang.Thread", "run", null, -1),
        });
        reporter.report(error, "startup");

        assertEquals(List.of("https://tracker.example/api/1/store/"), endpoints);
        assertEquals(List.of("Sentry sentry_version=7, sentry_key=key, sentry_client=mcdashboard-plugin/1.2.3"), auth);
        assertEquals(1, sent.size());
        Map<?, ?> event = (Map<?, ?>) Json.parse(sent.get(0));
        assertEquals("java", event.get("platform"));
        assertEquals("error", event.get("level"));
        assertEquals("1.2.3", event.get("release"));
        assertEquals(Map.of("context", "startup"), event.get("extra"));
        Map<?, ?> exception = (Map<?, ?>) ((List<?>) ((Map<?, ?>) event.get("exception")).get("values")).get(0);
        assertEquals("java.lang.Error", exception.get("type"));
        assertEquals("failed for [email] at [url] from [ip]", exception.get("value"));
        Map<?, ?> frames = (Map<?, ?>) exception.get("stacktrace");
        List<?> list = (List<?>) frames.get("frames");
        assertEquals(2, list.size());
        assertEquals("run", ((Map<?, ?>) list.get(0)).get("function"));
        assertEquals("explode", ((Map<?, ?>) list.get(1)).get("function"));
        assertEquals(true, ((Map<?, ?>) list.get(1)).get("in_app"));
        assertEquals(false, ((Map<?, ?>) list.get(0)).get("in_app"));
    }

    @Test
    void acceptsAMessageLessErrorWithoutAStack() {
        ErrorReporter reporter = reporter();
        Error error = new Error((String) null);
        error.setStackTrace(new StackTraceElement[0]);
        reporter.report(error, " ");
        Map<?, ?> event = (Map<?, ?>) Json.parse(sent.get(0));
        Map<?, ?> exception = (Map<?, ?>) ((List<?>) ((Map<?, ?>) event.get("exception")).get("values")).get(0);
        assertEquals("Error", exception.get("value"));
        assertFalse(exception.containsKey("stacktrace"));
        assertFalse(event.containsKey("extra"));
    }

    @Test
    void ignoresNullAndVeryFrequentDuplicates() {
        ErrorReporter reporter = reporter();
        reporter.report(null, "startup");
        Error error = new Error("same");
        reporter.report(error, "uncaught");
        reporter.report(error, "uncaught");
        assertEquals(1, sent.size());
        now.addAndGet(60_000);
        reporter.report(error, "uncaught");
        assertEquals(2, sent.size());
    }

    @Test
    void capsReportsAndStartsANewHour() {
        ErrorReporter reporter = reporter();
        for (int i = 0; i < 25; i++) {
            Error error = new Error("e" + i);
            error.setStackTrace(new StackTraceElement[]{new StackTraceElement("C", "m" + i, "C.java", i)});
            reporter.report(error, "uncaught");
        }
        assertEquals(20, sent.size());
        now.addAndGet(3_600_000);
        Error error = new Error("later");
        error.setStackTrace(new StackTraceElement[]{new StackTraceElement("C", "later", "C.java", 1)});
        reporter.report(error, "uncaught");
        assertEquals(21, sent.size());
    }

    @Test
    void aBrokenSenderNeverBreaksTheServer() {
        ErrorReporter reporter = new ErrorReporter("https://tracker.example/api/1/store/", "key", "1", now::get,
                (endpoint, body, header) -> {
                    throw new IllegalStateException("down");
                });
        reporter.report(new Error("x"), "uncaught");
    }

    private static Error pluginError(String message) {
        Error error = new Error(message);
        error.setStackTrace(new StackTraceElement[]{new StackTraceElement("org.mcdashboard.Boom", "explode", "Boom.java", 1)});
        return error;
    }

    @Test
    void chainsTheJvmHandlerAndRestoresIt() {
        List<String> previousCalls = new ArrayList<>();
        Thread.UncaughtExceptionHandler previous = (thread, error) -> previousCalls.add("previous");
        Thread.setDefaultUncaughtExceptionHandler(previous);
        ErrorReporter reporter = reporter();
        reporter.install();
        reporter.install();
        Thread.UncaughtExceptionHandler installed = Thread.getDefaultUncaughtExceptionHandler();
        installed.uncaughtException(new Thread(), pluginError("crash"));
        assertEquals(1, sent.size());
        assertEquals(1, previousCalls.size());
        assertTrue(sent.get(0).startsWith("{"));
        reporter.close();
        assertEquals(previous, Thread.getDefaultUncaughtExceptionHandler());
        reporter.close();
    }

    @Test
    void doesNotReportAnotherPluginsCrash() {
        List<String> previousCalls = new ArrayList<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> previousCalls.add("previous"));
        Error other = new Error("other plugin");
        other.setStackTrace(new StackTraceElement[]{new StackTraceElement("com.example.Other", "run", "Other.java", 1)});
        ErrorReporter reporter = reporter();
        reporter.install();
        Thread.getDefaultUncaughtExceptionHandler().uncaughtException(new Thread(), other);
        assertEquals(0, sent.size());
        assertEquals(1, previousCalls.size());
        assertFalse(ErrorReporter.isOurs(other));
        assertTrue(ErrorReporter.isOurs(pluginError("ours")));
        reporter.close();
    }

    @Test
    void postsToTheTrackerInTheBackground() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<String> header = new AtomicReference<>();
        server.createContext("/api/7/store/", exchange -> {
            header.set(exchange.getRequestHeaders().getFirst("x-sentry-auth"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            received.countDown();
        });
        server.start();
        try {
            ErrorReporter.httpSender().send("http://127.0.0.1:" + server.getAddress().getPort() + "/api/7/store/", "{}", "Sentry test");
            assertTrue(received.await(5, TimeUnit.SECONDS), "the tracker received the report");
            assertEquals("Sentry test", header.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void printsACrashWhenTheJvmHadNoHandler() {
        Thread.setDefaultUncaughtExceptionHandler(null);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream original = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            ErrorReporter reporter = reporter();
            reporter.install();
            Thread.getDefaultUncaughtExceptionHandler().uncaughtException(new Thread(), pluginError("crash"));
        } finally {
            System.setErr(original);
        }
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("crash"));
    }
}
