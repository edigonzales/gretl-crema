package ch.so.agi.gretl.runtime.runner;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic monotonic clock for budgets and duration assertions. */
final class FakeMonotonicClock implements MonotonicClock {
    private final AtomicLong ticks = new AtomicLong();

    @Override
    public long ticks() {
        return ticks.get();
    }

    void advance(Duration duration) {
        ticks.addAndGet(duration.toNanos());
    }

    void set(long value) {
        ticks.set(value);
    }
}
