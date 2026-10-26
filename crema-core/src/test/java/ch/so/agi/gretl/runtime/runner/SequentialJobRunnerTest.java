package ch.so.agi.gretl.runtime.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.so.agi.gretl.runtime.api.CancelReason;
import ch.so.agi.gretl.runtime.api.ContextAccessException;
import ch.so.agi.gretl.runtime.api.Diagnostic;
import ch.so.agi.gretl.runtime.api.DiagnosticSeverity;
import ch.so.agi.gretl.runtime.api.LogLevel;
import ch.so.agi.gretl.runtime.api.OperationType;
import ch.so.agi.gretl.runtime.api.TaskContext;
import ch.so.agi.gretl.runtime.api.TaskRef;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SequentialJobRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void executesTheSelectedHullOnceInStableOrderAndExposesResults() {
        List<String> order = new ArrayList<>();
        AtomicReference<TaskRef<Object>> a = new AtomicReference<>();
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            a.set(builder.register(RunnerTestSupport.task("a").body(context -> {
                order.add("a");
                return "A";
            }).build()));
            builder.register(RunnerTestSupport.task("b").dependsOn(a.get()).body(context -> {
                order.add("b");
                assertEquals("A", context.result(a.get()));
                return "B";
            }).build());
            builder.register(RunnerTestSupport.task("unselected").body(context -> {
                order.add("unselected");
                return "U";
            }).build());
            builder.defaults("b");
        });

        FakeMonotonicClock clock = new FakeMonotonicClock();
        RecordingEventSink sink = new RecordingEventSink();
        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition), RunnerTestSupport.input(tempDir, clock, sink).build());

        assertEquals(RunStatus.SUCCEEDED, outcome.status());
        assertEquals(0, outcome.exitCode());
        assertEquals(List.of("a", "b"), order);
        assertEquals(List.of("a", "b"), outcome.tasks().stream().map(TaskOutcome::taskId).toList());
        assertEquals(new ResultSlot.Value("B"), outcome.task("b").orElseThrow().slot());
        assertTrue(outcome.task("unselected").isEmpty());
        assertEquals(1, sink.count("gretl.run.started"));
        assertEquals(1, sink.count("gretl.run.finished"));
        assertEquals(2, sink.count("gretl.task.started"));
        assertEquals(2, sink.count("gretl.task.finished"));
        RunEvent runStarted = sink.first("gretl.run.started");
        assertEquals(Optional.of(outcome.runId()), runStarted.runId());
        assertEquals("m1-test", runStarted.jobName().orElseThrow());
        RunEvent taskStarted = sink.first("gretl.task.started");
        assertEquals(Optional.of("a"), taskStarted.taskId());
        assertTrue(taskStarted.invocationId().isEmpty());
        assertEquals(LogLevel.INFO, sink.last("gretl.run.finished").level());
    }

    @Test
    void anErrorDiagnosticDoesNotChangeTheTaskStatus() {
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("diagnostic").body(context -> {
                context.report(new Diagnostic(DiagnosticSeverity.ERROR, "task.diagnostic",
                        "diagnostic only", Map.of("kind", "info"), List.of()));
                return "ok";
            }).build());
            builder.defaults("diagnostic");
        });

        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(),
                        new RecordingEventSink()).build());

        assertEquals(RunStatus.SUCCEEDED, outcome.status());
        assertEquals(TaskStatus.SUCCEEDED, outcome.task("diagnostic").orElseThrow().status());
        assertEquals(DiagnosticSeverity.ERROR, outcome.diagnostics().get(0).severity());
    }

    @Test
    void successfulInvocationFollowedByFailedPostCheckFailsTheTask() {
        AtomicInteger handlers = new AtomicInteger();
        OperationType<String, String> echo = TestOperations.echo("probe.post-check", handlers);
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("postCheck").body(context -> {
                context.call(echo, "request");
                throw new IllegalStateException("post-check failed");
            }).build());
            builder.defaults("postCheck");
        });

        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(),
                        new RecordingEventSink()).build());

        assertEquals(RunStatus.FAILED, outcome.status());
        assertEquals(TaskStatus.FAILED, outcome.task("postCheck").orElseThrow().status());
        assertEquals(InvocationStatus.SUCCEEDED,
                outcome.task("postCheck").orElseThrow().invocations().get(0).status());
        assertEquals(IllegalStateException.class.getName(),
                outcome.task("postCheck").orElseThrow().failure().orElseThrow().type());
    }

    @Test
    void realFailureIsPrimaryEvenWhenCancellationIsRequestedToo() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RunControl control = new RunControl(clock, Optional.empty());
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("failing").body(context -> {
                control.requestCancel(CancelReason.REQUESTED);
                throw new IllegalStateException("error first");
            }).build());
            builder.defaults("failing");
        });

        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, clock, new RecordingEventSink())
                        .runControl(control).build());

        assertEquals(RunStatus.FAILED, outcome.status());
        assertEquals(10, outcome.exitCode());
        assertEquals(IllegalStateException.class.getName(),
                outcome.primaryFailure().orElseThrow().type());
    }

    @Test
    void producerWorkspaceFileRemainsReadableForTheConsumer() {
        AtomicReference<TaskRef<Object>> producer = new AtomicReference<>();
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            producer.set(builder.register(RunnerTestSupport.task("producer").body(context -> {
                Path file = context.workPath("data.txt");
                Files.writeString(file, "workspace-payload", StandardCharsets.UTF_8);
                return file.toString();
            }).build()));
            builder.register(RunnerTestSupport.task("consumer").dependsOn(producer.get())
                    .body(context -> {
                        String path = (String) context.result(producer.get());
                        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
                    }).build());
            builder.defaults("consumer");
        });

        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(),
                        new RecordingEventSink()).build());

        assertEquals(RunStatus.SUCCEEDED, outcome.status());
        assertEquals(new ResultSlot.Value("workspace-payload"),
                outcome.task("consumer").orElseThrow().slot());
    }

    @Test
    void aFailingEventSinkDoesNotChangeTheRunOutcome() {
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("a").body(context -> "a").build());
            builder.defaults("a");
        });
        RunEventSink brokenSink = event -> {
            throw new IllegalStateException("sink down");
        };

        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(), brokenSink).build());

        assertEquals(RunStatus.SUCCEEDED, outcome.status());
        assertEquals(TaskStatus.SUCCEEDED, outcome.task("a").orElseThrow().status());
    }

    @Test
    void failureIsFailFastAndMarksAllRemainingSelectedTasksNotRun() {
        AtomicReference<TaskRef<Object>> failing = new AtomicReference<>();
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            failing.set(builder.register(RunnerTestSupport.task("failing").body(context -> {
                throw new IllegalStateException("boom", new IOException("cause"));
            }).build()));
            builder.register(RunnerTestSupport.task("dependent").dependsOn(failing.get())
                    .body(context -> "never").build());
            builder.register(RunnerTestSupport.task("independent")
                    .body(context -> "never").build());
            builder.defaults("failing", "dependent", "independent");
        });

        FakeMonotonicClock clock = new FakeMonotonicClock();
        RecordingEventSink sink = new RecordingEventSink();
        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition), RunnerTestSupport.input(tempDir, clock, sink).build());

        assertEquals(RunStatus.FAILED, outcome.status());
        assertEquals(10, outcome.exitCode());
        assertEquals(TaskStatus.FAILED, outcome.task("failing").orElseThrow().status());
        assertEquals(IllegalStateException.class.getName(),
                outcome.task("failing").orElseThrow().failure().orElseThrow().type());
        assertEquals(IOException.class.getName(), outcome.task("failing").orElseThrow()
                .failure().orElseThrow().causes().get(0).type());
        assertEquals(TaskStatus.NOT_RUN, outcome.task("dependent").orElseThrow().status());
        assertEquals("FAIL_FAST", outcome.task("dependent").orElseThrow().reason());
        assertEquals(TaskStatus.NOT_RUN, outcome.task("independent").orElseThrow().status());
        assertEquals(1, sink.count("gretl.task.started"));
        assertEquals(1, sink.count("gretl.task.failed"));
        assertEquals(2, sink.count("gretl.task.not_run"));
    }

    @Test
    void aFailedTaskLeavesItsAlreadyWrittenFileAndIsNotRetried() throws Exception {
        Path marker = tempDir.resolve("marker.txt");
        AtomicInteger calls = new AtomicInteger();
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("writeAndFail").body(context -> {
                calls.incrementAndGet();
                Files.writeString(marker, "written", StandardCharsets.UTF_8);
                throw new IllegalStateException("after write");
            }).build());
            builder.defaults("writeAndFail");
        });

        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(),
                        new RecordingEventSink()).build());

        assertEquals(RunStatus.FAILED, outcome.status());
        assertEquals(1, calls.get());
        assertTrue(Files.exists(marker));
        assertEquals("written", Files.readString(marker));
    }

    @Test
    void predicateFalseSkipsWithoutInvocationAndSuccessorMayReadEmptyOptional() {
        AtomicInteger skippedBodyCalls = new AtomicInteger();
        AtomicReference<TaskRef<Object>> optional = new AtomicReference<>();
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            optional.set(builder.register(RunnerTestSupport.task("optional")
                    .when(context -> false)
                    .body(context -> {
                        skippedBodyCalls.incrementAndGet();
                        return "never";
                    }).build()));
            builder.register(RunnerTestSupport.task("successor").dependsOn(optional.get())
                    .body(context -> {
                        Optional<Object> value = context.resultOptional(optional.get());
                        return value.isEmpty() ? "empty" : "present";
                    }).build());
            builder.defaults("successor");
        });

        FakeMonotonicClock clock = new FakeMonotonicClock();
        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition), RunnerTestSupport.input(tempDir, clock,
                        new RecordingEventSink()).build());

        assertEquals(RunStatus.SUCCEEDED, outcome.status());
        assertEquals(0, skippedBodyCalls.get());
        assertEquals(TaskStatus.SKIPPED, outcome.task("optional").orElseThrow().status());
        assertEquals("PREDICATE_FALSE", outcome.task("optional").orElseThrow().reason());
        assertEquals(new ResultSlot.Value("empty"), outcome.task("successor").orElseThrow().slot());
        assertEquals(0, outcome.task("optional").orElseThrow().invocations().size());
    }

    @Test
    void actionNullIsNoValueForOptionalButNotForDirectAccess() {
        AtomicReference<TaskRef<Object>> producer = new AtomicReference<>();
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            producer.set(builder.register(RunnerTestSupport.task("producer")
                    .body(context -> null).build()));
            builder.register(RunnerTestSupport.task("consumer").dependsOn(producer.get())
                    .body(context -> {
                        if (context.resultOptional(producer.get()).isPresent()) {
                            return "present";
                        }
                        try {
                            context.result(producer.get());
                            return "direct-worked";
                        } catch (ResultAccessException expected) {
                            return "empty";
                        }
                    }).build());
            builder.defaults("consumer");
        });

        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(),
                        new RecordingEventSink()).build());

        assertEquals(RunStatus.SUCCEEDED, outcome.status());
        assertEquals(ResultSlot.NoValue.INSTANCE,
                outcome.task("producer").orElseThrow().slot());
        assertEquals(new ResultSlot.Value("empty"), outcome.task("consumer").orElseThrow().slot());
    }

    @Test
    void accessingAnUndeclaredResultFailsTheConsumingTask() {
        AtomicReference<TaskRef<Object>> producer = new AtomicReference<>();
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            producer.set(builder.register(RunnerTestSupport.task("producer")
                    .body(context -> "value").build()));
            builder.register(RunnerTestSupport.task("consumer").body(context -> {
                context.result(producer.get());
                return "unreachable";
            }).build());
            builder.defaults("producer", "consumer");
        });

        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(),
                        new RecordingEventSink()).build());

        assertEquals(RunStatus.FAILED, outcome.status());
        assertEquals(TaskStatus.FAILED, outcome.task("consumer").orElseThrow().status());
        assertEquals(ResultAccessException.class.getName(),
                outcome.task("consumer").orElseThrow().failure().orElseThrow().type());
    }

    @Test
    void afterOrderingDoesNotGrantResultAccess() {
        AtomicReference<TaskRef<Object>> producer = new AtomicReference<>();
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            producer.set(builder.register(RunnerTestSupport.task("producer")
                    .body(context -> "value").build()));
            builder.register(RunnerTestSupport.task("consumer").after(producer.get())
                    .body(context -> {
                        context.result(producer.get());
                        return "unreachable";
                    }).build());
            builder.defaults("producer", "consumer");
        });

        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(),
                        new RecordingEventSink()).build());

        assertEquals(RunStatus.FAILED, outcome.status());
        assertEquals(TaskStatus.FAILED, outcome.task("consumer").orElseThrow().status());
    }

    @Test
    void finishJobProducesNoDataAndRunsCleanup() {
        List<String> cleanupOrder = new ArrayList<>();
        List<Boolean> cleanupRan = new ArrayList<>();
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("finish").body(context -> {
                context.finishJob("empty source");
                return "unreachable";
            }).build());
            builder.register(RunnerTestSupport.task("later").dependsOn("finish")
                    .body(context -> "never").build());
            builder.cleanup("first", context -> {
                cleanupOrder.add("first");
                cleanupRan.add(true);
            });
            builder.cleanup("second", context -> {
                cleanupOrder.add("second");
                cleanupRan.add(true);
            });
            builder.defaults("later");
        });

        FakeMonotonicClock clock = new FakeMonotonicClock();
        RecordingEventSink sink = new RecordingEventSink();
        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition), RunnerTestSupport.input(tempDir, clock, sink).build());

        assertEquals(RunStatus.NO_DATA, outcome.status());
        assertEquals(0, outcome.exitCode());
        assertEquals(TaskStatus.SKIPPED, outcome.task("finish").orElseThrow().status());
        assertEquals("empty source", outcome.task("finish").orElseThrow().reason());
        assertEquals(TaskStatus.NOT_RUN, outcome.task("later").orElseThrow().status());
        assertEquals("JOB_FINISHED", outcome.task("later").orElseThrow().reason());
        assertTrue(outcome.finishRequested());
        assertEquals(Optional.of("empty source"), outcome.finishReason());
        assertEquals(List.of("second", "first"), cleanupOrder);
        assertTrue(cleanupRan.stream().allMatch(Boolean::booleanValue));
    }

    @Test
    void catchingTheFinishSignalCannotResumeOperationWork() {
        AtomicInteger handlers = new AtomicInteger();
        var echo = TestOperations.echo("probe.echo", handlers);
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("finish").body(context -> {
                try {
                    context.finishJob("stop");
                } catch (RuntimeException caught) {
                    context.call(echo, "must-not-run");
                }
                return "leaked";
            }).build());
            builder.defaults("finish");
        });

        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(),
                        new RecordingEventSink()).build());

        assertEquals(RunStatus.NO_DATA, outcome.status());
        assertEquals(0, handlers.get());
        assertEquals(TaskStatus.SKIPPED, outcome.task("finish").orElseThrow().status());
        assertEquals(0, outcome.invocations().size());
    }

    @Test
    void cancellationStopsTaskAndMarksRemainingTasksNotRun() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RunControl control = new RunControl(clock, Optional.empty());
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("cancel").body(context -> {
                control.requestCancel(CancelReason.REQUESTED);
                context.cancellation().throwIfCancellationRequested();
                return "unreachable";
            }).build());
            builder.register(RunnerTestSupport.task("later").dependsOn("cancel")
                    .body(context -> "never").build());
            builder.defaults("later");
        });

        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, clock, new RecordingEventSink())
                        .runControl(control).build());

        assertEquals(RunStatus.CANCELLED, outcome.status());
        assertEquals(20, outcome.exitCode());
        assertEquals(TaskStatus.CANCELLED, outcome.task("cancel").orElseThrow().status());
        assertEquals(TaskStatus.NOT_RUN, outcome.task("later").orElseThrow().status());
        assertEquals("CANCELLED_BEFORE_START", outcome.task("later").orElseThrow().reason());
    }

    @Test
    void finishBeforeTheFirstTaskProducesNoDataWithoutStartingATask() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RunControl control = new RunControl(clock, Optional.empty());
        control.requestFinish("nothing to do");
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("a").body(context -> "a").build());
            builder.defaults("a");
        });

        RecordingEventSink sink = new RecordingEventSink();
        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, clock, sink).runControl(control).build());

        assertEquals(RunStatus.NO_DATA, outcome.status());
        assertEquals(0, outcome.exitCode());
        assertEquals(TaskStatus.NOT_RUN, outcome.task("a").orElseThrow().status());
        assertEquals("JOB_FINISHED", outcome.task("a").orElseThrow().reason());
        assertEquals(0, sink.count("gretl.task.started"));
    }

    @Test
    void failingPredicateFailsTheTaskWithoutInvocation() {
        AtomicInteger bodyCalls = new AtomicInteger();
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("condition").when(context -> {
                throw new IllegalStateException("condition failed");
            }).body(context -> {
                bodyCalls.incrementAndGet();
                return "never";
            }).build());
            builder.defaults("condition");
        });

        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(),
                        new RecordingEventSink()).build());

        assertEquals(RunStatus.FAILED, outcome.status());
        assertEquals(0, bodyCalls.get());
        assertEquals(TaskStatus.FAILED, outcome.task("condition").orElseThrow().status());
        assertEquals(0, outcome.task("condition").orElseThrow().invocations().size());
    }

    @Test
    void cancellationBeforeTheFirstTaskMarksEverythingNotRun() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RunControl control = new RunControl(clock, Optional.empty());
        control.requestCancel(CancelReason.REQUESTED);
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("a").body(context -> "a").build());
            builder.defaults("a");
        });

        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, clock, new RecordingEventSink())
                        .runControl(control).build());

        assertEquals(RunStatus.CANCELLED, outcome.status());
        assertEquals(TaskStatus.NOT_RUN, outcome.task("a").orElseThrow().status());
        assertEquals("CANCELLED_BEFORE_START", outcome.task("a").orElseThrow().reason());
    }

    @Test
    void timeoutInsideTaskBecomesControlledCancellation() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RunInput input = RunnerTestSupport.input(tempDir, clock, new RecordingEventSink())
                .runControl(new RunControl(clock, Optional.of(Duration.ofSeconds(1))))
                .build();
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("slow").body(context -> {
                clock.advance(Duration.ofSeconds(2));
                context.cancellation().throwIfCancellationRequested();
                return "unreachable";
            }).build());
            builder.defaults("slow");
        });

        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition), input);
        assertEquals(RunStatus.CANCELLED, outcome.status());
        assertEquals(20, outcome.exitCode());
        assertEquals(TaskStatus.CANCELLED, outcome.task("slow").orElseThrow().status());
    }
}
