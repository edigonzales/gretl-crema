package ch.so.agi.gretl.runtime.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.so.agi.gretl.runtime.api.CancelReason;
import ch.so.agi.gretl.runtime.api.TaskCancelledException;
import ch.so.agi.gretl.runtime.api.TimeBudget;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RunControlTest {

    @Test
    void firstCancelReasonWinsAndIsNeverCleared() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RunControl control = new RunControl(clock, Optional.empty());
        assertFalse(control.isCancellationRequested());

        control.requestCancel(CancelReason.REQUESTED);
        control.requestCancel(CancelReason.TIMEOUT);
        assertTrue(control.isCancellationRequested());
        assertEquals(Optional.of(CancelReason.REQUESTED), control.reason());
        assertThrows(TaskCancelledException.class, control::checkBeforeWork);
    }

    @Test
    void finishRequestIsStoredIndependentlyAndThrowsFinishSignal() {
        RunControl control = new RunControl(new FakeMonotonicClock(), Optional.empty());
        control.requestFinish("empty source");
        assertTrue(control.isFinishRequested());
        assertEquals(Optional.of("empty source"), control.finishReason());
        FinishSignal signal = assertThrows(FinishSignal.class, control::checkBeforeWork);
        assertEquals("empty source", signal.reason());
    }

    @Test
    void timeoutUsesMonotonicTicksAndBecomesControlledCancellation() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RunControl control = new RunControl(clock, Optional.of(Duration.ofSeconds(1)));
        assertEquals(Duration.ofSeconds(1), control.remaining().orElseThrow());
        assertFalse(control.expired());

        clock.advance(Duration.ofSeconds(1));
        assertTrue(control.expired());
        assertEquals(Duration.ZERO, control.remaining().orElseThrow());
        TaskCancelledException exception = assertThrows(TaskCancelledException.class,
                control::checkBeforeWork);
        assertEquals(CancelReason.TIMEOUT, exception.reason());
        assertEquals(Optional.of(CancelReason.TIMEOUT), control.reason());
    }

    @Test
    void childBudgetIsBoundedByParentAndLocalLimit() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RunControl control = new RunControl(clock, Optional.of(Duration.ofSeconds(10)));
        TimeBudget child = control.childBudget(Duration.ofSeconds(2));
        assertEquals(Duration.ofSeconds(2), child.remaining().orElseThrow());

        clock.advance(Duration.ofSeconds(2));
        assertTrue(child.expired());
        assertFalse(control.expired());
    }

    @Test
    void tokenExposesCancellationReason() {
        RunControl control = new RunControl(new FakeMonotonicClock(), Optional.empty());
        control.requestCancel(CancelReason.INTERRUPTED);
        assertTrue(control.isCancellationRequested());
        assertEquals(Optional.of(CancelReason.INTERRUPTED), control.reason());
        TaskCancelledException exception = assertThrows(TaskCancelledException.class,
                control::throwIfCancellationRequested);
        assertEquals(CancelReason.INTERRUPTED, exception.reason());
    }

    @Test
    void cancellationTakesPrecedenceOverAConcurrentFinishRequest() {
        RunControl control = new RunControl(new FakeMonotonicClock(), Optional.empty());
        control.requestFinish("finish");
        control.requestCancel(CancelReason.REQUESTED);
        TaskCancelledException exception = assertThrows(TaskCancelledException.class,
                control::checkBeforeWork);
        assertEquals(CancelReason.REQUESTED, exception.reason());
    }

    @Test
    void completionUsesOneIndependentThirtySecondBudget() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RunControl control = new RunControl(clock, Optional.of(Duration.ofSeconds(1)));
        clock.advance(Duration.ofSeconds(2));
        var completion = control.completionBudget();
        assertEquals(Duration.ofSeconds(30), completion.remaining().orElseThrow());
        clock.advance(Duration.ofSeconds(12));
        assertEquals(Duration.ofSeconds(18), control.completionBudget().remaining().orElseThrow());
        clock.advance(Duration.ofSeconds(18));
        assertTrue(completion.expired());
    }

    @Test
    void noTimeoutMeansUnlimitedBudget() {
        RunControl control = new RunControl(new FakeMonotonicClock(), Optional.empty());
        assertTrue(control.remaining().isEmpty());
        assertFalse(control.expired());
    }
}
