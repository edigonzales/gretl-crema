package ch.so.agi.gretl.runtime.api;

/** Optional interface for exceptions carrying structured diagnostics. */
public interface DiagnosticCarrier {
    DiagnosticInfo diagnostic();
}
