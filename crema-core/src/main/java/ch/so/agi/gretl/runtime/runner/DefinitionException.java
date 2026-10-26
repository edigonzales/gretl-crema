package ch.so.agi.gretl.runtime.runner;

/** A static job/provider definition error discovered before task execution. */
public class DefinitionException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    public DefinitionException(String code, String message) {
        super(message);
        this.code = requireText(code, "code");
    }

    public DefinitionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = requireText(code, "code");
    }

    public String code() {
        return code;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
