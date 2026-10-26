package ch.so.agi.gretl.runtime.api;

/**
 * Opaque reference to the possible result of one task in the current job
 * definition. Only references issued by that definition are accepted by the
 * runtime.
 *
 * @param <R> declared result type
 */
public interface TaskRef<R> {
    String id();
    Class<R> resultType();
}
