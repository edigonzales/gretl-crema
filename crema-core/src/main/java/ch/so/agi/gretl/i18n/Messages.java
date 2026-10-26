package ch.so.agi.gretl.i18n;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * JDK-only message catalog. English is the source and fallback language;
 * additional locales are shipped as resource bundles.
 */
public final class Messages {
    public static final String BUNDLE = "ch.so.agi.gretl.i18n.messages";
    public static final Locale SOURCE_LOCALE = Locale.ENGLISH;

    private Messages() {
    }

    public static Locale sourceLocale() {
        return SOURCE_LOCALE;
    }

    public static String get(Locale locale, String key) {
        return bundle(locale).getString(key);
    }

    public static String format(Locale locale, String key, Object... arguments) {
        return new MessageFormat(get(locale, key), locale).format(arguments);
    }

    public static boolean contains(Locale locale, String key) {
        return bundle(locale).containsKey(key);
    }

    private static ResourceBundle bundle(Locale locale) {
        Locale selected = locale == null ? SOURCE_LOCALE : locale;
        try {
            return ResourceBundle.getBundle(BUNDLE, selected,
                    Messages.class.getClassLoader(),
                    ResourceBundle.Control.getNoFallbackControl(
                            ResourceBundle.Control.FORMAT_PROPERTIES));
        } catch (MissingResourceException exception) {
            if (selected.equals(SOURCE_LOCALE)) {
                throw exception;
            }
            return ResourceBundle.getBundle(BUNDLE, SOURCE_LOCALE,
                    Messages.class.getClassLoader(),
                    ResourceBundle.Control.getNoFallbackControl(
                            ResourceBundle.Control.FORMAT_PROPERTIES));
        }
    }
}
