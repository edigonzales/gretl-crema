package ch.so.agi.gretl.tasks.database;

import java.util.List;

/** Result of one SqlExecutor invocation. */
public record SqlResult(int executionCount, List<SqlExecutionResult> executions) {
    public SqlResult {
        if (executionCount < 0) {
            throw new IllegalArgumentException("executionCount must not be negative");
        }
        executions = List.copyOf(executions);
        if (executionCount != executions.size()) {
            throw new IllegalArgumentException("executionCount does not match executions");
        }
    }
}
