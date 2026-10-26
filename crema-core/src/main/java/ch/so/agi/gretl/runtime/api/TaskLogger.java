package ch.so.agi.gretl.runtime.api;

/**
 * JDK-only task logger contract. The runtime adds run/task/invocation context;
 * tasks supply only safe structured fields and an optional cause.
 *
 * <p>All arguments are non-null except {@code cause}. Logging is not a control
 * channel: an invalid API argument is a programming error and may fail
 * immediately, while sink I/O failures are handled as logging degradation.</p>
 */
public interface TaskLogger {
    boolean isEnabled(LogLevel level);

    void log(LogLevel level, String eventName, String message,
             java.util.Map<String, String> fields, Throwable cause);

    default void info(String message) {
        log(LogLevel.INFO, "gretl.task.message", message, java.util.Map.of(), null);
    }

    default void debug(String message) {
        log(LogLevel.DEBUG, "gretl.task.message", message, java.util.Map.of(), null);
    }

    default void warn(String message) {
        log(LogLevel.WARN, "gretl.task.message", message, java.util.Map.of(), null);
    }

    default void error(String message, Throwable cause) {
        log(LogLevel.ERROR, "gretl.task.message", message, java.util.Map.of(), cause);
    }
}
