package ch.so.agi.gretl.tasks.database;

import java.util.List;

/** Result of one ordered script or prepared-statement entry. */
public record SqlExecutionResult(int index, List<Long> updateCounts, int resultSetCount) {
    public SqlExecutionResult {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative");
        }
        updateCounts = List.copyOf(updateCounts);
        if (resultSetCount < 0) {
            throw new IllegalArgumentException("resultSetCount must not be negative");
        }
    }
}
