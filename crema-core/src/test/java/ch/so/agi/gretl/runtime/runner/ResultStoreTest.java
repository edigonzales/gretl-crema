package ch.so.agi.gretl.runtime.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.so.agi.gretl.runtime.api.TaskRef;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResultStoreTest {

    @TempDir
    Path tempDir;

    private static final Instant T0 = Instant.parse("2026-10-18T10:00:00Z");
    private static final Instant T1 = T0.plusMillis(1);

    @Test
    void statusAndSlotAreIndependent() {
        Fixture fixture = fixture();
        ResultStore store = fixture.store();
        TaskRef<Object> a = fixture.a();

        assertEquals(TaskStatus.PENDING, store.status(a));
        assertEquals(ResultSlot.NoValue.INSTANCE, store.slot(a));

        store.start(a, T0, 0);
        assertEquals(TaskStatus.RUNNING, store.status(a));
        assertEquals(ResultSlot.NoValue.INSTANCE, store.slot(a));

        store.complete(a, TaskCompletion.succeeded("value"), T1, 1_000_000L);
        assertEquals(TaskStatus.SUCCEEDED, store.status(a));
        assertEquals(new ResultSlot.Value("value"), store.slot(a));
        assertEquals(1, store.outcome(a).durationMillis());
    }

    @Test
    void successfulValueCanBeReadOnlyByADataDependentConsumer() {
        Fixture fixture = fixture();
        ResultStore store = fixture.store();
        TaskRef<Object> a = fixture.a();
        TaskRef<Object> b = fixture.b();

        store.start(a, T0, 0);
        store.complete(a, TaskCompletion.succeeded("value"), T1, 1_000_000L);

        assertEquals("value", store.require(b, a));
        assertEquals(Optional.of("value"), store.optional(b, a));
    }

    @Test
    void afterAloneDoesNotGrantResultAccess() {
        AtomicReference<TaskRef<Object>> a = new AtomicReference<>();
        AtomicReference<TaskRef<Object>> b = new AtomicReference<>();
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            a.set(builder.register(RunnerTestSupport.task("a")
                    .body(context -> "a").build()));
            b.set(builder.register(RunnerTestSupport.task("b").after("a")
                    .body(context -> "b").build()));
            builder.defaults("a", "b");
        });
        ExecutionPlan plan = RunnerTestSupport.plan(definition);
        ResultStore store = new ResultStore(definition, plan);
        store.start(a.get(), T0, 0);
        store.complete(a.get(), TaskCompletion.succeeded("a"), T1, 1_000_000L);

        ResultAccessException exception = assertThrows(ResultAccessException.class,
                () -> store.require(b.get(), a.get()));
        assertEquals("UNDECLARED_RESULT_DEPENDENCY", exception.code());
    }

    @Test
    void skippedAndSuccessfulWithoutValueProduceEmptyOptional() {
        Fixture fixture = fixture();
        ResultStore store = fixture.store();
        TaskRef<Object> a = fixture.a();

        store.start(a, T0, 0);
        store.complete(a, TaskCompletion.skipped("PREDICATE_FALSE"), T1, 1_000_000L);

        assertEquals(Optional.empty(), store.optional(fixture.b(), a));
        ResultAccessException exception = assertThrows(ResultAccessException.class,
                () -> store.require(fixture.b(), a));
        assertEquals("RESULT_NOT_PRODUCED", exception.code());
    }

    @Test
    void successWithoutValueProducesEmptyOptionalButNoDirectResult() {
        Fixture fixture = fixture();
        ResultStore store = fixture.store();
        TaskRef<Object> a = fixture.a();
        store.start(a, T0, 0);
        store.complete(a, TaskCompletion.succeeded(null), T1, 1_000_000L);

        assertEquals(TaskStatus.SUCCEEDED, store.status(a));
        assertEquals(ResultSlot.NoValue.INSTANCE, store.slot(a));
        assertEquals(Optional.empty(), store.optional(fixture.b(), a));
        assertThrows(ResultAccessException.class, () -> store.require(fixture.b(), a));
    }

    @Test
    void failedAndCancelledResultsAreErrorsEvenForOptionalAccess() {
        Fixture failed = fixture();
        ResultStore failedStore = failed.store();
        failedStore.start(failed.a(), T0, 0);
        failedStore.complete(failed.a(),
                TaskCompletion.failed(new IllegalStateException("boom")), T1, 1_000_000L);
        ResultAccessException failedError = assertThrows(ResultAccessException.class,
                () -> failedStore.optional(failed.b(), failed.a()));
        assertEquals("RESULT_NOT_AVAILABLE", failedError.code());

        Fixture cancelled = fixture();
        ResultStore cancelledStore = cancelled.store();
        cancelledStore.start(cancelled.a(), T0, 0);
        cancelledStore.complete(cancelled.a(),
                TaskCompletion.cancelled(ch.so.agi.gretl.runtime.api.CancelReason.REQUESTED, List.of()), T1, 1_000_000L);
        ResultAccessException cancelError = assertThrows(ResultAccessException.class,
                () -> cancelledStore.optional(cancelled.b(), cancelled.a()));
        assertEquals("RESULT_NOT_AVAILABLE", cancelError.code());
    }

    @Test
    void pendingAndRunningResultsAreNotAvailable() {
        Fixture fixture = fixture();
        ResultStore store = fixture.store();

        ResultAccessException pending = assertThrows(ResultAccessException.class,
                () -> store.optional(fixture.b(), fixture.a()));
        assertEquals("RESULT_NOT_AVAILABLE", pending.code());

        store.start(fixture.a(), T0, 0);
        ResultAccessException running = assertThrows(ResultAccessException.class,
                () -> store.optional(fixture.b(), fixture.a()));
        assertEquals("RESULT_NOT_AVAILABLE", running.code());
    }

    @Test
    void unknownAndForeignReferencesAreRejected() {
        Fixture fixture = fixture();
        ResultStore store = fixture.store();
        store.start(fixture.a(), T0, 0);
        store.complete(fixture.a(), TaskCompletion.succeeded("value"), T1, 1_000_000L);

        TaskRef<Object> foreign = new TaskRef<>() {
            @Override
            public String id() {
                return "a";
            }

            @Override
            public Class<Object> resultType() {
                return Object.class;
            }
        };
        ResultAccessException foreignError = assertThrows(ResultAccessException.class,
                () -> store.require(fixture.b(), foreign));
        assertEquals("FOREIGN_OR_UNKNOWN_TASK_REF", foreignError.code());

        TaskRef<Object> unknown = new JobTaskRef<>(
                java.util.UUID.randomUUID(), "a", Object.class, 0);
        ResultAccessException unknownError = assertThrows(ResultAccessException.class,
                () -> store.require(fixture.b(), unknown));
        assertEquals("FOREIGN_OR_UNKNOWN_TASK_REF", unknownError.code());
    }

    @Test
    void resultTypeMismatchIsReportedAtTheConsumer() {
        Fixture fixture = fixture();
        ResultStore store = fixture.store();
        TaskRef<Object> a = fixture.a();
        store.start(a, T0, 0);
        store.complete(a, TaskCompletion.succeeded("text"), T1, 1_000_000L);

        JobTaskRef<Long> wrongType = new JobTaskRef<>(
                fixture.definition().definitionId(), "a", Long.class, 0);
        ResultAccessException exception = assertThrows(ResultAccessException.class,
                () -> store.require(fixture.b(), wrongType));
        assertEquals("RESULT_TYPE_MISMATCH", exception.code());
    }

    @Test
    void undeclaredAndUnselectedProducersAreRejected() {
        Fixture fixture = fixture();
        ResultStore store = fixture.store();
        store.start(fixture.a(), T0, 0);
        store.complete(fixture.a(), TaskCompletion.succeeded("value"), T1, 1_000_000L);

        ResultAccessException exception = assertThrows(ResultAccessException.class,
                () -> store.require(fixture.b(), fixture.c()));
        assertEquals("UNDECLARED_RESULT_DEPENDENCY", exception.code());
    }

    @Test
    void notRunOutcomeHasNoTimesOrValue() {
        Fixture fixture = fixture();
        ResultStore store = fixture.store();
        store.start(fixture.a(), T0, 0);
        store.complete(fixture.a(), TaskCompletion.succeeded("value"), T1, 1_000_000L);
        store.complete(fixture.b(), TaskCompletion.notRun("FAIL_FAST"), T1, 2_000_000L);

        TaskOutcome outcome = store.outcome(fixture.b());
        assertEquals(TaskStatus.NOT_RUN, outcome.status());
        assertTrue(outcome.startedAt().isEmpty());
        assertTrue(outcome.finishedAt().isEmpty());
        assertEquals(0, outcome.durationMillis());
        assertEquals(ResultSlot.NoValue.INSTANCE, outcome.slot());
        assertEquals("FAIL_FAST", outcome.reason());
    }

    @Test
    void doubleCompletionAndLateStartAreInternalErrors() {
        Fixture fixture = fixture();
        ResultStore store = fixture.store();
        store.start(fixture.a(), T0, 0);
        store.complete(fixture.a(), TaskCompletion.succeeded("value"), T1, 1_000_000L);

        assertThrows(IllegalStateException.class, () -> store.start(fixture.a(), T1, 1_000_000L));
        assertThrows(IllegalStateException.class,
                () -> store.complete(fixture.a(), TaskCompletion.succeeded("again"), T1, 1_000_000L));
    }

    private Fixture fixture() {
        AtomicReference<TaskRef<Object>> a = new AtomicReference<>();
        AtomicReference<TaskRef<Object>> b = new AtomicReference<>();
        AtomicReference<TaskRef<Object>> c = new AtomicReference<>();
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            a.set(builder.register(RunnerTestSupport.task("a")
                    .body(context -> "a").build()));
            b.set(builder.register(RunnerTestSupport.task("b").dependsOn("a")
                    .body(context -> "b").build()));
            c.set(builder.register(RunnerTestSupport.task("c")
                    .body(context -> "c").build()));
            builder.defaults("b");
        });
        ExecutionPlan plan = RunnerTestSupport.plan(definition);
        return new Fixture(definition, plan, a.get(), b.get(), c.get());
    }

    private record Fixture(
            JobDefinition definition, ExecutionPlan plan,
            TaskRef<Object> a, TaskRef<Object> b, TaskRef<Object> c) {
        ResultStore store() {
            return new ResultStore(definition, plan);
        }
    }
}
