package ch.so.agi.gretl.runtime.api;

/**
 * Scope that owns registered closeable resources. Resources are closed once in
 * reverse registration order. Registering the same object identity twice in one
 * scope is an {@link IllegalArgumentException}.
 */
public interface ResourceScope {
    <T extends AutoCloseable> T manage(T resource);
}
