package ch.so.agi.gretl.runtime.runner;

import java.util.Objects;

/** Frozen job-cleanup definition. */
public record CleanupDefinition(
        String name, int registrationIndex, CleanupBody body, SourceLocation source) {

    public CleanupDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (registrationIndex < 0) {
            throw new IllegalArgumentException("registrationIndex must not be negative");
        }
        Objects.requireNonNull(body, "body");
        source = source == null ? SourceLocation.unknown() : source;
    }
}
