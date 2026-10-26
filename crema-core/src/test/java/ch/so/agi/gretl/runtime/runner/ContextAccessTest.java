package ch.so.agi.gretl.runtime.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.so.agi.gretl.runtime.api.ContextAccessException;
import ch.so.agi.gretl.runtime.api.Diagnostic;
import ch.so.agi.gretl.runtime.api.DiagnosticSeverity;
import ch.so.agi.gretl.runtime.api.Progress;
import ch.so.agi.gretl.runtime.api.SecretValue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextAccessTest {

    @TempDir
    Path tempDir;

    @Test
    void contextProvidesParametersConfigPathsSecretsReportAndProgress() {
        Map<String, Map<String, Object>> taskConfig = new LinkedHashMap<>();
        taskConfig.put("alias", Map.of("key", "value"));
        ResolvedParameters parameters = new ResolvedParameters(
                Map.of("p", "value"),
                Map.of("p", ResolvedParameters.ParameterSource.COMMAND_LINE),
                List.of(), Set.of("p"));
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        RecordingEventSink sink = new RecordingEventSink();

        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("context").body(context -> {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("param", context.parameter("p"));
                values.put("typed", context.parameter("p", String.class));
                values.put("config", context.config("alias"));
                values.put("file", context.file("job.txt"));
                values.put("path", context.path("start.txt"));
                Path work = context.workPath("sub/file.txt");
                values.put("work", work);
                values.put("workParentExists", Files.isDirectory(work.getParent()));

                SecretValue secret = context.secret("token");
                values.put("secretToString", secret.toString());
                values.put("secret", secret.reveal());
                values.put("sensitive", context.sensitive("dynamic-token"));
                values.put("secretAfter", secret.reveal());

                context.report(new Diagnostic(DiagnosticSeverity.WARN, "context.test",
                        "Meldung", Map.of("key", "value"), List.of()));
                context.progress(new Progress("bytes", 1, OptionalLong.of(2), "bytes"));
                context.log().info("context message");

                values.put("unknownParameter", code(() -> context.parameter("missing")));
                values.put("typeMismatch", code(() -> context.parameter("p", Integer.class)));
                values.put("unknownConfig", code(() -> context.config("missing")));
                values.put("traversal", code(() -> context.workPath("../escape.txt")));
                values.put("absoluteWork", code(() -> context.workPath("/tmp/absolute.txt")));
                captured.set(values);
                return "ok";
            }).build());
            builder.defaults("context");
        });

        RunInput input = RunnerTestSupport.input(tempDir, new FakeMonotonicClock(), sink)
                .parameters(parameters)
                .taskConfig(taskConfig)
                .secretResolver(alias -> "token-value")
                .build();
        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition), input);

        assertEquals(RunStatus.SUCCEEDED, outcome.status());
        Map<String, Object> values = captured.get();
        assertEquals("value", values.get("param"));
        assertEquals("value", values.get("typed"));
        assertEquals(Map.of("key", "value"), values.get("config"));
        assertThrows(UnsupportedOperationException.class,
                () -> asMap(values.get("config")).put("other", "x"));
        assertEquals(tempDir.resolve("job.txt"), values.get("file"));
        assertEquals(tempDir.resolve("start.txt"), values.get("path"));
        assertTrue(values.get("work").toString().endsWith("sub/file.txt"));
        assertEquals(true, values.get("workParentExists"));
        assertEquals("[REDACTED]", values.get("secretToString"));
        assertEquals("token-value", values.get("secret"));
        assertEquals("token-value", values.get("secretAfter"));
        assertEquals("dynamic-token", values.get("sensitive"));
        assertEquals("UNKNOWN_PARAMETER", values.get("unknownParameter"));
        assertEquals("PARAMETER_TYPE_MISMATCH", values.get("typeMismatch"));
        assertEquals("UNKNOWN_CONFIG_ALIAS", values.get("unknownConfig"));
        assertEquals("WORK_PATH_ESCAPE", values.get("traversal"));
        assertEquals("WORK_PATH_ESCAPE", values.get("absoluteWork"));

        assertEquals(1, outcome.diagnostics().size());
        assertTrue(sink.names().contains("gretl.operation.progress"));
        RunEvent message = sink.first("gretl.task.message");
        assertEquals(Optional.of("context"), message.taskId());
        assertEquals("context message", message.message());
    }

    @Test
    void runInputCopiesTaskConfigDeeplyAndRejectsMutation() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("key", "old");
        Map<String, Map<String, Object>> config = new LinkedHashMap<>();
        config.put("alias", nested);

        RunInput input = RunInput.builder().taskConfig(config).build();
        nested.put("key", "new");

        assertEquals("old", input.taskConfig().get("alias").get("key"));
        assertThrows(UnsupportedOperationException.class,
                () -> input.taskConfig().get("alias").put("other", "value"));
    }

    @Test
    void secretRevealAfterRunIsRejected() {
        AtomicReference<SecretValue> secret = new AtomicReference<>();
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("secret").body(context -> {
                secret.set(context.secret("token"));
                return "ok";
            }).build());
            builder.defaults("secret");
        });

        RunInput input = RunnerTestSupport.input(tempDir, new FakeMonotonicClock(),
                        new RecordingEventSink())
                .secretResolver(alias -> "late-secret")
                .build();
        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition), input);
        assertEquals(RunStatus.SUCCEEDED, outcome.status());
        ContextAccessException exception = assertThrows(ContextAccessException.class,
                () -> secret.get().reveal());
        assertEquals("CONTEXT_CLOSED", exception.code());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static String code(org.junit.jupiter.api.function.Executable executable) {
        try {
            executable.execute();
            return "NO_ERROR";
        } catch (ContextAccessException exception) {
            return exception.code();
        } catch (Throwable throwable) {
            return throwable.getClass().getName();
        }
    }
}
