package ch.so.agi.gretl.i18n;

import java.util.Locale;
import java.util.Optional;

/** Documented locale precedence for CLI, environment, configuration and OS. */
public final class LocaleSelection {
    private LocaleSelection() {
    }

    public static Locale resolve(String cliValue, String environmentValue,
                                 String configuredValue, Locale operatingSystem) {
        return parse(cliValue)
                .or(() -> parse(environmentValue))
                .or(() -> parse(configuredValue))
                .orElseGet(() -> fromOperatingSystem(operatingSystem));
    }

    public static Optional<Locale> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String language = value.strip().toLowerCase(Locale.ROOT);
        if (language.equals("en") || language.startsWith("en-")) {
            return Optional.of(Locale.ENGLISH);
        }
        if (language.equals("de") || language.startsWith("de-")) {
            return Optional.of(Locale.GERMAN);
        }
        return Optional.empty();
    }

    public static Locale fromOperatingSystem(Locale operatingSystem) {
        if (operatingSystem != null
                && "de".equalsIgnoreCase(operatingSystem.getLanguage())) {
            return Locale.GERMAN;
        }
        return Locale.ENGLISH;
    }
}
