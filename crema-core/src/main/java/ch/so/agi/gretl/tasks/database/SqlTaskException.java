package ch.so.agi.gretl.tasks.database;

import ch.so.agi.gretl.runtime.api.DiagnosticCarrier;
import ch.so.agi.gretl.runtime.api.DiagnosticInfo;
import java.util.List;
import java.util.Optional;

/** Safe SQL failure details; SQL text and bound values are never included. */
public final class SqlTaskException extends RuntimeException
        implements DiagnosticCarrier {
    private static final long serialVersionUID = 1L;

    private final String code;
    private final String databaseAlias;
    private final String scriptName;
    private final int entryIndex;
    private final String phase;
    private final String sqlState;
    private final int vendorCode;

    public SqlTaskException(String code, String message, String databaseAlias,
                            String scriptName, int entryIndex, String phase,
                            String sqlState, int vendorCode, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.databaseAlias = databaseAlias;
        this.scriptName = scriptName;
        this.entryIndex = entryIndex;
        this.phase = phase;
        this.sqlState = sqlState;
        this.vendorCode = vendorCode;
    }

    public String code() { return code; }

    @Override
    public DiagnosticInfo diagnostic() {
        return new DiagnosticInfo(code, Optional.empty(), Optional.empty(),
                List.of(), Optional.empty());
    }

    public String databaseAlias() { return databaseAlias; }
    public String scriptName() { return scriptName; }
    public int entryIndex() { return entryIndex; }
    public String phase() { return phase; }
    public String sqlState() { return sqlState; }
    public int vendorCode() { return vendorCode; }
}
