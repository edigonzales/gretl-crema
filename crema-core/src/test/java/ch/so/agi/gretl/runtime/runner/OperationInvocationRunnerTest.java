package ch.so.agi.gretl.runtime.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.so.agi.gretl.runtime.api.CancelReason;
import ch.so.agi.gretl.runtime.api.ContextAccessException;
import ch.so.agi.gretl.runtime.api.LogLevel;
import ch.so.agi.gretl.runtime.api.OperationType;
import ch.so.agi.gretl.runtime.api.TaskContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperationInvocationRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void contextCallUsesAFreshHandlerAndRecordsTheInvocation() throws Exception {
        AtomicInteger handlers = new AtomicInteger();
        OperationType<String, String> echo = TestOperations.echo("probe.echo", handlers);
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("caller")
                    .body(context -> context.call(echo, "request")).build());
            builder.defaults("caller");
        });

        RecordingEventSink sink = new RecordingEventSink();
        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(), sink).build());

        assertEquals(RunStatus.SUCCEEDED, outcome.status());
        assertEquals(new ResultSlot.Value("request"),
                outcome.task("caller").orElseThrow().slot());
        assertEquals(1, handlers.get());
        assertEquals(1, outcome.invocations().size());
        InvocationOutcome invocation = outcome.invocations().get(0);
        assertEquals(InvocationStatus.SUCCEEDED, invocation.status());
        assertEquals("probe.echo", invocation.operationId());
        assertEquals("caller", invocation.parentTaskId());
        assertEquals("cleanup", invocation.phase());
        assertTrue(invocation.invocationId().startsWith("i"));
        assertEquals(1, sink.count("gretl.operation.started"));
        assertEquals(1, sink.count("gretl.operation.finished"));
    }

    @Test
    void caughtInvocationFailureStillAllowsTheActionToSucceed() {
        AtomicInteger handlers = new AtomicInteger();
        OperationType<String, String> failing = TestOperations.failing("probe.fail", handlers);
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("caller").body(context -> {
                try {
                    return context.call(failing, "request");
                } catch (IllegalStateException expected) {
                    return "handled";
                }
            }).build());
            builder.defaults("caller");
        });

        RecordingEventSink sink = new RecordingEventSink();
        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(), sink).build());

        assertEquals(RunStatus.SUCCEEDED, outcome.status());
        assertEquals(new ResultSlot.Value("handled"),
                outcome.task("caller").orElseThrow().slot());
        assertEquals(InvocationStatus.FAILED, outcome.invocations().get(0).status());
        assertEquals(IllegalStateException.class.getName(),
                outcome.invocations().get(0).failure().orElseThrow().type());
        assertEquals(1, sink.count("gretl.operation.failed"));
        assertEquals(LogLevel.INFO, sink.last("gretl.run.finished").level());
    }

    @Test
    void configurationFailureAlreadyHasInvocationIdentityAndConfigurationPhase() {
        AtomicInteger handlers = new AtomicInteger();
        OperationType<String, String> echo = TestOperations.echo("probe.config", handlers);
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(TaskRegistration.builder("typed", String.class)
                    .body(new OperationTaskBody<String, String>(echo, frame -> {
                        throw new IllegalArgumentException("bad configuration");
                    }))
                    .build());
            builder.defaults("typed");
        });

        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(),
                        new RecordingEventSink()).build());

        assertEquals(RunStatus.FAILED, outcome.status());
        assertEquals(TaskStatus.FAILED, outcome.task("typed").orElseThrow().status());
        assertEquals(0, handlers.get());
        InvocationOutcome invocation = outcome.task("typed").orElseThrow()
                .invocations().get(0);
        assertEquals(InvocationStatus.FAILED, invocation.status());
        assertEquals("configuration", invocation.phase());
        assertEquals("probe.config", invocation.operationId());
        assertTrue(invocation.invocationId().matches("i[0-9]{6}"));
        assertTrue(invocation.failure().isPresent());
    }

    @Test
    void nonVoidHandlerNullIsAProviderContractFailure() {
        AtomicInteger handlers = new AtomicInteger();
        OperationType<String, String> nullResult = TestOperations.nullResult("probe.null", handlers);
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(TaskRegistration.builder("typed", String.class)
                    .body(new OperationTaskBody<String, String>(nullResult, frame -> "request"))
                    .build());
            builder.defaults("typed");
        });

        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(),
                        new RecordingEventSink()).build());

        assertEquals(RunStatus.FAILED, outcome.status());
        assertEquals(ProviderContractException.class.getName(),
                outcome.task("typed").orElseThrow().failure().orElseThrow().type());
        assertEquals(1, handlers.get());
        assertEquals(InvocationStatus.FAILED,
                outcome.task("typed").orElseThrow().invocations().get(0).status());
    }

    @Test
    void voidOperationNullIsSuccessWithoutValue() {
        AtomicInteger handlers = new AtomicInteger();
        OperationType<String, Void> voidOperation = TestOperations.voidOperation(
                "probe.void", handlers);
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(TaskRegistration.builder("typed", Void.class)
                    .body(new OperationTaskBody<String, Void>(voidOperation, frame -> "request"))
                    .build());
            builder.defaults("typed");
        });

        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(),
                        new RecordingEventSink()).build());

        assertEquals(RunStatus.SUCCEEDED, outcome.status());
        assertEquals(TaskStatus.SUCCEEDED, outcome.task("typed").orElseThrow().status());
        assertEquals(ResultSlot.NoValue.INSTANCE, outcome.task("typed").orElseThrow().slot());
        assertEquals(InvocationStatus.SUCCEEDED,
                outcome.task("typed").orElseThrow().invocations().get(0).status());
    }

    @Test
    void actionNullIsSuccessWithoutValue() {
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("action")
                    .body(context -> null).build());
            builder.defaults("action");
        });

        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(),
                        new RecordingEventSink()).build());

        assertEquals(RunStatus.SUCCEEDED, outcome.status());
        assertEquals(TaskStatus.SUCCEEDED, outcome.task("action").orElseThrow().status());
        assertEquals(ResultSlot.NoValue.INSTANCE, outcome.task("action").orElseThrow().slot());
        assertTrue(outcome.invocations().isEmpty());
    }

    @Test
    void twoCallSitesUseFreshHandlersAndDistinctInvocationIds() {
        AtomicInteger handlers = new AtomicInteger();
        OperationType<String, String> echo = TestOperations.echo("probe.echo", handlers);
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("caller").body(context -> {
                String first = context.call(echo, "one");
                String second = context.call(echo, "two");
                return first + second;
            }).build());
            builder.defaults("caller");
        });

        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(),
                        new RecordingEventSink()).build());

        assertEquals(RunStatus.SUCCEEDED, outcome.status());
        assertEquals(2, handlers.get());
        assertEquals(2, outcome.invocations().size());
        assertNotEquals(outcome.invocations().get(0).invocationId(),
                outcome.invocations().get(1).invocationId());
        assertEquals("onetwo", outcome.task("caller").orElseThrow().slot()
                instanceof ResultSlot.Value value ? value.value() : null);
    }

    @Test
    void nullHandlerFactoryIsAProviderContractFailure() {
        OperationType<String, String> nullHandler = TestOperations.nullHandler("probe.null-handler");
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(TaskRegistration.builder("typed", String.class)
                    .body(new OperationTaskBody<String, String>(nullHandler, frame -> "request"))
                    .build());
            builder.defaults("typed");
        });

        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(),
                        new RecordingEventSink()).build());

        assertEquals(RunStatus.FAILED, outcome.status());
        assertEquals(ProviderContractException.class.getName(),
                outcome.task("typed").orElseThrow().failure().orElseThrow().type());
        assertEquals(InvocationStatus.FAILED,
                outcome.task("typed").orElseThrow().invocations().get(0).status());
    }

    @Test
    void closeErrorAfterHandlerValueFailsTheInvocationAndTask() {
        AtomicInteger handlers = new AtomicInteger();
        OperationType<String, String> closeError = TestOperations.closeError(
                "probe.close", handlers);
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(TaskRegistration.builder("typed", String.class)
                    .body(new OperationTaskBody<String, String>(closeError, frame -> "request"))
                    .build());
            builder.defaults("typed");
        });

        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(),
                        new RecordingEventSink()).build());

        assertEquals(RunStatus.FAILED, outcome.status());
        assertEquals(ResultSlot.NoValue.INSTANCE, outcome.task("typed").orElseThrow().slot());
        assertEquals("java.io.IOException",
                outcome.task("typed").orElseThrow().failure().orElseThrow().type());
        assertEquals(InvocationStatus.FAILED,
                outcome.task("typed").orElseThrow().invocations().get(0).status());
    }

    @Test
    void taskResourceScopeClosesInReverseOrderAndCloseErrorFailsTheTask() {
        List<String> closeOrder = new ArrayList<>();
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("resources").body(context -> {
                context.resources().manage(() -> closeOrder.add("first"));
                context.resources().manage(() -> {
                    closeOrder.add("second");
                    throw new IOException("close-error");
                });
                return "value";
            }).build());
            builder.defaults("resources");
        });

        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(),
                        new RecordingEventSink()).build());

        assertEquals(RunStatus.FAILED, outcome.status());
        assertEquals(TaskStatus.FAILED, outcome.task("resources").orElseThrow().status());
        assertEquals(List.of("second", "first"), closeOrder);
        assertEquals(ResultSlot.NoValue.INSTANCE, outcome.task("resources").orElseThrow().slot());
    }

    @Test
    void cleanupRunsInReverseOrderAndFailureAfterSuccessFailsTheRun() {
        List<String> order = new ArrayList<>();
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("a").body(context -> "a").build());
            builder.cleanup("first", context -> order.add("first"));
            builder.cleanup("second", context -> {
                order.add("second");
                throw new IOException("cleanup-error");
            });
            builder.defaults("a");
        });

        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(),
                        new RecordingEventSink()).build());

        assertEquals(List.of("second", "first"), order);
        assertEquals(RunStatus.FAILED, outcome.status());
        assertEquals(TaskStatus.SUCCEEDED, outcome.task("a").orElseThrow().status());
        assertEquals(IOException.class.getName(),
                outcome.primaryFailure().orElseThrow().type());
    }

    @Test
    void cleanupFailureAfterTaskFailureKeepsTheTaskAsPrimary() {
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("failing").body(context -> {
                throw new IllegalStateException("task");
            }).build());
            builder.cleanup("cleanup", context -> {
                throw new IOException("cleanup");
            });
            builder.defaults("failing");
        });

        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(),
                        new RecordingEventSink()).build());

        assertEquals(RunStatus.FAILED, outcome.status());
        assertEquals(IllegalStateException.class.getName(),
                outcome.primaryFailure().orElseThrow().type());
        assertEquals(1, outcome.secondaryFailures().size());
        assertEquals(IOException.class.getName(),
                outcome.secondaryFailures().get(0).type());
    }

    @Test
    void cleanupAfterFinishFailureMakesTheRunFailed() {
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("finish").body(context -> {
                context.finishJob("no data");
                return "unreachable";
            }).build());
            builder.cleanup("cleanup", context -> {
                throw new IOException("cleanup");
            });
            builder.defaults("finish");
        });

        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(),
                        new RecordingEventSink()).build());

        assertEquals(RunStatus.FAILED, outcome.status());
        assertTrue(outcome.finishRequested());
        assertEquals(IOException.class.getName(),
                outcome.primaryFailure().orElseThrow().type());
    }

    @Test
    void cleanupFailureAfterCancellationStaysSecondary() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RunControl control = new RunControl(clock, Optional.empty());
        control.requestCancel(CancelReason.REQUESTED);
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("a").body(context -> "a").build());
            builder.cleanup("cleanup", context -> {
                throw new IOException("cleanup");
            });
            builder.defaults("a");
        });

        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, clock, new RecordingEventSink())
                        .runControl(control).build());

        assertEquals(RunStatus.CANCELLED, outcome.status());
        assertEquals(1, outcome.secondaryFailures().size());
        assertEquals(IOException.class.getName(),
                outcome.secondaryFailures().get(0).type());
    }

    @Test
    void contextLifetimeEndsWithTheTaskButIdentityAndLateLogRemainSafe() {
        AtomicReference<TaskContext> captured = new AtomicReference<>();
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("a").body(context -> {
                captured.set(context);
                return "value";
            }).build());
            builder.defaults("a");
        });

        RecordingEventSink sink = new RecordingEventSink();
        JobOutcome outcome = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, new FakeMonotonicClock(), sink).build());
        TaskContext context = captured.get();
        assertEquals("a", context.identity().taskId().orElseThrow());

        ContextAccessException closed = assertThrows(ContextAccessException.class,
                () -> context.parameter("missing"));
        assertEquals("CONTEXT_CLOSED", closed.code());
        assertThrows(ContextAccessException.class, () -> context.workPath("x"));

        context.log().info("late");
        RunEvent late = sink.last("gretl.late_log");
        assertEquals(Optional.of("a"), late.taskId());
        assertEquals("gretl.task.message", late.attributes().get("original.event_name"));
    }

    @Test
    void twoRunsGetFreshContextsAndResultStores() {
        List<TaskContext> contexts = new ArrayList<>();
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("a").body(context -> {
                contexts.add(context);
                return "value";
            }).build());
            builder.defaults("a");
        });

        FakeMonotonicClock clock = new FakeMonotonicClock();
        RecordingEventSink sink = new RecordingEventSink();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        JobOutcome first = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, clock, sink).runId(firstId).build());
        JobOutcome second = new SequentialJobRunner().run(definition,
                RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, clock, sink).runId(secondId).build());

        assertEquals(firstId, first.runId());
        assertEquals(secondId, second.runId());
        assertEquals(2, contexts.size());
        assertNotSame(contexts.get(0), contexts.get(1));
        assertEquals(new ResultSlot.Value("value"), first.task("a").orElseThrow().slot());
        assertEquals(new ResultSlot.Value("value"), second.task("a").orElseThrow().slot());
    }
}
