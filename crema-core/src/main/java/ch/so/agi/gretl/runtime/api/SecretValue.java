package ch.so.agi.gretl.runtime.api;

/**
 * Lazily resolved secret value. The runtime registers a revealed value with its
 * run-scoped redactor before returning it. {@link #toString()} is always masked.
 */
public final class SecretValue {
    private final java.util.function.Supplier<String> resolver;

    private SecretValue(java.util.function.Supplier<String> resolver) {
        this.resolver = java.util.Objects.requireNonNull(resolver, "resolver");
    }

    public static SecretValue deferred(java.util.function.Supplier<String> resolver) {
        return new SecretValue(resolver);
    }

    public String reveal() {
        return java.util.Objects.requireNonNull(resolver.get(), "secret value");
    }

    @Override
    public String toString() {
        return "[REDACTED]";
    }
}
