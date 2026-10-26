package ch.so.agi.gretl.runtime.runner;

/** Non-controlling sink for lifecycle and task log events. */
@FunctionalInterface
public interface RunEventSink {
    void emit(RunEvent event);

    default Scope openScope(ch.so.agi.gretl.runtime.api.ExecutionIdentity identity,
                            java.util.Optional<String> operationId) {
        return Scope.NO_OP;
    }

    @FunctionalInterface
    interface Scope extends AutoCloseable {
        Scope NO_OP = () -> { };
        @Override void close();
    }

    RunEventSink NO_OP = event -> {
    };
}
