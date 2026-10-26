package ch.so.agi.gretl.runtime.api;

import java.util.List;
import java.util.Optional;

/** Structured, non-localized diagnostic metadata for a failure. */
public record DiagnosticInfo(
        String code, Optional<String> taskId, Optional<String> field,
        List<String> available, Optional<String> hint) {

    public DiagnosticInfo {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        java.util.Objects.requireNonNull(taskId, "taskId");
        java.util.Objects.requireNonNull(field, "field");
        available = List.copyOf(available);
        java.util.Objects.requireNonNull(hint, "hint");
    }
}
