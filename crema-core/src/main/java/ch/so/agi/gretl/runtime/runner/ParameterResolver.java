package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.i18n.Messages;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One-shot resolution and typed conversion of public job parameters. The
 * resolver never reads secrets or mutates the definition.
 */
public final class ParameterResolver {
    private final Path startCwd;
    private final java.util.Locale locale;

    public ParameterResolver(Path startCwd) {
        this(startCwd, java.util.Locale.GERMAN);
    }

    public ParameterResolver(Path startCwd, java.util.Locale locale) {
        Objects.requireNonNull(startCwd, "startCwd");
        this.startCwd = startCwd.toAbsolutePath().normalize();
        this.locale = Objects.requireNonNull(locale, "locale");
    }

    public ResolvedParameters resolve(
            JobDefinition definition, ParameterSources sources, ParameterMode mode) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(mode, "mode");

        Map<String, ParameterDefinition> declared = new LinkedHashMap<>();
        for (ParameterDefinition parameter : definition.parameters()) {
            declared.put(parameter.name(), parameter);
        }

        rejectUnknown(sources.parameterFile(), declared,
                Messages.get(locale, "cli.source.parameterFile"));
        rejectUnknown(sources.commandLine(), declared,
                Messages.get(locale, "cli.source.commandLine"));

        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, ResolvedParameters.ParameterSource> origins = new LinkedHashMap<>();
        List<String> missingRequired = new ArrayList<>();

        for (ParameterDefinition parameter : definition.parameters()) {
            String name = parameter.name();
            String raw = null;
            ResolvedParameters.ParameterSource origin = ResolvedParameters.ParameterSource.MISSING;

            if (sources.commandLine().containsKey(name)) {
                raw = sources.commandLine().get(name);
                origin = ResolvedParameters.ParameterSource.COMMAND_LINE;
            } else if (sources.parameterFile().containsKey(name)) {
                raw = sources.parameterFile().get(name);
                origin = ResolvedParameters.ParameterSource.PARAMETER_FILE;
            } else if (sources.runtimeConfig().containsKey(name)) {
                raw = sources.runtimeConfig().get(name);
                origin = ResolvedParameters.ParameterSource.RUNTIME_CONFIG;
            }

            Object value;
            if (raw != null) {
                value = convert(name, parameter.type(), raw);
            } else if (parameter.defaultValue().isPresent()) {
                value = convertDefault(name, parameter.type(), parameter.defaultValue().orElseThrow());
                origin = ResolvedParameters.ParameterSource.DEFAULT;
            } else {
                value = null;
            }

            if (value == null) {
                values.put(name, null);
                origins.put(name, ResolvedParameters.ParameterSource.MISSING);
                if (parameter.required()) {
                    missingRequired.add(name);
                }
                continue;
            }

            if (parameter.required() && isEmptyValue(parameter.type(), value)) {
                throw new ParameterException("INVALID_PARAMETER_VALUE", name,
                        Messages.format(locale, "error.parameter.emptyRequired", name));
            }

            values.put(name, value);
            origins.put(name, origin);
        }

        if (mode.requiresCompleteSet() && !missingRequired.isEmpty()) {
            String first = missingRequired.get(0);
            ParameterDefinition definitionOfFirst = declared.get(first);
            throw new ParameterException("MISSING_REQUIRED_PARAMETER", first,
                    Messages.format(locale, "error.parameter.missing",
                            first,
                            definitionOfFirst == null ? ""
                                    : Messages.format(locale, "error.parameter.missingHint",
                                            definitionOfFirst.description()),
                            String.join(", ", missingRequired)));
        }

        return new ResolvedParameters(values, origins, missingRequired, declared.keySet());
    }

    private Object convert(String name, ParameterValueType type, String raw) {
        return switch (type) {
            case TEXT -> raw;
            case BOOLEAN -> parseBoolean(name, raw);
            case INTEGER -> parseInteger(name, raw);
            case PATH -> parsePath(name, raw);
        };
    }

    private Object convertDefault(String name, ParameterValueType type, Object raw) {
        return switch (type) {
            case TEXT -> raw instanceof CharSequence text ? text.toString()
                    : throwInvalid(name, raw, type);
            case BOOLEAN -> raw instanceof Boolean value ? value
                    : parseBoolean(name, raw.toString());
            case INTEGER -> raw instanceof Number number ? number.longValue()
                    : parseInteger(name, raw.toString());
            case PATH -> raw instanceof Path path ? normalizePath(path)
                    : parsePath(name, raw.toString());
        };
    }

    private Object throwInvalid(String name, Object raw, ParameterValueType type) {
        throw new ParameterException("INVALID_PARAMETER_VALUE", name,
                Messages.format(locale, "error.parameter.invalidDefault", type, raw));
    }

    private Boolean parseBoolean(String name, String raw) {
        if ("true".equals(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equals(raw)) {
            return Boolean.FALSE;
        }
        throw new ParameterException("INVALID_PARAMETER_VALUE", name,
                Messages.format(locale, "error.parameter.boolean", name));
    }

    private Long parseInteger(String name, String raw) {
        if (!raw.matches("[+-]?[0-9]+")) {
            throw new ParameterException("INVALID_PARAMETER_VALUE", name,
                    Messages.format(locale, "error.parameter.integer", name));
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException exception) {
            throw new ParameterException("INVALID_PARAMETER_VALUE", name,
                    Messages.format(locale, "error.parameter.integerRange", name));
        }
    }

    private Path parsePath(String name, String raw) {
        if (raw.isBlank()) {
            throw new ParameterException("INVALID_PARAMETER_VALUE", name,
                    Messages.format(locale, "error.parameter.pathEmpty", name));
        }
        try {
            return normalizePath(Path.of(raw));
        } catch (RuntimeException exception) {
            throw new ParameterException("INVALID_PARAMETER_VALUE", name,
                    Messages.format(locale, "error.parameter.pathInvalid", name));
        }
    }

    private Path normalizePath(Path path) {
        return (path.isAbsolute() ? path : startCwd.resolve(path)).normalize();
    }

    private static boolean isEmptyValue(ParameterValueType type, Object value) {
        return switch (type) {
            case TEXT -> value instanceof String text && text.isEmpty();
            case PATH -> value instanceof Path path && path.toString().isBlank();
            case BOOLEAN, INTEGER -> false;
        };
    }

    private void rejectUnknown(
            Map<String, String> source, Map<String, ParameterDefinition> declared, String label) {
        for (String name : source.keySet()) {
            if (declared.containsKey(name)) {
                continue;
            }
            String suggestion = closest(name, declared.keySet());
            throw new ParameterException("UNKNOWN_PARAMETER", name,
                    Messages.format(locale, "error.parameter.unknown", name,
                            suggestion == null ? ""
                                    : Messages.format(locale,
                                            "error.parameter.unknownSuggestion", suggestion),
                            label));
        }
    }

    private static String closest(String name, Iterable<String> candidates) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int distance = editDistance(name, candidate);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return bestDistance <= 2 ? best : null;
    }

    private static int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int index = 0; index <= right.length(); index++) {
            previous[index] = index;
        }
        for (int leftIndex = 1; leftIndex <= left.length(); leftIndex++) {
            current[0] = leftIndex;
            for (int rightIndex = 1; rightIndex <= right.length(); rightIndex++) {
                int substitution = previous[rightIndex - 1]
                        + (left.charAt(leftIndex - 1) == right.charAt(rightIndex - 1) ? 0 : 1);
                current[rightIndex] = Math.min(Math.min(
                        current[rightIndex - 1] + 1,
                        previous[rightIndex] + 1), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }
}
