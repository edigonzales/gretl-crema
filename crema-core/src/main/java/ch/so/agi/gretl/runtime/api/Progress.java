package ch.so.agi.gretl.runtime.api;

/** Cumulative progress within one stable phase; total may be unknown. */
public record Progress(
    String phase, long completed, java.util.OptionalLong total, String unit
) {
    public Progress {
        phase = ApiChecks.text(phase, "phase");
        unit = ApiChecks.text(unit, "unit");
        java.util.Objects.requireNonNull(total, "total");
        if (completed < 0 || (total.isPresent() && total.getAsLong() < completed))
            throw new IllegalArgumentException("Ungueltiger Fortschritt");
    }
}
