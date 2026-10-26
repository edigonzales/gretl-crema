package ch.so.agi.gretl.runtime.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FailureArbiterTest {
    private final FailureArbiter arbiter = new FailureArbiter();
    private final ControlSnapshot noControl = ControlSnapshot.empty();

    @Test
    void valueIsSuccessAndNullIsSuccessWithoutValue() {
        TaskCompletion value = arbiter.resolve(
                new FailureArbiter.WorkCompletion.Value("x"), noControl, List.of());
        assertEquals(TaskStatus.SUCCEEDED, value.status());
        assertEquals(new ResultSlot.Value("x"), value.slot());

        TaskCompletion nullValue = arbiter.resolve(
                new FailureArbiter.WorkCompletion.Value(null), noControl, List.of());
        assertEquals(TaskStatus.SUCCEEDED, nullValue.status());
        assertEquals(ResultSlot.NoValue.INSTANCE, nullValue.slot());
    }

    @Test
    void unhandledFailureKeepsItsPrimaryCauseAndAddsCloseErrors() {
        IOException close = new IOException("close");
        TaskCompletion completion = arbiter.resolve(
                new FailureArbiter.WorkCompletion.Failure(
                        new IllegalStateException("work")), noControl, List.of(close));
        assertEquals(TaskStatus.FAILED, completion.status());
        assertEquals(IllegalStateException.class.getName(), completion.primaryFailure().type());
        assertEquals(1, completion.secondaryFailures().size());
        assertEquals(IOException.class.getName(), completion.secondaryFailures().get(0).type());
    }

    @Test
    void closeErrorAfterValueBecomesPrimaryAndPreventsSuccess() {
        IOException first = new IOException("first");
        IllegalStateException second = new IllegalStateException("second");
        TaskCompletion completion = arbiter.resolve(
                new FailureArbiter.WorkCompletion.Value("x"), noControl,
                List.of(first, second));
        assertEquals(TaskStatus.FAILED, completion.status());
        assertEquals(IOException.class.getName(), completion.primaryFailure().type());
        assertEquals(1, completion.secondaryFailures().size());
        assertEquals(IllegalStateException.class.getName(),
                completion.secondaryFailures().get(0).type());
    }

    @Test
    void finishIsSkippedAndCloseErrorOverridesIt() {
        ControlSnapshot finish = new ControlSnapshot(Optional.empty(), true,
                Optional.of("no data"), false);
        TaskCompletion skipped = arbiter.resolve(
                new FailureArbiter.WorkCompletion.Finished("no data"), finish, List.of());
        assertEquals(TaskStatus.SKIPPED, skipped.status());
        assertEquals("no data", skipped.reason());

        TaskCompletion failed = arbiter.resolve(
                new FailureArbiter.WorkCompletion.Finished("no data"), finish,
                List.of(new IOException("close")));
        assertEquals(TaskStatus.FAILED, failed.status());
        assertEquals(IOException.class.getName(), failed.primaryFailure().type());
    }

    @Test
    void valueAfterCaughtFinishSignalStaysSkipped() {
        ControlSnapshot finish = new ControlSnapshot(Optional.empty(), true,
                Optional.of("no data"), false);
        TaskCompletion completion = arbiter.resolve(
                new FailureArbiter.WorkCompletion.Value("leaked"), finish, List.of());
        assertEquals(TaskStatus.SKIPPED, completion.status());
        assertEquals("no data", completion.reason());
        assertEquals(ResultSlot.NoValue.INSTANCE, completion.slot());
    }

    @Test
    void cancellationIsSecondaryToARealFailureButCancelsNormalValue() {
        ControlSnapshot cancelled = new ControlSnapshot(
                Optional.of(ch.so.agi.gretl.runtime.api.CancelReason.REQUESTED),
                false, Optional.empty(), false);
        TaskCompletion failed = arbiter.resolve(
                new FailureArbiter.WorkCompletion.Failure(new IllegalStateException("work")),
                cancelled, List.of());
        assertEquals(TaskStatus.FAILED, failed.status());

        TaskCompletion cancelledValue = arbiter.resolve(
                new FailureArbiter.WorkCompletion.Cancelled(), cancelled, List.of());
        assertEquals(TaskStatus.CANCELLED, cancelledValue.status());
        assertEquals(ResultSlot.NoValue.INSTANCE, cancelledValue.slot());
        assertNull(cancelledValue.primaryFailure());
    }

    @Test
    void cancellationAfterFinishWinsOverNoData() {
        ControlSnapshot both = new ControlSnapshot(
                Optional.of(ch.so.agi.gretl.runtime.api.CancelReason.REQUESTED),
                true, Optional.of("finish"), false);
        TaskCompletion completion = arbiter.resolve(
                new FailureArbiter.WorkCompletion.Finished("finish"), both,
                List.of(new ch.so.agi.gretl.runtime.api.TaskCancelledException(
                                ch.so.agi.gretl.runtime.api.CancelReason.REQUESTED),
                        new IOException("close")));
        assertEquals(TaskStatus.CANCELLED, completion.status());
        assertEquals(1, completion.secondaryFailures().size());
    }

    @Test
    void explicitSkipAndCloseErrorFollowTheSameRule() {
        TaskCompletion skipped = arbiter.resolve(
                new FailureArbiter.WorkCompletion.Skipped("PREDICATE_FALSE"),
                noControl, List.of());
        assertEquals(TaskStatus.SKIPPED, skipped.status());
        assertEquals("PREDICATE_FALSE", skipped.reason());

        TaskCompletion failed = arbiter.resolve(
                new FailureArbiter.WorkCompletion.Skipped("PREDICATE_FALSE"),
                noControl, List.of(new IOException("close")));
        assertEquals(TaskStatus.FAILED, failed.status());
    }

    @Test
    void completionValidatesStatusSlotCombinations() {
        assertTrue(assertThrows(IllegalArgumentException.class, () ->
                new TaskCompletion(TaskStatus.SKIPPED, new ResultSlot.Value("x"),
                        "reason", null, List.of())).getMessage().contains("cannot carry"));
        assertTrue(assertThrows(IllegalArgumentException.class, () ->
                new TaskCompletion(TaskStatus.FAILED, ResultSlot.NoValue.INSTANCE,
                        null, null, List.of())).getMessage().contains("primary failure"));
    }

    private static <T extends Throwable> T assertThrows(
            Class<T> expected, org.junit.jupiter.api.function.Executable executable) {
        return org.junit.jupiter.api.Assertions.assertThrows(expected, executable);
    }
}
