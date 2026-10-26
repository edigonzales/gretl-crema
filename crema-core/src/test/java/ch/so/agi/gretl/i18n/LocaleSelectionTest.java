package ch.so.agi.gretl.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class LocaleSelectionTest {
    @Test
    void cliEnvironmentConfigurationThenOperatingSystem() {
        assertEquals(Locale.GERMAN,
                LocaleSelection.resolve("de", "en", "en", Locale.ENGLISH));
        assertEquals(Locale.GERMAN,
                LocaleSelection.resolve(null, "de", "en", Locale.ENGLISH));
        assertEquals(Locale.GERMAN,
                LocaleSelection.resolve(null, null, "de", Locale.ENGLISH));
        assertEquals(Locale.GERMAN,
                LocaleSelection.resolve(null, null, null, Locale.GERMAN));
        assertEquals(Locale.ENGLISH,
                LocaleSelection.resolve(null, null, null, Locale.FRENCH));
    }

    @Test
    void unsupportedValuesFallThroughToTheNextSource() {
        assertEquals(Locale.ENGLISH,
                LocaleSelection.resolve("fr", "fr", "fr", Locale.ENGLISH));
        assertEquals(Locale.GERMAN,
                LocaleSelection.resolve("fr", "de", null, Locale.ENGLISH));
    }
}
