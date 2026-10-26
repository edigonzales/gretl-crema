package ch.so.agi.gretl.runtime.groovy;

import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Extracts original script file/line from Groovy stack frames. */
public final class SourceLocations {
    private SourceLocations() {
    }

    public static SourceLocation current(Path scriptFile) {
        return from(Thread.currentThread().getStackTrace(), scriptFile);
    }

    public static SourceLocation from(Throwable throwable, Path scriptFile) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        while (current != null && seen.add(current)) {
            SourceLocation found = from(current.getStackTrace(), scriptFile);
            if (found.line() > 0) {
                return found;
            }
            current = current.getCause();
        }
        return fallback(scriptFile);
    }

    public static SourceLocation fallback(Path scriptFile) {
        String name = scriptFile == null || scriptFile.getFileName() == null
                ? "<unknown>" : scriptFile.getFileName().toString();
        return new SourceLocation(name, 0, 0);
    }

    private static SourceLocation from(StackTraceElement[] stack, Path scriptFile) {
        if (scriptFile == null) {
            return new SourceLocation("<unknown>", 0, 0);
        }
        String expected = scriptFile.getFileName().toString();
        for (StackTraceElement element : stack) {
            if (expected.equals(element.getFileName()) && element.getLineNumber() > 0) {
                return new SourceLocation(expected, element.getLineNumber(), 0);
            }
        }
        return fallback(scriptFile);
    }
}
