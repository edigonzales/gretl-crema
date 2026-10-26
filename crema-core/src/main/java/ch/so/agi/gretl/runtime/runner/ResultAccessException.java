package ch.so.agi.gretl.runtime.runner;

/** A result access violation with a stable diagnostic code. */
public final class ResultAccessException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    private final String code;

    public ResultAccessException(String code, String message) {
        super(message);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}
