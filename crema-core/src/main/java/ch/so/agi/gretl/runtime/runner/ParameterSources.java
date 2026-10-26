package ch.so.agi.gretl.runtime.runner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * String transport sources for public job parameters. Runtime-config entries are
 * lower priority than the explicit job parameter file and CLI; unknown runtime
 * config entries are ignored, while unknown explicit names are errors.
 */
public record ParameterSources(
        Map<String, String> runtimeConfig,
        Map<String, String> parameterFile,
        Map<String, String> commandLine) {

    public ParameterSources {
        runtimeConfig = copy(runtimeConfig, "runtimeConfig");
        parameterFile = copy(parameterFile, "parameterFile");
        commandLine = copy(commandLine, "commandLine");
    }

    public static ParameterSources empty() {
        return new ParameterSources(Map.of(), Map.of(), Map.of());
    }

    private static Map<String, String> copy(Map<String, String> source, String field) {
        java.util.Objects.requireNonNull(source, field);
        Map<String, String> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException(field + " contains a blank name");
            }
            if (value == null) {
                throw new IllegalArgumentException(field + " contains a null value for " + key);
            }
            copy.put(key, value);
        });
        return Collections.unmodifiableMap(copy);
    }
}
