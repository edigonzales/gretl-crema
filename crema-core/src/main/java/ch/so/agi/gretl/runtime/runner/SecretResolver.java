package ch.so.agi.gretl.runtime.runner;

import java.nio.file.Path;

/** String transport adapter for named secret sources. */
@FunctionalInterface
public interface SecretResolver {
    String resolve(String alias);

    static SecretResolver none() {
        return alias -> {
            throw new IllegalArgumentException("unknown secret alias: " + alias);
        };
    }
}
