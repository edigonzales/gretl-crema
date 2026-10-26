package ch.so.agi.gretl.runtime.api;

/**
 * Small read-only view shared by task, operation and cleanup contexts.
 *
 * <p>All methods are scoped to the owning context lifetime. After the scope has
 * closed, parameter, config and path access raises {@link ContextAccessException}
 * with code {@code CONTEXT_CLOSED}.</p>
 */
public interface ContextView {
    ExecutionIdentity identity();
    Object parameter(String name);
    <T> T parameter(String name, Class<T> type);
    java.util.Map<String, Object> config(String alias);
    java.nio.file.Path file(String path);
    java.nio.file.Path path(String path);
    java.nio.file.Path workPath(String relativePath) throws java.io.IOException;
    TaskLogger log();
    void report(Diagnostic diagnostic);

    /** Locale for user-facing messages; English is the safe default. */
    default java.util.Locale locale() {
        return java.util.Locale.ENGLISH;
    }
}
