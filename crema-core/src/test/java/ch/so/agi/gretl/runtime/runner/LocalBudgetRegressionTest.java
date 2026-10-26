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
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocalBudgetRegressionTest {
    @TempDir Path tempDir;
    private final FakeMonotonicClock clock = new FakeMonotonicClock();
    private static final Duration LIMIT = Duration.ofSeconds(1);

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void expiredPredicateCancelsBeforeBodyEvenWhenItReturnsFalse(boolean predicate) {
        AtomicInteger bodies = new AtomicInteger();
        JobOutcome outcome = run(RunnerTestSupport.task("work").timeout(LIMIT)
                .when(ctx -> { clock.advance(LIMIT); return predicate; })
                .body(ctx -> bodies.incrementAndGet()).build());
        assertCancelled(outcome);
        assertEquals(0, bodies.get());
    }

    @Test
    void expiredConfigurationDoesNotConstructAHandler() {
        AtomicInteger factories = new AtomicInteger();
        var operation = operation(() -> { factories.incrementAndGet(); return (r, c) -> r; });
        JobOutcome outcome = run(TaskRegistration.builder("work", String.class).timeout(LIMIT)
                .body(new OperationTaskBody<>(operation, frame -> {
                    clock.advance(LIMIT);
                    return "configured";
                })).build());
        assertCancelled(outcome);
        assertEquals(0, factories.get());
        assertEquals(InvocationStatus.CANCELLED, outcome.invocations().getFirst().status());
        assertEquals("configuration", outcome.invocations().getFirst().phase());
    }

    @Test
    void expiredHandlerFactoryDoesNotExecuteTheHandler() {
        AtomicInteger executions = new AtomicInteger();
        var operation = operation(() -> {
            clock.advance(LIMIT);
            return (r, c) -> { executions.incrementAndGet(); return r; };
        });
        JobOutcome outcome = run(RunnerTestSupport.task("work").timeout(LIMIT)
                .body(ctx -> ctx.call(operation, "request")).build());
        assertCancelled(outcome);
        assertEquals(0, executions.get());
    }

    @Test
    void handlerReturnAfterDeadlineCannotPublishAValue() {
        var operation = operation(() -> (r, c) -> { clock.advance(LIMIT); return r; });
        JobOutcome outcome = run(RunnerTestSupport.task("work").timeout(LIMIT)
                .body(ctx -> ctx.call(operation, "request")).build());
        assertCancelled(outcome);
        assertEquals(InvocationStatus.CANCELLED, outcome.invocations().getFirst().status());
    }

    @Test
    void actionReturnAfterDeadlineCannotPublishAValue() {
        assertCancelled(run(RunnerTestSupport.task("work").timeout(LIMIT)
                .body(ctx -> { clock.advance(LIMIT); return "too late"; }).build()));
    }

    @Test
    void caughtLocalCancellationStillBlocksNewCallsAndReturn() {
        AtomicInteger executions = new AtomicInteger();
        var operation = operation(() -> (r, c) -> { executions.incrementAndGet(); return r; });
        JobOutcome outcome = run(RunnerTestSupport.task("work").timeout(LIMIT).body(ctx -> {
            clock.advance(LIMIT);
            var timeout = assertThrows(TaskCancelledException.class,
                    () -> ctx.cancellation().throwIfCancellationRequested());
            assertEquals(CancelReason.TIMEOUT, timeout.reason());
            assertTrue(ctx.cancellation().isCancellationRequested());
            assertEquals(CancelReason.TIMEOUT, ctx.cancellation().reason().orElseThrow());
            assertThrows(TaskCancelledException.class, () -> ctx.call(operation, "blocked"));
            return "caught";
        }).build());
        assertCancelled(outcome);
        assertEquals(0, executions.get());
    }

    @Test
    void caughtInvocationTimeoutCannotMakeItsParentSuccessful() {
        var operation = operation(() -> (r, c) -> {
            throw new TaskCancelledException(CancelReason.TIMEOUT);
        });
        JobOutcome outcome = run(RunnerTestSupport.task("work").body(ctx -> {
            assertThrows(TaskCancelledException.class, () -> ctx.call(operation, "request"));
            return "caught";
        }).build());
        assertCancelled(outcome);
    }

    @Test
    void timeoutDuringInvocationCloseIsStickyAndAllResourcesClose() {
        List<String> closed = new ArrayList<>();
        var operation = operation(() -> (r, c) -> {
            c.resources().manage(() -> closed.add("first"));
            c.resources().manage(() -> { clock.advance(LIMIT); closed.add("second"); });
            return r;
        });
        JobOutcome outcome = run(RunnerTestSupport.task("work").timeout(LIMIT).body(ctx -> {
            assertThrows(TaskCancelledException.class, () -> ctx.call(operation, "request"));
            return "caught";
        }).build());
        assertCancelled(outcome);
        assertEquals(List.of("second", "first"), closed);
        assertEquals(InvocationStatus.CANCELLED, outcome.invocations().getFirst().status());
    }

    @Test
    void timeoutDuringTaskCloseKeepsFollowingCloseFailureSecondary() {
        List<String> closed = new ArrayList<>();
        JobOutcome outcome = run(RunnerTestSupport.task("work").timeout(LIMIT).body(ctx -> {
            ctx.resources().manage(() -> { closed.add("first"); throw new IOException("late close"); });
            ctx.resources().manage(() -> { clock.advance(LIMIT); closed.add("second"); });
            return "value";
        }).build());
        assertCancelled(outcome);
        assertEquals(List.of("second", "first"), closed);
        assertTrue(outcome.secondaryFailures().stream().anyMatch(f -> f.message().equals("late close")));
    }

    @Test
    void realFailureBeforeTimeoutDuringCloseStaysPrimary() {
        JobOutcome outcome = run(RunnerTestSupport.task("work").timeout(LIMIT).body(ctx -> {
            ctx.resources().manage(() -> clock.advance(LIMIT));
            throw new IOException("work first");
        }).build());
        assertEquals(RunStatus.FAILED, outcome.status());
        assertEquals("work first", outcome.primaryFailure().orElseThrow().message());
    }

    @Test
    void observedCancellationBeforeWorkFailureKeepsFailureSecondary() {
        JobOutcome outcome = run(RunnerTestSupport.task("work").timeout(LIMIT).body(ctx -> {
            clock.advance(LIMIT);
            try { ctx.cancellation().throwIfCancellationRequested(); }
            catch (TaskCancelledException expected) { /* later failure is secondary */ }
            throw new IOException("after cancellation");
        }).build());
        assertCancelled(outcome);
        assertTrue(outcome.secondaryFailures().stream()
                .anyMatch(f -> f.message().equals("after cancellation")));
    }

    private JobOutcome run(TaskRegistration<?> task) {
        AtomicInteger cleanups = new AtomicInteger();
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(task);
            builder.register(RunnerTestSupport.task("later").dependsOn("work")
                    .body(ctx -> fail("following task must not start")).build());
            builder.cleanup("independent grace", ctx -> {
                assertEquals(Duration.ofSeconds(30), ctx.budget().remaining().orElseThrow());
                cleanups.incrementAndGet();
            });
            builder.defaults("later");
        });
        JobOutcome result = new SequentialJobRunner().run(definition, RunnerTestSupport.plan(definition),
                RunnerTestSupport.input(tempDir, clock, new RecordingEventSink()).build());
        assertEquals(1, cleanups.get());
        return result;
    }

    private static void assertCancelled(JobOutcome outcome) {
        assertEquals(RunStatus.CANCELLED, outcome.status());
        assertEquals(20, outcome.exitCode());
        assertEquals(TaskStatus.CANCELLED, outcome.task("work").orElseThrow().status());
        assertEquals(ResultSlot.NoValue.INSTANCE, outcome.task("work").orElseThrow().slot());
        assertEquals(TaskStatus.NOT_RUN, outcome.task("later").orElseThrow().status());
    }

    private static OperationType<String, String> operation(Supplier<OperationHandler<String, String>> factory) {
        return new OperationType<>("probe.budget", "BudgetProbe", "Budget test", String.class,
                String.class, () -> new ConfigBuilder<>() {
                    @Override public String build() { return "request"; }
                }, factory, List.of(), List.of(), "task('work', BudgetProbe) {}");
    }
}
