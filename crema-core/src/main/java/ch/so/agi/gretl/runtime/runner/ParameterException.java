package ch.so.agi.gretl.runtime.runner;

/** A public job-parameter declaration or value error (exit-code class 2). */
public final class ParameterException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    private final String code;
    private final String parameterName;

    public ParameterException(String code, String parameterName, String message) {
        super(message);
        this.code = text(code, "code");
        this.parameterName = text(parameterName, "parameterName");
    }

    public String code() {
        return code;
    }

    public String parameterName() {
        return parameterName;
    }

    private static String text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
