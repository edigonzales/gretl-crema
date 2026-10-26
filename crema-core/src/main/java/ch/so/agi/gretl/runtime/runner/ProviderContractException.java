package ch.so.agi.gretl.runtime.runner;

/**
 * Broken provider contract, for example a non-void handler that returns null.
 * Its stable code is exposed for diagnostics; it is not a business exception.
 */
public final class ProviderContractException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    public ProviderContractException(String code, String message) {
        super(message);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        this.code = code;
    }

    public ProviderContractException(String code, String message, Throwable cause) {
        super(message, cause);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}
