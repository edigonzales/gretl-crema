package ch.so.agi.gretl.runtime.api;

/**
 * One explicitly released diagnostic. Severity does not determine task status;
 * only an unhandled exception fails the surrounding task.
 */
public record Diagnostic(
    DiagnosticSeverity severity, String code, String message,
    java.util.Map<String, String> details,
    java.util.List<DiagnosticArtifact> artifacts
) {
    public Diagnostic {
        java.util.Objects.requireNonNull(severity, "severity");
        code = ApiChecks.text(code, "code");
        message = ApiChecks.text(message, "message");
        details = java.util.Map.copyOf(details);
        artifacts = java.util.List.copyOf(artifacts);
    }
}
