package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.CancelReason;
import ch.so.agi.gretl.runtime.api.CancellationToken;
import ch.so.agi.gretl.runtime.api.TaskCancelledException;
import ch.so.agi.gretl.runtime.api.TimeBudget;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Run-scoped finish request, cooperative cancellation and monotonic time
 * budget. The first cancellation reason is stable; a finish request is stored
 * independently so catching the internal signal cannot resume work.
 */
public final class RunControl implements TimeBudget, CancellationToken {
    private final MonotonicClock clock;
    private final OptionalLong deadlineTicks;
    private volatile CancelReason cancelReason;
    private volatile boolean cancellationObserved;
    private volatile boolean interrupted;
    private String finishReason;
    private TimeBudget completionBudget;

    /** One shared grace period for cooperative cleanup, report and flush. */
    public synchronized TimeBudget completionBudget() {
        if (completionBudget == null) {
            completionBudget = new RunControl(clock, Optional.of(Duration.ofSeconds(30)));
        }
        return completionBudget;
    }

    public RunControl(MonotonicClock clock, Optional<Duration> timeout) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (timeout == null || timeout.isEmpty()) {
            this.deadlineTicks = OptionalLong.empty();
        } else {
            Duration duration = timeout.orElseThrow();
            if (duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            long now = clock.ticks();
            long nanos;
            try {
                nanos = duration.toNanos();
            } catch (ArithmeticException overflow) {
                nanos = Long.MAX_VALUE;
            }
            long deadline;
            try {
                deadline = Math.addExact(now, nanos);
            } catch (ArithmeticException overflow) {
                deadline = Long.MAX_VALUE;
            }
            this.deadlineTicks = OptionalLong.of(deadline);
        }
    }

    public synchronized void requestCancel(CancelReason reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason == CancelReason.INTERRUPTED) {
            interrupted = true;
        }
        if (cancelReason == null) {
            cancelReason = reason;
        }
    }

    public void requestFinish(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("finish reason must not be blank");
        }
        if (finishReason == null) {
            finishReason = reason;
        }
    }

    public boolean isFinishRequested() {
        return finishReason != null;
    }

    public Optional<String> finishReason() {
        return Optional.ofNullable(finishReason);
    }

    /** Blocks any new normal work after finish, cancellation or budget expiry. */
    public void checkBeforeWork() {
        throwIfCancellationRequested();
        if (finishReason != null) {
            throw new FinishSignal(finishReason);
        }
    }

    public ControlSnapshot snapshot() {
        return new ControlSnapshot(Optional.ofNullable(cancelReason),
                finishReason != null, Optional.ofNullable(finishReason), expired());
    }

    @Override
    public boolean isCancellationRequested() {
        return reason().isPresent();
    }

    @Override
    public Optional<CancelReason> reason() {
        if (cancelReason != null) return Optional.of(cancelReason);
        if (expired()) return Optional.of(CancelReason.TIMEOUT);
        return Thread.currentThread().isInterrupted() ? Optional.of(CancelReason.INTERRUPTED) : Optional.empty();
    }

    /** A boundary observes the inherited task budget as well as run control. */
    void checkBeforeWork(TimeBudget budget) {
        checkCancellation(budget);
        if (finishReason != null) {
            throw new FinishSignal(finishReason);
        }
    }

    void checkCancellation(TimeBudget budget) {
        if (budget.expired()) {
            observeCancel(CancelReason.TIMEOUT);
        }
        throwIfCancellationRequested();
    }

    /** Reads only previously observed cancellation, without observing a later deadline. */
    boolean cancellationObserved() {
        return cancellationObserved;
    }

    void observeCancel(CancelReason reason) {
        requestCancel(reason);
        cancellationObserved = true;
    }

    boolean interrupted() {
        return interrupted;
    }

    @Override
    public void throwIfCancellationRequested() throws TaskCancelledException {
        if (cancelReason != null) {
            cancellationObserved = true;
            throw new TaskCancelledException(cancelReason);
        }
        if (expired()) {
            observeCancel(CancelReason.TIMEOUT);
            throw new TaskCancelledException(CancelReason.TIMEOUT);
        }
        if (Thread.currentThread().isInterrupted()) {
            Thread.interrupted();
            observeCancel(CancelReason.INTERRUPTED);
            throw new TaskCancelledException(CancelReason.INTERRUPTED);
        }
    }

    @Override
    public Optional<Duration> remaining() {
        if (deadlineTicks.isEmpty()) {
            return Optional.empty();
        }
        long remaining = Math.max(0L, deadlineTicks.getAsLong() - clock.ticks());
        return Optional.of(Duration.ofNanos(remaining));
    }

    @Override
    public boolean expired() {
        return deadlineTicks.isPresent() && clock.ticks() >= deadlineTicks.getAsLong();
    }

    /** Child budget limited by both the remaining run budget and a local limit. */
    public TimeBudget childBudget(Duration localLimit) {
        Objects.requireNonNull(localLimit, "localLimit");
        if (localLimit.isZero() || localLimit.isNegative()) {
            throw new IllegalArgumentException("local limit must be positive");
        }
        long start = clock.ticks();
        long localNanos;
        try {
            localNanos = localLimit.toNanos();
        } catch (ArithmeticException overflow) {
            localNanos = Long.MAX_VALUE;
        }
        long localDeadline;
        try {
            localDeadline = Math.addExact(start, localNanos);
        } catch (ArithmeticException overflow) {
            localDeadline = Long.MAX_VALUE;
        }
        long finalLocalDeadline = localDeadline;
        return new TimeBudget() {
            @Override
            public Optional<Duration> remaining() {
                Optional<Duration> parent = RunControl.this.remaining();
                long localRemaining = Math.max(0L, finalLocalDeadline - clock.ticks());
                Duration local = Duration.ofNanos(localRemaining);
                if (parent.isEmpty()) {
                    return Optional.of(local);
                }
                return Optional.of(parent.get().compareTo(local) <= 0 ? parent.get() : local);
            }

            @Override
            public boolean expired() {
                return localRemainingExpired() || RunControl.this.expired();
            }

            private boolean localRemainingExpired() {
                return clock.ticks() >= finalLocalDeadline;
            }
        };
    }
}
