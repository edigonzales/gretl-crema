package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.TaskRef;
import java.util.Objects;
import java.util.UUID;

/** Opaque task reference issued by exactly one JobDefinitionBuilder. */
final class JobTaskRef<R> implements TaskRef<R> {
    private final UUID definitionId;
    private final String id;
    private final Class<R> resultType;
    private final int registrationIndex;

    JobTaskRef(UUID definitionId, String id, Class<R> resultType, int registrationIndex) {
        this.definitionId = Objects.requireNonNull(definitionId, "definitionId");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        this.id = id;
        this.resultType = Objects.requireNonNull(resultType, "resultType");
        this.registrationIndex = registrationIndex;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Class<R> resultType() {
        return resultType;
    }

    UUID definitionId() {
        return definitionId;
    }

    int registrationIndex() {
        return registrationIndex;
    }

    @Override
    public String toString() {
        return "TaskRef[" + id + "]";
    }
}
