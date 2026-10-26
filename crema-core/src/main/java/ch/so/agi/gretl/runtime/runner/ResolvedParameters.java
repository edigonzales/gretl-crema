package ch.so.agi.gretl.runtime.runner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable typed parameter view for one definition resolution. */
public record ResolvedParameters(
        Map<String, Object> values, Map<String, ParameterSource> sources,
        List<String> missingRequired, Set<String> declaredNames) {

    public ResolvedParameters {
        Map<String, Object> valueCopy = new LinkedHashMap<>(values);
        values = Collections.unmodifiableMap(valueCopy);
        sources = Map.copyOf(sources);
        missingRequired = List.copyOf(missingRequired);
        declaredNames = Set.copyOf(declaredNames);
    }

    public Object get(String name) {
        return values.get(name);
    }

    public boolean contains(String name) {
        return values.containsKey(name);
    }

    public ParameterSource source(String name) {
        return sources.getOrDefault(name, ParameterSource.MISSING);
    }

    /** Transport source of a declared parameter. */
    public enum ParameterSource {
        DEFAULT, RUNTIME_CONFIG, PARAMETER_FILE, COMMAND_LINE, MISSING
    }
}
