package ch.so.agi.gretl.runtime.runner;

/**
 * Value presence of a task result, independent of task status. There is no
 * {@code Optional} around the whole state: PENDING/NoValue and SUCCEEDED/NoValue
 * must remain distinguishable.
 */
public sealed interface ResultSlot {
    record Value(Object value) implements ResultSlot {
        public Value {
            java.util.Objects.requireNonNull(value, "value");
        }
    }

    enum NoValue implements ResultSlot {
        INSTANCE
    }
}
