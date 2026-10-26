package ch.so.agi.gretl.runtime.runner;

import static org.junit.jupiter.api.Assertions.*;

import ch.so.agi.gretl.runtime.api.CancelReason;
import ch.so.agi.gretl.runtime.api.ConfigBuilder;
import ch.so.agi.gretl.runtime.api.OperationHandler;
import ch.so.agi.gretl.runtime.api.OperationType;
import ch.so.agi.gretl.runtime.api.TaskCancelledException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Counterexamples crossing invocation, release, parent-task and completion boundaries. */
class BudgetPrecedenceTest {
    @TempDir Path tempDir;
    private final FakeMonotonicClock clock = new FakeMonotonicClock();
    private final AtomicInteger cleanups = new AtomicInteger();
    private static final Duration LIMIT = Duration.ofSeconds(1);

    @Test
    void invocationWorkFailureBeforeTimeoutDuringReleaseStaysPrimaryAtParent() {
        var operation = operation((request, context) -> {
            context.resources().manage(() -> clock.advance(LIMIT));
            throw new IOException("invocation-work-first");
        });
        JobOutcome outcome = run(RunnerTestSupport.task("work").timeout(LIMIT)
                .body(ctx -> ctx.call(operation, "request")).build());
        assertEquals(RunStatus.FAILED, outcome.status());
        assertEquals("invocation-work-first", outcome.primaryFailure().orElseThrow().message());
        assertEquals(InvocationStatus.FAILED, outcome.invocations().getFirst().status());
        assertEquals(1, cleanups.get());
    }

    @Test
    void invocationReleaseFailureBeforeLaterTimeoutStaysPrimaryAtParent() {
        var operation = operation((request, context) -> {
            context.resources().manage(() -> clock.advance(LIMIT));
            context.resources().manage(() -> { throw new IOException("invocation-release-first"); });
            return request;
        });
        JobOutcome outcome = run(RunnerTestSupport.task("work").timeout(LIMIT)
                .body(ctx -> ctx.call(operation, "request")).build());
        assertEquals(RunStatus.FAILED, outcome.status());
        assertEquals("invocation-release-first", outcome.primaryFailure().orElseThrow().message());
        assertEquals(InvocationStatus.FAILED, outcome.invocations().getFirst().status());
        assertEquals(1, cleanups.get());
    }

    @Test
    void explicitHandlerFactoryCancellationUsesTheSameControlledOutcome() {
        var operation = new OperationType<>("probe.factory-cancel", "FactoryCancel", "Factory cancel test",
                String.class, String.class, () -> new ConfigBuilder<String>() {
                    @Override public String build() { return "request"; }
                }, () -> { throw new TaskCancelledException(CancelReason.TIMEOUT); },
                List.of(), List.of(), "task('work', FactoryCancel) {}");
        JobOutcome outcome = run(RunnerTestSupport.task("work")
                .body(ctx -> ctx.call(operation, "request")).build());
        assertEquals(RunStatus.CANCELLED, outcome.status());
        assertEquals(InvocationStatus.CANCELLED, outcome.invocations().getFirst().status());
        assertEquals(1, cleanups.get());
    }

    @Test
    void deliberatelyCaughtInvocationFailureAllowsSuccessfulParent() {
        var operation = operation((request, context) -> { throw new IOException("handled-business-error"); });
        JobOutcome outcome = run(RunnerTestSupport.task("work").body(ctx -> {
            IOException failure = assertThrows(IOException.class, () -> ctx.call(operation, "request"));
            assertEquals("handled-business-error", failure.getMessage());
            return "handled";
        }).build());
        assertEquals(RunStatus.SUCCEEDED, outcome.status());
        assertEquals(TaskStatus.SUCCEEDED, outcome.task("work").orElseThrow().status());
        assertEquals(InvocationStatus.FAILED, outcome.invocations().getFirst().status());
        assertEquals(1, cleanups.get());
    }

    @Test
    void explicitReleaseCancellationKeepsNestedCausesAndLaterCloseErrors() {
        List<String> closed = new ArrayList<>();
        JobOutcome outcome = run(RunnerTestSupport.task("work").body(ctx -> {
            ctx.resources().manage(() -> { closed.add("later"); throw new IOException("later-close-error"); });
            ctx.resources().manage(() -> { closed.add("cancel"); throw cancellationWithCauses(); });
            return "value";
        }).build());
        assertEquals(RunStatus.CANCELLED, outcome.status());
        assertEquals(List.of("cancel", "later"), closed);
        assertFailureContains(outcome.secondaryFailures(), "release-cancellation-cause");
        assertFailureContains(outcome.secondaryFailures(), "release-cancellation-suppressed");
        assertFailureContains(outcome.secondaryFailures(), "later-close-error");
        assertEquals(1, cleanups.get());
    }

    @Test
    void explicitInvocationReleaseCancellationKeepsNestedCauses() {
        var operation = operation((request, context) -> {
            context.resources().manage(() -> { throw cancellationWithCauses(); });
            return request;
        });
        JobOutcome outcome = run(RunnerTestSupport.task("work")
                .body(ctx -> ctx.call(operation, "request")).build());
        assertEquals(RunStatus.CANCELLED, outcome.status());
        InvocationOutcome invocation = outcome.invocations().getFirst();
        assertEquals(InvocationStatus.CANCELLED, invocation.status());
        assertFailureContains(invocation.secondaryFailures(), "release-cancellation-cause");
        assertFailureContains(invocation.secondaryFailures(), "release-cancellation-suppressed");
        assertEquals(1, cleanups.get());
    }

    @Test
    void earlierRealReleaseFailureWinsOverLaterExplicitCancellation() {
        List<String> closed = new ArrayList<>();
        JobOutcome outcome = run(RunnerTestSupport.task("work").body(ctx -> {
            ctx.resources().manage(() -> { closed.add("cancel"); throw cancellationWithCauses(); });
            ctx.resources().manage(() -> { closed.add("first"); throw new IOException("release-error-first"); });
            return "value";
        }).build());
        assertEquals(RunStatus.FAILED, outcome.status());
        assertEquals("release-error-first", outcome.primaryFailure().orElseThrow().message());
        assertEquals(List.of("first", "cancel"), closed);
        assertEquals(1, cleanups.get());
    }

    @Test
    void finishDoesNotLetLaterCancellationHideEarlierReleaseFailure() {
        List<String> closed = new ArrayList<>();
        JobOutcome outcome = run(RunnerTestSupport.task("work").body(ctx -> {
            ctx.resources().manage(() -> { closed.add("cancel"); throw cancellationWithCauses(); });
            ctx.resources().manage(() -> { closed.add("first"); throw new IOException("finish-close-first"); });
            ctx.finishJob("no data");
            return null;
        }).build());
        assertEquals(RunStatus.FAILED, outcome.status());
        assertEquals("finish-close-first", outcome.primaryFailure().orElseThrow().message());
        assertEquals(List.of("first", "cancel"), closed);
        assertEquals(1, cleanups.get());
    }

    @Test
    void interruptedInvocationStillRunsCleanupAndRestoresInterruptOnReturn() {
        assertFalse(Thread.currentThread().isInterrupted());
        var operation = operation((request, context) -> { throw new InterruptedException("handler-interrupted"); });
        try {
            JobOutcome outcome = run(RunnerTestSupport.task("work")
                    .body(ctx -> ctx.call(operation, "request")).build());
            assertEquals(RunStatus.CANCELLED, outcome.status());
            assertEquals(1, cleanups.get(), "Work interruption must not cancel the separate cleanup budget");
            assertTrue(Thread.currentThread().isInterrupted(), "Interrupt state must be restored to the caller");
            assertFailureContains(outcome.invocations().getFirst().secondaryFailures(), "handler-interrupted");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptedReleaseRunsRemainingClosesAndCleanupThenRestoresInterrupt() {
        assertFalse(Thread.currentThread().isInterrupted());
        List<String> closed = new ArrayList<>();
        try {
            JobOutcome outcome = run(RunnerTestSupport.task("work").body(ctx -> {
                ctx.resources().manage(() -> closed.add("later"));
                ctx.resources().manage(() -> { closed.add("interrupted"); throw new InterruptedException("release-interrupted"); });
                return "value";
            }).build());
            assertEquals(RunStatus.CANCELLED, outcome.status());
            assertEquals(List.of("interrupted", "later"), closed);
            assertEquals(1, cleanups.get());
            assertTrue(Thread.currentThread().isInterrupted());
            assertFailureContains(outcome.secondaryFailures(), "release-interrupted");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void observedThreadInterruptRunsCleanupAndRestoresTheClearedFlag() {
        assertFalse(Thread.currentThread().isInterrupted());
        try {
            JobOutcome outcome = run(RunnerTestSupport.task("work").body(ctx -> {
                Thread.currentThread().interrupt();
                return "interrupted-return";
            }).build());
            assertEquals(RunStatus.CANCELLED, outcome.status());
            assertEquals(1, cleanups.get());
            assertTrue(Thread.currentThread().isInterrupted(), "Controlled observation must preserve caller interruption");
        } finally {
            Thread.interrupted();
        }
    }

    private JobOutcome run(TaskRegistration<?> task) {
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(task);
            builder.cleanup("independent-completion", context -> cleanups.incrementAndGet());
            builder.defaults("work");
        });
        return new SequentialJobRunner().run(definition, RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, clock, new RecordingEventSink()).build());
    }

    private static TaskCancelledException cancellationWithCauses() {
        TaskCancelledException cancellation = new TaskCancelledException(CancelReason.TIMEOUT);
        cancellation.initCause(new IOException("release-cancellation-cause"));
        cancellation.addSuppressed(new IOException("release-cancellation-suppressed"));
        return cancellation;
    }

    private static void assertFailureContains(List<FailureInfo> failures, String message) {
        assertTrue(failures.stream().anyMatch(failure -> contains(failure, message)),
                () -> "Missing retained failure '" + message + "' in " + failures);
    }

    private static boolean contains(FailureInfo failure, String message) {
        return failure.message().equals(message)
                || failure.causes().stream().anyMatch(cause -> contains(cause, message))
                || failure.suppressed().stream().anyMatch(cause -> contains(cause, message));
    }

    private static OperationType<String, String> operation(OperationHandler<String, String> handler) {
        return new OperationType<>("probe.precedence", "PrecedenceProbe", "Precedence test",
                String.class, String.class, () -> new ConfigBuilder<>() {
                    @Override public String build() { return "request"; }
                }, () -> handler, List.of(), List.of(), "task('work', PrecedenceProbe) {}");
    }
}
