package ch.so.agi.gretl.runtime.runner;

import java.util.Locale;

/** Supported public job-parameter types from spec 04/06. */
public enum ParameterValueType {
    TEXT, BOOLEAN, INTEGER, PATH;

    public static ParameterValueType parse(String value) {
        if (value == null || value.isBlank()) {
            return TEXT;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "text" -> TEXT;
            case "boolean" -> BOOLEAN;
            case "integer" -> INTEGER;
            case "path" -> PATH;
            default -> throw new ParameterException("UNKNOWN_PARAMETER_TYPE", value,
                    "Unbekannter Parametertyp: " + value);
        };
    }
}
