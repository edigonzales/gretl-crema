package ch.so.agi.gretl.runtime.groovy;

import java.io.Serializable;

/** Optional original source position; line/column 0 mean unknown. */
public record SourceLocation(String file, int line, int column) implements Serializable {
    private static final long serialVersionUID = 1L;

    public SourceLocation {
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("file must not be blank");
        }
        if (line < 0 || column < 0) {
            throw new IllegalArgumentException("line/column must not be negative");
        }
    }

    @Override
    public String toString() {
        return line > 0 ? file + ":" + line : file;
    }
}
