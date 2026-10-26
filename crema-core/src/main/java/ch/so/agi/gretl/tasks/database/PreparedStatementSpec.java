package ch.so.agi.gretl.tasks.database;

import java.util.List;

/** Small explicit SQL statement value contract. */
public record PreparedStatementSpec(String sql, List<Object> parameters) {
    public PreparedStatementSpec {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("sql must not be blank");
        }
        parameters = java.util.Collections.unmodifiableList(
                new java.util.ArrayList<>(parameters));
    }
}
