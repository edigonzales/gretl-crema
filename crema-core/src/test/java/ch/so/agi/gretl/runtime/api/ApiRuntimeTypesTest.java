package ch.so.agi.gretl.runtime.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class ApiRuntimeTypesTest {

    @Test
    void secretIsLazyMaskedAndRevealsExplicitly() {
        AtomicInteger reads = new AtomicInteger();
        SecretValue secret = SecretValue.deferred(() -> {
            reads.incrementAndGet();
            return "sentinel-secret";
        });
        assertEquals(0, reads.get());
        assertEquals("[REDACTED]", secret.toString());
        assertEquals(0, reads.get());
        assertEquals("sentinel-secret", secret.reveal());
        assertEquals(1, reads.get());
        assertThrows(NullPointerException.class, () -> SecretValue.deferred(() -> null).reveal());
    }

    @Test
    void diagnosticCopiesDetailsAndNormalizesArtifacts() {
        Map<String, String> details = new HashMap<>();
        details.put("key", "old");
        Path relative = Path.of("reports", "..", "report.json");
        Diagnostic diagnostic = new Diagnostic(
                DiagnosticSeverity.WARN, "fixture.warning", "Meldung", details,
                List.of(new DiagnosticArtifact("report", relative, "application/json", true)));

        details.put("key", "new");
        assertEquals("old", diagnostic.details().get("key"));
        assertThrows(UnsupportedOperationException.class,
                () -> diagnostic.details().put("other", "value"));
        assertTrue(diagnostic.artifacts().get(0).path().isAbsolute());
        assertEquals(Path.of("report.json").toAbsolutePath().normalize(),
                diagnostic.artifacts().get(0).path());
        assertTrue(diagnostic.artifacts().get(0).restricted());
        assertThrows(UnsupportedOperationException.class, () -> diagnostic.artifacts().clear());
    }

    @Test
    void progressRejectsInvalidCountersAndKeepsUnknownTotalUnknown() {
        Progress known = new Progress("bytes", 5, OptionalLong.of(10), "bytes");
        assertEquals(5, known.completed());
        assertTrue(known.total().isPresent());

        Progress unknown = new Progress("objects", 5, OptionalLong.empty(), "objects");
        assertTrue(unknown.total().isEmpty());

        assertThrows(IllegalArgumentException.class,
                () -> new Progress("download", 5, OptionalLong.of(4), "bytes"));
        assertThrows(IllegalArgumentException.class,
                () -> new Progress("download", -1, OptionalLong.empty(), "bytes"));
        assertThrows(IllegalArgumentException.class,
                () -> new Progress(" ", 1, OptionalLong.empty(), "bytes"));
    }

    @Test
    void executionIdentityRequiresTaskForInvocation() {
        UUID runId = UUID.randomUUID();
        ExecutionIdentity task = new ExecutionIdentity(
                runId, "job", Optional.of("task"), Optional.empty());
        assertEquals(Optional.of("task"), task.taskId());
        assertTrue(task.invocationId().isEmpty());

        ExecutionIdentity invocation = new ExecutionIdentity(
                runId, "job", Optional.of("task"), Optional.of("i000001"));
        assertEquals("i000001", invocation.invocationId().orElseThrow());

        assertThrows(IllegalArgumentException.class, () -> new ExecutionIdentity(
                runId, "job", Optional.empty(), Optional.of("i000001")));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionIdentity(
                runId, " ", Optional.empty(), Optional.empty()));
    }

    @Test
    void contextAccessExceptionCarriesCode() {
        ContextAccessException exception = new ContextAccessException(
                "UNKNOWN_PARAMETER", "Parameter fehlt");
        assertEquals("UNKNOWN_PARAMETER", exception.code());
        assertEquals("Parameter fehlt", exception.getMessage());
        assertTrue(exception instanceof IllegalStateException);
        assertThrows(IllegalArgumentException.class,
                () -> new ContextAccessException(" ", "message"));
    }

    @Test
    void taskLoggerConvenienceMethodsUseTheReservedMessageEvent() {
        RecordingLogger logger = new RecordingLogger();
        logger.info("info");
        logger.debug("debug");
        logger.warn("warn");
        logger.error("error", new IllegalStateException("cause"));

        assertEquals(List.of(LogLevel.INFO, LogLevel.DEBUG, LogLevel.WARN, LogLevel.ERROR),
                logger.levels);
        assertEquals(List.of("gretl.task.message", "gretl.task.message",
                "gretl.task.message", "gretl.task.message"), logger.events);
        assertEquals(4, logger.fields.size());
        assertTrue(logger.fields.stream().allMatch(Map::isEmpty));
        assertNull(logger.causes.get(0));
        assertNull(logger.causes.get(1));
        assertNull(logger.causes.get(2));
        assertEquals("cause", logger.causes.get(3).getMessage());
    }

    @Test
    void cancellationAndBudgetTypesKeepTheirSmallContracts() {
        TaskCancelledException cancelled = new TaskCancelledException(CancelReason.TIMEOUT);
        assertEquals(CancelReason.TIMEOUT, cancelled.reason());
        assertTrue(cancelled.getMessage().contains("TIMEOUT"));

        TimeBudget unlimited = new TimeBudget() {
            @Override
            public Optional<Duration> remaining() {
                return Optional.empty();
            }

            @Override
            public boolean expired() {
                return false;
            }
        };
        assertTrue(unlimited.remaining().isEmpty());
        assertFalse(unlimited.expired());

        ResourceScope scope = new ResourceScope() {
            private final List<AutoCloseable> resources = new ArrayList<>();

            @Override
            public <T extends AutoCloseable> T manage(T resource) {
                resources.add(resource);
                return resource;
            }
        };
        AutoCloseable resource = () -> {};
        assertSame(resource, scope.manage(resource));
    }

    @Test
    void nullabilityOfOptionalAndFieldValuesIsExplicit() {
        Optional<String> empty = Optional.empty();
        assertTrue(empty.isEmpty());
        assertThrows(NullPointerException.class, () -> Optional.of(null));
    }

    private static final class RecordingLogger implements TaskLogger {
        final List<LogLevel> levels = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<Map<String, String>> fields = new ArrayList<>();
        final List<Throwable> causes = new ArrayList<>();

        @Override
        public boolean isEnabled(LogLevel level) {
            return true;
        }

        @Override
        public void log(LogLevel level, String eventName, String message,
                        Map<String, String> fields, Throwable cause) {
            levels.add(level);
            events.add(eventName);
            this.fields.add(fields);
            causes.add(cause);
        }
    }
}
