package ch.so.agi.gretl.runtime.api;

/**
 * One explicitly offered result field of an operation.
 *
 * <p>{@code read} is invoked lazily on actual consumer access, must be read-only
 * and must not trigger new domain work.</p>
 *
 * @param <R> operation result type
 * @param <V> field value type
 */
public record OutputField<R, V>(
    String name, String valueType, String description, boolean nullable,
    java.util.function.Function<R, V> read
) {
    public OutputField {
        name = ApiChecks.text(name, "name");
        valueType = ApiChecks.text(valueType, "valueType");
        description = ApiChecks.text(description, "description");
        java.util.Objects.requireNonNull(read, "read");
    }
}
