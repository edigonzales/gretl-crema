package ch.so.agi.gretl.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class MessagesTest {
    @Test
    void englishAndGermanCatalogsHaveTheSameKeys() {
        ResourceBundle english = ResourceBundle.getBundle(Messages.BUNDLE,
                Locale.ENGLISH, getClass().getClassLoader());
        ResourceBundle german = ResourceBundle.getBundle(Messages.BUNDLE,
                Locale.GERMAN, getClass().getClassLoader());

        assertEquals(english.keySet(), german.keySet());
        for (String key : english.keySet()) {
            assertEquals(english.getString(key),
                    Messages.get(Locale.ENGLISH, key));
            assertEquals(german.getString(key), Messages.get(Locale.GERMAN, key));
        }
    }

    @Test
    void unsupportedLocaleFallsBackToEnglish() {
        assertEquals("Task failed", Messages.get(Locale.FRENCH,
                "log.title.gretl.task.failed"));
        assertTrue(Messages.contains(Locale.GERMAN, "log.field.code"));
    }
}
