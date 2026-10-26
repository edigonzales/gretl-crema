package ch.so.agi.gretl.runtime.api;

/** Small package-private validation helpers for public API records. */
final class ApiChecks {
    private ApiChecks() {}

    static String text(String value, String field) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    static void unique(java.util.List<String> names) {
        if (new java.util.HashSet<>(names).size() != names.size())
            throw new IllegalArgumentException("Doppelte Feldnamen");
    }
}
