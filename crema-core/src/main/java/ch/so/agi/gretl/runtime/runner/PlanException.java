package ch.so.agi.gretl.runtime.runner;

/** A target/planning error discovered before a run begins (exit-code class 2). */
public final class PlanException extends DefinitionException {
    private static final long serialVersionUID = 1L;

    public PlanException(String code, String message) {
        super(code, message);
    }
}
