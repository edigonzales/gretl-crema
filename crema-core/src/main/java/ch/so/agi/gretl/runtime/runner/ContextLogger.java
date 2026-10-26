package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.ExecutionIdentity;
import ch.so.agi.gretl.runtime.api.LogLevel;
import ch.so.agi.gretl.runtime.api.TaskLogger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Context-bound TaskLogger. After its scope closes it emits at most a
 * {@code gretl.late_log} event with the old identity and never changes status.
 */
final class ContextLogger implements TaskLogger {
    private final RunEnvironment environment;
    private final ExecutionIdentity identity;
    private final Optional<String> operationId;
    private final Map<String, String> baseAttributes;
    private final BooleanSupplier closed;

    ContextLogger(RunEnvironment environment, ExecutionIdentity identity,
                  Optional<String> operationId, Map<String, String> baseAttributes,
                  BooleanSupplier closed) {
        this.environment = environment;
        this.identity = identity;
        this.operationId = operationId;
        this.baseAttributes = Map.copyOf(baseAttributes);
        this.closed = closed;
    }

    @Override
    public boolean isEnabled(LogLevel level) {
        return true;
    }

    @Override
    public void log(LogLevel level, String eventName, String message,
                    Map<String, String> fields, Throwable cause) {
        java.util.Objects.requireNonNull(level, "level");
        if (eventName == null || eventName.isBlank()) {
            throw new IllegalArgumentException("eventName must not be blank");
        }
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        java.util.Objects.requireNonNull(fields, "fields");
        Map<String, String> attributes = new LinkedHashMap<>(baseAttributes);
        attributes.putAll(fields);
        String event = eventName;
        if (closed.getAsBoolean()) {
            attributes.put("original.event_name", eventName);
            event = "gretl.late_log";
        }
        environment.emit(event, level, identity, operationId, null, null, 0,
                message, attributes, Optional.ofNullable(cause).map(FailureInfo::from));
    }
}
