package ch.so.agi.gretl.runtime.groovy;

import ch.so.agi.gretl.runtime.runner.OperationRegistry;
import java.nio.file.Path;

/**
 * Explicit wiring for one Groovy definition load. It contains no Groovy state
 * and can be reused as a small test/CLI boundary.
 */
public record DefinitionEnvironment(
        OperationRegistry registry, Path jobRoot, Path startCwd, UserMessageSink userMessages,
        java.util.Locale locale) {

    public DefinitionEnvironment {
        java.util.Objects.requireNonNull(registry, "registry");
        java.util.Objects.requireNonNull(jobRoot, "jobRoot");
        java.util.Objects.requireNonNull(startCwd, "startCwd");
        java.util.Objects.requireNonNull(userMessages, "userMessages");
        java.util.Objects.requireNonNull(locale, "locale");
        jobRoot = jobRoot.toAbsolutePath().normalize();
        startCwd = startCwd.toAbsolutePath().normalize();
    }

    public DefinitionEnvironment(OperationRegistry registry, Path jobRoot, Path startCwd,
                                  UserMessageSink userMessages) {
        this(registry, jobRoot, startCwd, userMessages, java.util.Locale.GERMAN);
    }

    public static DefinitionEnvironment of(OperationRegistry registry, Path jobRoot) {
        return new DefinitionEnvironment(registry, jobRoot, jobRoot, UserMessageSink.NO_OP,
                java.util.Locale.GERMAN);
    }

    public DefinitionEnvironment withUserMessages(UserMessageSink sink) {
        return new DefinitionEnvironment(registry, jobRoot, startCwd, sink, locale);
    }
}
