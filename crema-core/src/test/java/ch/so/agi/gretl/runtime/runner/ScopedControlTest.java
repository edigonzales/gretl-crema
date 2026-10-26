package ch.so.agi.gretl.runtime.runner;

import static org.junit.jupiter.api.Assertions.*;

import ch.so.agi.gretl.runtime.api.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** F2-02/08, TC-F2-15..20: observations never become control boundaries. */
class ScopedControlTest {
    @TempDir Path root;
    final FakeMonotonicClock clock = new FakeMonotonicClock();

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void closedTaskAndInvocationHandlesKeepTheirOwnSnapshot(boolean invocation, boolean binding) {
        AtomicReference<CancellationToken> token = new AtomicReference<>();
        AtomicReference<TimeBudget> budget = new AtomicReference<>();
        AtomicReference<Duration> frozen = new AtomicReference<>();
        OperationType<String, String> op = operation((r, c) -> {
            token.set(c.cancellation()); budget.set(c.budget()); return r;
        });
        JobDefinition definition = RunnerTestSupport.definition(root, b -> {
            b.register(RunnerTestSupport.task("first").timeout(Duration.ofSeconds(1)).body(c -> {
                if (invocation) c.call(op, "ok");
                else { token.set(c.cancellation()); budget.set(c.budget()); }
                frozen.set(budget.get().remaining().orElseThrow());
                return "ok";
            }).build());
            b.register(RunnerTestSupport.task("second").dependsOn("first").timeout(Duration.ofSeconds(10)).body(c -> {
                clock.advance(Duration.ofSeconds(2));
                if (binding) {
                    ContextAccessException e = assertThrows(ContextAccessException.class,
                            () -> token.get().throwIfCancellationRequested());
                    assertEquals("CONTEXT_CLOSED", e.code());
                }
                assertFalse(token.get().isCancellationRequested());
                assertTrue(token.get().reason().isEmpty());
                assertFalse(budget.get().expired());
                assertEquals(frozen.get(), budget.get().remaining().orElseThrow());
                c.cancellation().throwIfCancellationRequested();
                return "second";
            }).build());
            b.defaults("second");
        });
        JobOutcome outcome = run(definition, null);
        assertEquals(RunStatus.SUCCEEDED, outcome.status());
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void purePollingDoesNotChangePrecedenceOrInterrupt(boolean local, boolean remaining) {
        RunControl control = new RunControl(clock, local ? Optional.empty() : Optional.of(Duration.ofSeconds(1)));
        JobDefinition definition = RunnerTestSupport.definition(root, b -> {
            b.register(RunnerTestSupport.task("work").timeout(local ? Duration.ofSeconds(1) : null).body(c -> {
                clock.advance(Duration.ofSeconds(2));
                if (remaining) assertEquals(Duration.ZERO, c.budget().remaining().orElseThrow());
                else assertTrue(c.budget().expired());
                assertTrue(c.cancellation().isCancellationRequested());
                assertEquals(CancelReason.TIMEOUT, c.cancellation().reason().orElseThrow());
                assertFalse(control.cancellationObserved(), "Pure read must not observe cancellation");
                throw new IOException("first-real-failure");
            }).build());
            b.defaults("work");
        });
        JobOutcome outcome = run(definition, control);
        assertEquals(RunStatus.FAILED, outcome.status());
        assertEquals("first-real-failure", outcome.primaryFailure().orElseThrow().message());
    }

    @Test
    void frozenSuccessDoesNotInheritLaterRequestedCancellation() {
        AtomicReference<CancellationToken> old = new AtomicReference<>();
        RunControl control = new RunControl(clock, Optional.empty());
        JobDefinition d = RunnerTestSupport.definition(root, b -> {
            b.register(RunnerTestSupport.task("first").body(c -> { old.set(c.cancellation()); return null; }).build());
            b.register(RunnerTestSupport.task("second").dependsOn("first").body(c -> {
                control.requestCancel(CancelReason.REQUESTED);
                assertFalse(old.get().isCancellationRequested());
                assertTrue(old.get().reason().isEmpty());
                c.cancellation().throwIfCancellationRequested(); return null;
            }).build()); b.defaults("second");
        });
        assertEquals(RunStatus.CANCELLED, run(d, control).status());
    }

    @Test
    void twoCleanupsShareOneGraceBudgetAfterWorkingTimeout() {
        List<String> order = new ArrayList<>();
        JobDefinition d = RunnerTestSupport.definition(root, b -> {
            b.register(RunnerTestSupport.task("work").timeout(Duration.ofSeconds(1)).body(c -> {
                clock.advance(Duration.ofSeconds(2)); return "late";
            }).build());
            b.cleanup("first", c -> {
                assertEquals(Duration.ofSeconds(18), c.budget().remaining().orElseThrow()); order.add("first");
            });
            b.cleanup("second", c -> {
                assertEquals(Duration.ofSeconds(30), c.budget().remaining().orElseThrow());
                clock.advance(Duration.ofSeconds(12)); order.add("second");
            }); b.defaults("work");
        });
        assertEquals(RunStatus.CANCELLED, run(d, null).status());
        assertEquals(List.of("second", "first"), order);
    }

    @Test
    void successiveInvocationsShareTheOriginalDeadlineAndFreezeEarlierSuccess() {
        AtomicReference<TimeBudget> old = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        var op = operation((request, c) -> {
            if (calls.incrementAndGet() == 1) old.set(c.budget());
            clock.advance(Duration.ofMillis(600));
            return request;
        });
        JobDefinition d = RunnerTestSupport.definition(root, b -> {
            b.register(RunnerTestSupport.task("work").timeout(Duration.ofSeconds(1)).body(c -> {
                c.call(op, "first");
                assertThrows(TaskCancelledException.class, () -> c.call(op, "second"));
                assertEquals(Duration.ofMillis(400), old.get().remaining().orElseThrow());
                assertFalse(old.get().expired());
                assertThrows(TaskCancelledException.class, () -> c.call(op, "never"));
                return "no result";
            }).build()); b.defaults("work");
        });
        JobOutcome result = run(d, null);
        assertEquals(RunStatus.CANCELLED, result.status());
        assertEquals(2, calls.get());
        assertEquals(InvocationStatus.SUCCEEDED, result.invocations().getFirst().status());
        assertTrue(result.invocations().getFirst().cancelReason().isEmpty());
        assertEquals(Optional.of(CancelReason.TIMEOUT), result.invocations().get(1).cancelReason());
    }

    @Test
    void cancelledHandleRetainsItsCauseAndBudgetAfterRunEnd() {
        AtomicReference<CancellationToken> old = new AtomicReference<>();
        AtomicReference<TimeBudget> oldBudget = new AtomicReference<>();
        JobDefinition d = RunnerTestSupport.definition(root, b -> {
            b.register(RunnerTestSupport.task("work").timeout(Duration.ofSeconds(1)).body(c -> {
                old.set(c.cancellation()); oldBudget.set(c.budget());
                clock.advance(Duration.ofSeconds(2));
                c.cancellation().throwIfCancellationRequested(); return null;
            }).build()); b.defaults("work");
        });
        assertEquals(RunStatus.CANCELLED, run(d, null).status());
        clock.set(0);
        assertTrue(old.get().isCancellationRequested());
        assertEquals(Optional.of(CancelReason.TIMEOUT), old.get().reason());
        assertEquals(Optional.of(Duration.ZERO), oldBudget.get().remaining());
        assertTrue(oldBudget.get().expired());
        assertEquals("CONTEXT_CLOSED", assertThrows(ContextAccessException.class,
                () -> old.get().throwIfCancellationRequested()).code());
    }

    @Test
    void closedBindingDoesNotClearInterruptFlag() {
        AtomicReference<CancellationToken> old = new AtomicReference<>();
        JobDefinition d = RunnerTestSupport.definition(root, b -> {
            b.register(RunnerTestSupport.task("first").body(c -> { old.set(c.cancellation()); return null; }).build());
            b.register(RunnerTestSupport.task("second").dependsOn("first").body(c -> {
                Thread.currentThread().interrupt();
                assertEquals("CONTEXT_CLOSED", assertThrows(ContextAccessException.class,
                        () -> old.get().throwIfCancellationRequested()).code());
                assertTrue(Thread.currentThread().isInterrupted());
                return null;
            }).build()); b.defaults("second");
        });
        try { assertEquals(RunStatus.CANCELLED, run(d, null).status()); }
        finally { Thread.interrupted(); }
    }

    @Test
    void taskBudgetIncludesSynchronousStartObservation() {
        AtomicInteger predicates = new AtomicInteger();
        AtomicInteger bodies = new AtomicInteger();
        AtomicInteger cleanups = new AtomicInteger();
        JobDefinition d = RunnerTestSupport.definition(root, b -> {
            b.register(RunnerTestSupport.task("work").timeout(Duration.ofSeconds(1))
                    .when(c -> { predicates.incrementAndGet(); return true; })
                    .body(c -> { bodies.incrementAndGet(); return null; }).build());
            b.cleanup("release", c -> cleanups.incrementAndGet());
            b.defaults("work");
        });
        var input = RunnerTestSupport.input(root, clock, event -> {
            if (event.eventName().equals("gretl.task.started")) clock.advance(Duration.ofSeconds(2));
        }).build();
        JobOutcome result = new SequentialJobRunner().run(d, RunnerTestSupport.plan(d), input);
        assertEquals(RunStatus.CANCELLED, result.status());
        assertEquals(Optional.of(CancelReason.TIMEOUT), result.tasks().getFirst().cancelReason());
        assertEquals(0, predicates.get());
        assertEquals(0, bodies.get());
        assertEquals(1, cleanups.get());
    }

    private JobOutcome run(JobDefinition d, RunControl control) {
        var input = RunnerTestSupport.input(root, clock, new RecordingEventSink());
        if (control != null) input.runControl(control);
        return new SequentialJobRunner().run(d, RunnerTestSupport.plan(d), input.build());
    }

    static OperationType<String,String> operation(OperationHandler<String,String> handler) {
        return new OperationType<>("test.scoped", "ScopedProbe", "scope probe", String.class, String.class,
                () -> new ConfigBuilder<>() { public String build() { return "ok"; } },
                () -> handler, List.of(), List.of(), "task('probe', ScopedProbe) {}");
    }
}
