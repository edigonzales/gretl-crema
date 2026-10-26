package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.i18n.Messages;
import ch.so.agi.gretl.runtime.api.TaskRef;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Ordered registration of one job definition with exactly one freeze transition.
 * Task references are issued here; aliases are resolved only at freeze time so
 * forward references by name are allowed.
 */
public final class JobDefinitionBuilder {
    private final UUID definitionId = UUID.randomUUID();
    private final Path jobRoot;
    private final java.util.Locale locale;
    private final List<Registered<?>> registered = new ArrayList<>();
    private final Map<String, Registered<?>> byId = new LinkedHashMap<>();
    private final List<ParameterDefinition> parameters = new ArrayList<>();
    private final Set<String> parameterNames = new HashSet<>();
    private final List<String> defaultTargets = new ArrayList<>();
    private final Set<String> defaultTargetNames = new HashSet<>();
    private final List<CleanupDefinition> cleanups = new ArrayList<>();
    private final Set<String> cleanupNames = new HashSet<>();
    private boolean frozen;

    public JobDefinitionBuilder(Path jobRoot) {
        this(jobRoot, java.util.Locale.GERMAN);
    }

    public JobDefinitionBuilder(Path jobRoot, java.util.Locale locale) {
        this.jobRoot = Objects.requireNonNull(jobRoot, "jobRoot").toAbsolutePath().normalize();
        this.locale = Objects.requireNonNull(locale, "locale");
    }

    public UUID definitionId() {
        return definitionId;
    }

    public <R> TaskRef<R> register(TaskRegistration<R> registration) {
        Objects.requireNonNull(registration, "registration");
        ensureNotFrozen();
        if (byId.containsKey(registration.id())) {
            throw new DefinitionException("DUPLICATE_TASK_ID",
                    Messages.format(locale, "runner.duplicateTaskId", registration.id()));
        }
        JobTaskRef<R> ref = new JobTaskRef<>(
                definitionId, registration.id(), registration.resultType(), registered.size());
        Registered<R> pending = new Registered<>(ref, registration, registered.size());
        registered.add(pending);
        byId.put(registration.id(), pending);
        return ref;
    }

    public void registerParameter(ParameterDefinition parameter) {
        Objects.requireNonNull(parameter, "parameter");
        ensureNotFrozen();
        if (!parameterNames.add(parameter.name())) {
            throw new ParameterException("DUPLICATE_PARAMETER", parameter.name(),
                    Messages.format(locale, "runner.duplicateParameter", parameter.name()));
        }
        parameters.add(parameter);
    }

    public void defaults(String... targets) {
        ensureNotFrozen();
        for (String target : targets) {
            addDefault(target);
        }
    }

    public void defaultsRef(TaskRef<?> ref) {
        ensureNotFrozen();
        addDefault(defaultName(ref));
    }

    public String defaultName(TaskRef<?> ref) {
        if (!(ref instanceof JobTaskRef<?> jobRef)
                || !definitionId.equals(jobRef.definitionId())
                || !byId.containsKey(jobRef.id())) {
            throw new DefinitionException("FOREIGN_TASK_REFERENCE",
                    Messages.format(locale, "runner.foreignDefaultRef", ref));
        }
        return jobRef.id();
    }

    private void addDefault(String target) {
        if (target == null || target.isBlank()) {
            throw new DefinitionException("INVALID_DEFAULT_TARGET",
                    Messages.get(locale, "runner.invalidDefaultTarget"));
        }
        if (!defaultTargetNames.add(target)) {
            throw new DefinitionException("DUPLICATE_DEFAULT_TARGET",
                    Messages.format(locale, "runner.duplicateDefaultTarget", target));
        }
        defaultTargets.add(target);
    }

    public void cleanup(String name, CleanupBody body) {
        cleanup(name, body, SourceLocation.unknown());
    }

    public void cleanup(String name, CleanupBody body, SourceLocation source) {
        ensureNotFrozen();
        Objects.requireNonNull(body, "body");
        if (!cleanupNames.add(name)) {
            throw new DefinitionException("DUPLICATE_CLEANUP_NAME",
                    Messages.format(locale, "runner.duplicateCleanupName", name));
        }
        cleanups.add(new CleanupDefinition(name, cleanups.size(), body, source));
    }

    public JobDefinition freeze() {
        if (frozen) {
            throw new DefinitionException("DEFINITION_ALREADY_FROZEN",
                    Messages.get(locale, "runner.definitionFrozen"));
        }
        List<TaskDefinition<?>> tasks = new ArrayList<>(registered.size());
        for (Registered<?> pending : registered) {
            List<TaskRef<?>> dependsOn = resolveLinks(pending.registration.dependsOn(), pending.ref.id());
            List<TaskRef<?>> after = resolveLinks(pending.registration.after(), pending.ref.id());
            tasks.add(createDefinition(pending, dependsOn, after));
        }
        for (String target : defaultTargets) {
            if (!byId.containsKey(target)) {
                throw new DefinitionException("UNKNOWN_DEFAULT_TARGET",
                        Messages.format(locale, "runner.unknownDefaultTarget", target));
            }
        }
        frozen = true;
        return new JobDefinition(definitionId, tasks, parameters, defaultTargets, cleanups, jobRoot);
    }

    public boolean isFrozen() {
        return frozen;
    }

    private <R> TaskDefinition<R> createDefinition(
            Registered<R> pending, List<TaskRef<?>> dependsOn, List<TaskRef<?>> after) {
        TaskRegistration<R> registration = pending.registration;
        return new TaskDefinition<>(
                pending.ref, pending.index, registration.description(),
                dependsOn, after, registration.condition(), registration.timeout(),
                registration.body(), registration.operation(), registration.source());
    }

    private List<TaskRef<?>> resolveLinks(List<TaskLink> links, String ownerId) {
        Map<String, TaskRef<?>> resolved = new LinkedHashMap<>();
        for (TaskLink link : links) {
            TaskRef<?> ref;
            if (link.isNamed()) {
                Registered<?> target = byId.get(link.name());
                if (target == null) {
                    throw new DefinitionException("UNKNOWN_TASK_REFERENCE",
                            Messages.format(locale, "runner.unknownTaskReference", ownerId, link.name()));
                }
                ref = target.ref;
            } else {
                ref = link.ref();
                if (!(ref instanceof JobTaskRef<?> jobRef)
                        || !definitionId.equals(jobRef.definitionId())
                        || !byId.containsKey(jobRef.id())) {
                    throw new DefinitionException("FOREIGN_TASK_REFERENCE",
                            Messages.format(locale, "runner.foreignTaskReference", ownerId, ref));
                }
            }
            if (resolved.putIfAbsent(ref.id(), ref) != null) {
                throw new DefinitionException("DUPLICATE_TASK_LINK",
                        Messages.format(locale, "runner.duplicateTaskLink", ownerId, ref.id()));
            }
        }
        return List.copyOf(resolved.values());
    }

    private void ensureNotFrozen() {
        if (frozen) {
            throw new DefinitionException("DYNAMIC_TASK_REGISTRATION_UNSUPPORTED",
                    Messages.get(locale, "runner.dynamicUnsupported"));
        }
    }

    private record Registered<R>(TaskRef<R> ref, TaskRegistration<R> registration, int index) {
    }
}
