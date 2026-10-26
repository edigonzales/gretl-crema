package ch.so.agi.gretl.runtime.runner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deep immutable copy of the deliberately small JSON value universe. */
final class ImmutableJson {
    private ImmutableJson() {
    }

    static Map<String, Object> map(Map<String, ?> source) {
        if (source == null) {
            throw new IllegalArgumentException("JSON object must not be null");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("JSON object contains a blank key");
            }
            copy.put(key, value(value));
        });
        return Collections.unmodifiableMap(copy);
    }

    static Object value(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Number) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                if (!(key instanceof String text) || text.isBlank()) {
                    throw new IllegalArgumentException("JSON object keys must be non-blank strings");
                }
                copy.put(text, value(nested));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return Collections.unmodifiableList(list.stream().map(ImmutableJson::value).toList());
        }
        throw new IllegalArgumentException("Unsupported JSON value type: " + value.getClass().getName());
    }
}
