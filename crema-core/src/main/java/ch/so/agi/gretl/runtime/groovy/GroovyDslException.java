package ch.so.agi.gretl.runtime.groovy;

import ch.so.agi.gretl.runtime.api.DiagnosticCarrier;
import ch.so.agi.gretl.runtime.api.DiagnosticInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Groovy DSL error with an optional task, field and original source position. */
public class GroovyDslException extends RuntimeException implements DiagnosticCarrier {
    private static final long serialVersionUID = 1L;

    private final String code;
    private final String taskId;
    private final String fieldName;
    private final SourceLocation source;
    private final ArrayList<String> available;

    public GroovyDslException(String code, String message) {
        this(code, message, null, null, null, List.of(), null);
    }

    public GroovyDslException(String code, String message, Throwable cause) {
        this(code, message, null, null, null, List.of(), cause);
    }

    public GroovyDslException(
            String code, String message, String taskId, String fieldName,
            SourceLocation source, List<String> available, Throwable cause) {
        super(message == null ? "Groovy DSL error" : message, cause);
        this.code = require(code, "code");
        this.taskId = taskId;
        this.fieldName = fieldName;
        this.source = source;
        this.available = new ArrayList<>(available);
    }

    public String code() {
        return code;
    }

    public String taskId() {
        return taskId;
    }

    public String fieldName() {
        return fieldName;
    }

    public SourceLocation source() {
        return source;
    }

    public List<String> available() {
        return List.copyOf(available);
    }

    @Override
    public DiagnosticInfo diagnostic() {
        return new DiagnosticInfo(code,
                Optional.ofNullable(taskId),
                Optional.ofNullable(fieldName),
                available,
                Optional.empty());
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
