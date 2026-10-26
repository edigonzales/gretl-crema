package ch.so.agi.gretl.tasks.database;

import ch.so.agi.gretl.runtime.api.ConfigBuilder;
import ch.so.agi.gretl.runtime.api.Fields;
import ch.so.agi.gretl.runtime.api.InputField;
import ch.so.agi.gretl.runtime.api.LogLevel;
import ch.so.agi.gretl.i18n.Messages;
import ch.so.agi.gretl.runtime.api.OperationContext;
import ch.so.agi.gretl.runtime.api.OperationType;
import ch.so.agi.gretl.runtime.api.OutputField;
import ch.so.agi.gretl.runtime.api.Progress;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** PostgreSQL-focused bounded SqlExecutor with scripts or prepared statements. */
public final class SqlExecutorOperation {
    public static final String ID = "gretl.sql-executor";
    public static final String SYMBOL = "SqlExecutor";

    private SqlExecutorOperation() {
    }

    public static OperationType<SqlRequest, SqlResult> type() {
        InputField<SqlRequest, String> database = new InputField<>(
                "database", "text", "Alias of a prepared PostgreSQL connection",
                true, Optional.empty(), List.of(), "database = 'edit'",
                SqlExecutorOperation::text, (builder, value) -> ((SqlBuilder) builder).setDatabase(value));
        InputField<SqlRequest, List<Path>> scripts = new InputField<>(
                "scripts", "list", "Ordered complete UTF-8 SQL script files", false,
                Optional.of(List.of()), List.of(),
                "scripts = [file('sql/update.sql')]",
                SqlExecutorOperation::paths, (builder, value) -> ((SqlBuilder) builder).setScripts(value));
        InputField<SqlRequest, List<PreparedStatementSpec>> statements = new InputField<>(
                "statements", "list", "Ordered prepared statements", false,
                Optional.of(List.of()), List.of(),
                "statements = [[sql: 'UPDATE t SET x=?', parameters: [1]]]",
                SqlExecutorOperation::statements, (builder, value) -> ((SqlBuilder) builder).setStatements(value));
        OutputField<SqlResult, Integer> executionCount = Fields.output(
                "executionCount", "integer", "Number of script/statement entries", false,
                SqlResult::executionCount);
        OutputField<SqlResult, List<SqlExecutionResult>> executions = Fields.output(
                "executions", "list", "Results in input order", false,
                SqlResult::executions);
        return new OperationType<>(
                ID, SYMBOL, "Execute SQL scripts or prepared statements",
                SqlRequest.class, SqlResult.class,
                SqlBuilder::new, SqlHandler::new,
                List.of(database, scripts, statements),
                List.of(executionCount, executions),
                """
                task('update', SqlExecutor) {
                    database = 'edit'
                    scripts = [file('sql/update.sql')]
                }
                """);
    }

    private static String text(Object value) {
        if (!(value instanceof CharSequence text)) {
            throw new IllegalArgumentException("Textwert erwartet");
        }
        return text.toString();
    }

    private static List<Path> paths(Object value) {
        List<Object> raw = list(value, "scripts");
        List<Path> paths = new ArrayList<>();
        for (Object element : raw) {
            Path path;
            if (element instanceof Path direct) {
                path = direct;
            } else if (element instanceof CharSequence text) {
                path = Path.of(text.toString());
            } else {
                throw new IllegalArgumentException("scripts contains no path");
            }
            paths.add(path.normalize());
        }
        return List.copyOf(paths);
    }

    private static List<PreparedStatementSpec> statements(Object value) {
        List<Object> raw = list(value, "statements");
        List<PreparedStatementSpec> statements = new ArrayList<>();
        for (Object element : raw) {
            if (!(element instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("statements contains no map");
            }
            Set<String> keys = map.keySet().stream().map(String::valueOf)
                    .collect(java.util.stream.Collectors.toSet());
            if (!keys.equals(Set.of("sql", "parameters"))) {
                throw new IllegalArgumentException(
                        "Statement requires exactly sql and parameters");
            }
            Object sqlValue = map.get("sql");
            if (!(sqlValue instanceof CharSequence sql)) {
                throw new IllegalArgumentException("Statement.sql must be text");
            }
            List<Object> parameters = parameters(map.get("parameters"));
            statements.add(new PreparedStatementSpec(sql.toString(), parameters));
        }
        return List.copyOf(statements);
    }

    private static List<Object> parameters(Object value) {
        List<Object> raw = list(value, "parameters");
        List<Object> parameters = new ArrayList<>();
        for (Object element : raw) {
            if (element == null || element instanceof String || element instanceof Boolean
                    || element instanceof Byte || element instanceof Short
                    || element instanceof Integer || element instanceof Long
                    || element instanceof BigDecimal) {
                parameters.add(element);
            } else if (element instanceof CharSequence text) {
                parameters.add(text.toString());
            } else {
                throw new IllegalArgumentException(
                        "SQL-Parameter typ " + element.getClass().getName()
                                + " is not supported");
            }
        }
        return java.util.Collections.unmodifiableList(new ArrayList<>(parameters));
    }

    private static List<Object> list(Object value, String field) {
        List<Object> result = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(result::add);
        } else if (value instanceof Object[] array) {
            java.util.Collections.addAll(result, array);
        } else {
            throw new IllegalArgumentException(field + " must be a list");
        }
        return result;
    }

    public record SqlRequest(
            String database, List<Path> scripts,
            List<PreparedStatementSpec> statements) {
        public SqlRequest {
            java.util.Objects.requireNonNull(database, "database");
            scripts = List.copyOf(scripts);
            statements = List.copyOf(statements);
        }
    }

    public static final class SqlBuilder implements ConfigBuilder<SqlRequest> {
        private String database;
        private List<Path> scripts = List.of();
        private List<PreparedStatementSpec> statements = List.of();

        public void setDatabase(String value) { database = value; }
        public void setScripts(List<Path> value) { scripts = value; }
        public void setStatements(List<PreparedStatementSpec> value) { statements = value; }

        @Override
        public SqlRequest build() {
            if (database == null || database.isBlank()) {
                throw new IllegalArgumentException("database is missing");
            }
            boolean hasScripts = !scripts.isEmpty();
            boolean hasStatements = !statements.isEmpty();
            if (hasScripts == hasStatements) {
                throw new IllegalArgumentException(
                        "Exactly one of scripts or statements must be non-empty");
            }
            return new SqlRequest(database, scripts, statements);
        }
    }

    static final class SqlHandler
            implements ch.so.agi.gretl.runtime.api.OperationHandler<SqlRequest, SqlResult> {
        @Override
        public SqlResult execute(SqlRequest request, OperationContext context) {
            Map<String, Object> config = context.config(request.database());
            String jdbcUrl = requiredText(config, "jdbcUrl", context);
            String user = optionalText(config, "user", context);
            String password = null;
            Object passwordSecret = config.get("passwordSecret");
            if (passwordSecret != null) {
                if (!(passwordSecret instanceof CharSequence alias)) {
                    throw new SqlTaskException("SQL_CONFIG", msg(context, "task.sql.passwordSecretText"),
                            request.database(), null, 0, "configuration", null, 0, null);
                }
                password = context.sensitive(context.secret(alias.toString()).reveal());
            }
            Set<String> allowed = Set.of("jdbcUrl", "user", "passwordSecret");
            for (String key : config.keySet()) {
                if (!allowed.contains(key)) {
                    throw new SqlTaskException("SQL_CONFIG",
                            msg(context, "task.sql.unknownField", key), request.database(),
                            null, 0, "configuration", null, 0, null);
                }
            }
            long started = System.nanoTime();
            Connection connection = null;
            SqlTaskException primary = null;
            try {
                connection = DriverManager.getConnection(jdbcUrl, user, password);
                connection.setAutoCommit(false);
                List<SqlExecutionResult> results = new ArrayList<>();
                if (!request.scripts().isEmpty()) {
                    for (int index = 0; index < request.scripts().size(); index++) {
                        context.cancellation().throwIfCancellationRequested();
                        results.add(executeScript(connection, request.scripts().get(index),
                                index, request.database(), context));
                        context.progress(new Progress("sql", index + 1,
                                OptionalLong.of(request.scripts().size()), "entries"));
                    }
                } else {
                    for (int index = 0; index < request.statements().size(); index++) {
                        context.cancellation().throwIfCancellationRequested();
                        results.add(executeStatement(connection,
                                request.statements().get(index), index,
                                request.database(), context));
                        context.progress(new Progress("sql", index + 1,
                                OptionalLong.of(request.statements().size()), "entries"));
                    }
                }
                try {
                    connection.commit();
                } catch (SQLException exception) {
                    primary = sqlError("SQL_COMMIT_UNKNOWN",
                            msg(context, "task.sql.commitUnknown"), request.database(), null,
                            results.size(), "commit", exception);
                    rollbackQuietly(connection, primary);
                    throw primary;
                }
                context.log().log(LogLevel.INFO, "gretl.sql.finished",
                        msg(context, "task.sql.finished"),
                        Map.of("db.alias", request.database(),
                                "sql.execution_count", Integer.toString(results.size()),
                                "duration_ms", Long.toString(
                                        (System.nanoTime() - started) / 1_000_000)), null);
                return new SqlResult(results.size(), results);
            } catch (SqlTaskException exception) {
                primary = exception;
                logFailure(context, exception);
                throw exception;
            } catch (SQLException exception) {
                primary = sqlError("SQL_EXECUTION",
                        msg(context, "task.sql.executionFailed"),
                        request.database(), null, 0, "connect", exception);
                rollbackQuietly(connection, primary);
                logFailure(context, primary);
                throw primary;
            } finally {
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (SQLException exception) {
                        if (primary != null) {
                            primary.addSuppressed(exception);
                        } else {
                            throw new SqlTaskException("SQL_CLOSE",
                                    msg(context, "task.sql.closeFailed"),
                                    request.database(), null, 0, "cleanup",
                                    exception.getSQLState(), exception.getErrorCode(), exception);
                        }
                    }
                }
            }
        }

        private static String msg(OperationContext context, String key, Object... args) {
            return Messages.format(context.locale(), key, args);
        }

        private static void logFailure(OperationContext context, SqlTaskException exception) {
            java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
            fields.put("db.alias", exception.databaseAlias());
            if (exception.scriptName() != null) {
                fields.put("sql.script", exception.scriptName());
            }
            fields.put("sql.entry", Integer.toString(exception.entryIndex()));
            fields.put("sql.phase", exception.phase());
            if (exception.sqlState() != null) {
                fields.put("sql.state", exception.sqlState());
            }
            if (exception.vendorCode() != 0) {
                fields.put("sql.vendor_code", Integer.toString(exception.vendorCode()));
            }
            context.log().log(LogLevel.WARN, "gretl.sql.failed",
                    msg(context, "task.sql.executionFailed"), fields, exception);
        }

        private static SqlExecutionResult executeScript(
                Connection connection, Path script, int index, String alias,
                OperationContext context) {
            String sql;
            try {
                sql = Files.readString(script, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new SqlTaskException("SQL_SCRIPT_READ",
                        msg(context, "task.sql.scriptRead"), alias,
                        script.getFileName().toString(), index, "read", null, 0, exception);
            }
            String hash = sha256(sql);
            context.log().log(LogLevel.DEBUG, "gretl.sql.script",
                    "SQL script is being executed",
                    Map.of("db.alias", alias,
                            "sql.script", script.getFileName().toString(),
                            "sql.entry", Integer.toString(index),
                            "sql.sha256", hash), null);
            try (Statement statement = connection.createStatement()) {
                applyTimeout(statement, context);
                boolean hasResult = statement.execute(sql);
                return collect(statement, hasResult, index);
            } catch (SQLException exception) {
                throw sqlError("SQL_SCRIPT", msg(context, "task.sql.scriptFailed"), alias,
                        script.getFileName().toString(), index, "execute", exception);
            }
        }

        private static SqlExecutionResult executeStatement(
                Connection connection, PreparedStatementSpec spec, int index,
                String alias, OperationContext context) {
            try (PreparedStatement statement = connection.prepareStatement(spec.sql())) {
                applyTimeout(statement, context);
                bind(statement, spec.parameters());
                boolean hasResult = statement.execute();
                return collect(statement, hasResult, index);
            } catch (SQLException exception) {
                throw sqlError("SQL_STATEMENT", msg(context, "task.sql.statementFailed"), alias,
                        null, index, "execute", exception);
            }
        }

        private static SqlExecutionResult collect(
                Statement statement, boolean hasResult, int index) throws SQLException {
            List<Long> updateCounts = new ArrayList<>();
            int resultSets = 0;
            boolean current = hasResult;
            while (true) {
                if (current) {
                    try (ResultSet resultSet = statement.getResultSet()) {
                        if (resultSet != null) {
                            resultSets++;
                            while (resultSet.next()) {
                                // Results are discarded but consumed and closed.
                            }
                        }
                    }
                } else {
                    long updateCount = statement.getUpdateCount();
                    if (updateCount == -1) {
                        break;
                    }
                    updateCounts.add(updateCount);
                }
                current = statement.getMoreResults();
            }
            return new SqlExecutionResult(index, updateCounts, resultSets);
        }

        private static void bind(PreparedStatement statement, List<Object> parameters)
                throws SQLException {
            for (int index = 0; index < parameters.size(); index++) {
                Object value = parameters.get(index);
                int parameter = index + 1;
                switch (value) {
                    case null -> statement.setNull(parameter, Types.NULL);
                    case String text -> statement.setString(parameter, text);
                    case Boolean bool -> statement.setBoolean(parameter, bool);
                    case Byte number -> statement.setByte(parameter, number);
                    case Short number -> statement.setShort(parameter, number);
                    case Integer number -> statement.setInt(parameter, number);
                    case Long number -> statement.setLong(parameter, number);
                    case BigDecimal number -> statement.setBigDecimal(parameter, number);
                    default -> throw new IllegalArgumentException(
                            "Unsupported SQL parameter: " + value.getClass().getName());
                }
            }
        }

        private static void applyTimeout(Statement statement, OperationContext context) {
            Optional<Duration> remaining = context.budget().remaining();
            if (remaining.isEmpty()) {
                return;
            }
            long seconds = Math.max(1, Math.min(Integer.MAX_VALUE,
                    remaining.orElseThrow().toSeconds()));
            try {
                statement.setQueryTimeout((int) seconds);
            } catch (SQLException ignored) {
                // Driver may not support query timeout; cooperative checks remain.
            }
        }

        private static String requiredText(Map<String, Object> config, String key,
                                           OperationContext context) {
            Object value = config.get(key);
            if (!(value instanceof CharSequence text) || text.toString().isBlank()) {
                throw new SqlTaskException("SQL_CONFIG",
                        msg(context, "task.sql.fieldMissing", key),
                        "?", null, 0, "configuration", null, 0, null);
            }
            return text.toString();
        }

        private static String optionalText(Map<String, Object> config, String key,
                                           OperationContext context) {
            Object value = config.get(key);
            if (value == null) {
                return null;
            }
            if (!(value instanceof CharSequence text)) {
                throw new SqlTaskException("SQL_CONFIG",
                        msg(context, "task.sql.fieldNotText", key),
                        "?", null, 0, "configuration", null, 0, null);
            }
            return text.toString();
        }

        private static SqlTaskException sqlError(
                String code, String message, String alias, String script, int index,
                String phase, SQLException exception) {
            SQLException current = exception;
            while (current != null) {
                if (current.getSQLState() != null || current.getErrorCode() != 0) {
                    return new SqlTaskException(code, message, alias, script, index, phase,
                            current.getSQLState(), current.getErrorCode(), exception);
                }
                current = current.getNextException();
            }
            return new SqlTaskException(code, message, alias, script, index, phase,
                    null, 0, exception);
        }

        private static void rollbackQuietly(Connection connection, SqlTaskException primary) {
            if (connection == null) {
                return;
            }
            try {
                connection.rollback();
            } catch (SQLException exception) {
                primary.addSuppressed(exception);
            }
        }

        private static String sha256(String text) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(digest.digest(
                        text.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
