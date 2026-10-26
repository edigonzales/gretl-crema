package ch.so.agi.gretl.runtime.runner;

/** One selected planning edge and its kind. */
public record PlanEdge(String fromId, String toId, EdgeType type) {
    public PlanEdge {
        text(fromId, "fromId");
        text(toId, "toId");
        java.util.Objects.requireNonNull(type, "type");
    }

    private static void text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    public enum EdgeType { DEPENDS_ON, AFTER }
}
