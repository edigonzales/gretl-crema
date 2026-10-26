package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.TaskRef;

/** A predecessor link, either a forward name or an already issued TaskRef. */
public record TaskLink(String name, TaskRef<?> ref) {
    public TaskLink {
        boolean hasName = name != null && !name.isBlank();
        boolean hasRef = ref != null;
        if (hasName == hasRef) {
            throw new IllegalArgumentException("exactly one of name or ref must be set");
        }
    }

    public static TaskLink named(String name) {
        return new TaskLink(name, null);
    }

    public static TaskLink of(TaskRef<?> ref) {
        return new TaskLink(null, ref);
    }

    public boolean isNamed() {
        return name != null;
    }
}
