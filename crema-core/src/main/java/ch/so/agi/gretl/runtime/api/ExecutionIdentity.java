package ch.so.agi.gretl.runtime.api;

/**
 * Correlation identity of a run, task or invocation.
 *
 * <p>{@code runId} is created per actual run. {@code taskId} names a public DAG
 * node. {@code invocationId} is a new run-local id for each operation call and
 * is only present together with a task id.</p>
 */
public record ExecutionIdentity(
    java.util.UUID runId, String jobName,
    java.util.Optional<String> taskId, java.util.Optional<String> invocationId
) {
    public ExecutionIdentity {
        java.util.Objects.requireNonNull(runId, "runId");
        jobName = ApiChecks.text(jobName, "jobName");
        java.util.Objects.requireNonNull(taskId, "taskId");
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        taskId.ifPresent(v -> ApiChecks.text(v, "taskId"));
        invocationId.ifPresent(v -> ApiChecks.text(v, "invocationId"));
        if (invocationId.isPresent() && taskId.isEmpty())
            throw new IllegalArgumentException("Invocation requires a task");
    }
}
