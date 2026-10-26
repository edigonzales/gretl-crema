package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.ContextAccessException;
import java.util.ArrayList;
import java.util.List;

/** Run-bound registry of known secret values for later redaction. */
public final class SecretRegistry {
    private static final int MAX_VALUES = 256;
    private static final int MAX_VALUE_CHARS = 64 * 1024;

    private final List<String> values = new ArrayList<>();

    public String register(String value) {
        if (value == null) {
            throw new IllegalArgumentException("secret value must not be null");
        }
        if (value.length() > MAX_VALUE_CHARS) {
            throw new ContextAccessException("SECRET_LIMIT",
                    "Secret value exceeds the run limit of " + MAX_VALUE_CHARS + " characters");
        }
        if (!values.contains(value) && values.size() >= MAX_VALUES) {
            throw new ContextAccessException("SECRET_LIMIT",
                    "The run has reached the limit of " + MAX_VALUES + " registered secrets");
        }
        if (!values.contains(value)) {
            values.add(value);
        }
        return value;
    }

    public List<String> values() {
        return List.copyOf(values);
    }
}
