package ch.so.agi.gretl.runtime.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deterministic setup failures: a regular file occupies a required directory. */
class SetupFailureTest {
    @TempDir
    Path tempDir;

    @Test
    void failedRunWorkspaceIsAStartErrorWithoutCleanup() throws Exception {
        Path workRoot = Files.writeString(tempDir.resolve("blocked-work"), "untouched");
        List<String> cleaned = new ArrayList<>();
        AtomicInteger taskCalls = new AtomicInteger();
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("never").body(context -> {
                taskCalls.incrementAndGet();
                return null;
            }).build());
            builder.cleanup("first", context -> cleaned.add("first"));
            builder.cleanup("second", context -> {
                context.log().info("cleanup after workspace failure");
                cleaned.add("second");
            });
            builder.defaults("never");
        });
        RecordingEventSink sink = new RecordingEventSink();
        RunStartException failure = assertThrows(RunStartException.class, () -> new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(), sink).workRoot(workRoot).build()));
        assertTrue(failure.getCause().getMessage().contains("blocked-work"));
        assertEquals(List.of(), cleaned);
        assertEquals(0, taskCalls.get());
        assertEquals("untouched", Files.readString(workRoot));
        assertEquals(0, sink.count("gretl.run.started"));
        assertEquals(0, sink.count("gretl.run.finished"));
    }

    @Test
    void invocationSetupFailureHasOrdinaryCatchSemanticsAndItsOwnHistory() {
        for (boolean handled : List.of(false, true)) {
            var operation = ScopedControlTest.operation((request, c) -> { throw new AssertionError("handler never"); });
            var definition = RunnerTestSupport.definition(tempDir, b -> {
                b.register(RunnerTestSupport.task("caller").body(c -> {
                    Files.writeString(c.workPath("anchor").getParent().resolve("i000001"), "blocked invocation");
                    if (handled) {
                        assertThrows(java.io.IOException.class, () -> c.call(operation, "request")); return "handled";
                    }
                    return c.call(operation, "request");
                }).build()); b.defaults("caller");
            });
            var outcome = new SequentialJobRunner().run(definition, RunnerTestSupport.plan(definition),
                    RunnerTestSupport.input(tempDir, new FakeMonotonicClock(), new RecordingEventSink()).build());
            assertEquals(handled ? RunStatus.SUCCEEDED : RunStatus.FAILED, outcome.status());
            assertEquals(InvocationStatus.FAILED, outcome.invocations().getFirst().status());
            assertTrue(outcome.invocations().getFirst().failure().orElseThrow().message().contains("i000001"));
        }
    }

    @Test
    void lostWorkspaceAfterRunStartStillAllowsExternalCleanup() throws Exception {
        UUID id = UUID.randomUUID();
        Path runRoot = tempDir.resolve("work").resolve(id.toString());
        AtomicInteger cleanups = new AtomicInteger();
        var definition = RunnerTestSupport.definition(tempDir, b -> {
            b.register(RunnerTestSupport.task("blocked").body(c -> { throw new AssertionError("never"); }).build());
            b.cleanup("external", c -> { c.log().info("cleanup"); cleanups.incrementAndGet(); });
            b.cleanup("needs-files", c -> c.workPath("not-possible"));
            b.defaults("blocked");
        });
        RecordingEventSink sink = new RecordingEventSink();
        RunEventSink sabotage = event -> {
            sink.emit(event);
            if (event.eventName().equals("gretl.run.started")) {
                try { Files.delete(runRoot); Files.writeString(runRoot, "run no longer a directory"); }
                catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            }
        };
        var outcome = new SequentialJobRunner().run(definition, RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(), sabotage).runId(id).build());
        assertEquals(RunStatus.FAILED, outcome.status());
        assertEquals(1, cleanups.get());
        assertEquals("task-setup", outcome.tasks().getFirst().failurePhase());
        assertEquals(1, sink.count("gretl.cleanup.failed"));
        assertEquals(1, sink.count("gretl.cleanup.finished"));
        assertTrue(outcome.secondaryFailures().size() >= 2, "Cleanup and retention errors retained");
    }

    @Test
    void taskWorkspaceFailureIsTerminalAndPreservesSetupCauseBeforeCleanupErrors() {
        UUID runId = UUID.randomUUID();
        Path runRoot = tempDir.resolve("work").resolve(runId.toString());
        List<String> cleaned = new ArrayList<>();
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("prepare").body(context -> {
                Files.writeString(runRoot.resolve("task-000001"), "blocking task directory");
                return null;
            }).build());
            builder.register(RunnerTestSupport.task("blocked").body(context -> {
                throw new AssertionError("task body must not start");
            }).build());
            builder.register(RunnerTestSupport.task("later").body(context -> {
                throw new AssertionError("later task must not start");
            }).build());
            builder.cleanup("survivor", context -> cleaned.add("survivor"));
            builder.cleanup("failing", context -> {
                throw new IllegalStateException("secondary cleanup failure");
            });
            builder.defaults("prepare", "blocked", "later");
        });
        RecordingEventSink sink = new RecordingEventSink();
        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(), sink)
                        .runId(runId).build());

        assertEquals(RunStatus.FAILED, outcome.status());
        assertEquals(TaskStatus.SUCCEEDED, outcome.task("prepare").orElseThrow().status());
        assertEquals(TaskStatus.FAILED, outcome.task("blocked").orElseThrow().status());
        assertEquals(TaskStatus.NOT_RUN, outcome.task("later").orElseThrow().status());
        assertTrue(outcome.primaryFailure().orElseThrow().message().contains("task-000001"));
        assertEquals("secondary cleanup failure", outcome.secondaryFailures().get(0).message());
        assertEquals(List.of("survivor"), cleaned);
        assertEquals(1, sink.count("gretl.run.finished"));
    }

    @Test
    void cleanupWithoutFilesDoesNotOpenItsWorkspaceAndFileCleanupFailureIsSecondary() {
        UUID runId = UUID.randomUUID();
        Path runRoot = tempDir.resolve("work").resolve(runId.toString());
        List<String> cleaned = new ArrayList<>();
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("failing").body(context -> {
                Files.writeString(runRoot.resolve("cleanup-000"), "non-file cleanup blocker");
                Files.writeString(runRoot.resolve("cleanup-001"), "file cleanup blocker");
                throw new IllegalArgumentException("original task failure");
            }).build());
            builder.cleanup("no-files", context -> cleaned.add("no-files"));
            builder.cleanup("needs-files", context -> Files.writeString(
                    context.workPath("result.txt"), "never written"));
            builder.defaults("failing");
        });
        RecordingEventSink sink = new RecordingEventSink();
        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(), sink)
                        .runId(runId).build());

        assertEquals(RunStatus.FAILED, outcome.status());
        assertEquals("original task failure", outcome.primaryFailure().orElseThrow().message());
        assertEquals(List.of("no-files"), cleaned);
        assertEquals(1, outcome.secondaryFailures().size());
        assertTrue(outcome.secondaryFailures().get(0).message().contains("cleanup-001"));
        assertEquals(1, sink.count("gretl.cleanup.finished"));
        assertEquals(1, sink.count("gretl.cleanup.failed"));
        assertEquals(1, sink.count("gretl.run.finished"));
    }
}
