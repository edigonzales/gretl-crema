package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.ExecutionIdentity;
import ch.so.agi.gretl.runtime.api.LogLevel;
import ch.so.agi.gretl.runtime.api.Progress;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared run services used by all context implementations. */
final class RunEnvironment {
    private final RunInput input;
    private final JobDefinition definition;
    private final ExecutionPlan plan;
    private final WorkspaceManager.Workspace workspace;
    private final List<ch.so.agi.gretl.runtime.api.Diagnostic> diagnostics = new ArrayList<>();
    private final AtomicInteger invocationCounter = new AtomicInteger();
    private boolean diagnosticsTruncated;
    private boolean open = true;
    private boolean scopeDegraded;

    RunEnvironment(RunInput input, JobDefinition definition, ExecutionPlan plan,
                   WorkspaceManager.Workspace workspace) {
        this.input = Objects.requireNonNull(input, "input");
        this.definition = Objects.requireNonNull(definition, "definition");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
    }

    RunInput input() {
        return input;
    }

    JobDefinition definition() {
        return definition;
    }

    ExecutionPlan plan() {
        return plan;
    }

    WorkspaceManager.Workspace workspace() {
        return Objects.requireNonNull(workspace, "run workspace unavailable");
    }

    UUID runId() {
        return input.runId();
    }

    String jobName() {
        return input.jobName();
    }

    java.util.Locale locale() {
        return input.locale();
    }

    ResolvedParameters parameters() {
        return input.parameters();
    }

    Map<String, Map<String, Object>> taskConfig() {
        return input.taskConfig();
    }

    java.nio.file.Path jobRoot() {
        return input.jobRoot();
    }

    java.nio.file.Path startCwd() {
        return input.startCwd();
    }

    RunControl control() {
        return input.runControl();
    }

    SecretRegistry secretRegistry() {
        return input.secretRegistry();
    }

    Clock clock() {
        return input.clock();
    }

    MonotonicClock monotonicClock() {
        return input.monotonicClock();
    }

    boolean isOpen() {
        return open;
    }

    void close() {
        open = false;
    }

    ExecutionIdentity identity(String taskId, String invocationId) {
        return new ExecutionIdentity(runId(), jobName(),
                Optional.ofNullable(taskId), Optional.ofNullable(invocationId));
    }

    RunEventSink.Scope openScope(ExecutionIdentity identity, Optional<String> operationId) {
        try {
            RunEventSink.Scope delegate = Objects.requireNonNull(input.eventSink().openScope(identity, operationId));
            return new RunEventSink.Scope() {
                private final Thread owner = Thread.currentThread();
                private boolean closed;
                @Override public void close() {
                    if (closed) return;
                    if (Thread.currentThread() != owner) { scopeFailed(); return; }
                    closed = true;
                    try { delegate.close(); }
                    catch (RuntimeException failure) { scopeFailed(); }
                }
            };
        } catch (RuntimeException failure) {
            scopeFailed();
            return RunEventSink.Scope.NO_OP;
        }
    }

    private void scopeFailed() {
        if (scopeDegraded) return;
        scopeDegraded = true;
        emit("gretl.logging.degraded", LogLevel.WARN, identity(null, null), Optional.empty(),
                "logging", "", 0, "Logging-Scope konnte nicht sicher gebunden oder geschlossen werden.",
                Map.of("logging.scope", "failed"), Optional.empty());
    }

    String nextInvocationId() {
        return "i%06d".formatted(invocationCounter.incrementAndGet());
    }

    ContextLogger logger(ExecutionIdentity identity, Optional<String> operationId,
                         Map<String, String> baseAttributes, java.util.function.BooleanSupplier closed) {
        return new ContextLogger(this, identity, operationId, baseAttributes, closed);
    }

    void report(ch.so.agi.gretl.runtime.api.Diagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        if (diagnostics.size() >= 1000) {
            diagnosticsTruncated = true;
            return;
        }
        diagnostics.add(diagnostic);
    }

    List<ch.so.agi.gretl.runtime.api.Diagnostic> diagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    boolean diagnosticsTruncated() {
        return diagnosticsTruncated;
    }

    void progress(ExecutionIdentity identity, Progress progress) {
        emit("gretl.operation.progress", LogLevel.INFO, identity, Optional.empty(),
                null, null, 0, Map.of(
                        "progress.phase", progress.phase(),
                        "progress.completed", Long.toString(progress.completed()),
                        "progress.unit", progress.unit()), Optional.empty());
    }

    void emit(String eventName, LogLevel level, ExecutionIdentity identity,
              Optional<String> operationId, String phase, String outcome,
              long durationMillis, Map<String, String> attributes,
              Optional<FailureInfo> failure) {
        emit(eventName, level, identity, operationId, phase, outcome,
                durationMillis, "", attributes, failure);
    }

    void emit(String eventName, LogLevel level, ExecutionIdentity identity,
              Optional<String> operationId, String phase, String outcome,
              long durationMillis, String message, Map<String, String> attributes,
              Optional<FailureInfo> failure) {
        RunEvent event = new RunEvent(eventName, message, clock().instant(), level,
                Optional.of(identity.runId()), Optional.of(identity.jobName()),
                identity.taskId(), identity.invocationId(), operationId,
                phase, outcome, durationMillis, attributes, failure);
        try {
            input.eventSink().emit(event);
        } catch (RuntimeException ignored) {
            // M1 keeps telemetry non-controlling; M3 adds explicit degradation reporting.
        }
    }

    Instant now() {
        return clock().instant();
    }
}
