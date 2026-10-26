package ch.so.agi.gretl.runtime.runner;

/** Optional source position; line and column 0 mean unknown. */
public record SourceLocation(String file, int line, int column) {
    public SourceLocation {
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("file must not be blank");
        }
        if (line < 0 || column < 0) {
            throw new IllegalArgumentException("line/column must not be negative");
        }
    }

    public static SourceLocation unknown() {
        return new SourceLocation("<unknown>", 0, 0);
    }
}
