package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.TaskRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Owns the terminal status and raw value of every selected task in one run.
 * TaskStatus and ResultSlot are deliberately separate values.
 */
public final class ResultStore {
    private final UUID definitionId;
    private final JobDefinition definition;
    private final ExecutionPlan plan;
    private final Map<String, TaskState> states = new LinkedHashMap<>();

    public ResultStore(JobDefinition definition, ExecutionPlan plan) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.plan = Objects.requireNonNull(plan, "plan");
        if (!definition.definitionId().equals(plan.definitionId())) {
            throw new IllegalArgumentException("plan belongs to another definition");
        }
        this.definitionId = definition.definitionId();
        for (TaskDefinition<?> task : plan.selectedTasks()) {
            states.put(task.id(), new TaskState());
        }
    }

    public void start(TaskRef<?> ref, Instant startedAt, long startTicks) {
        TaskState state = stateFor(ref);
        if (state.status != TaskStatus.PENDING) {
            throw new IllegalStateException("task is not PENDING: " + ref.id());
        }
        state.status = TaskStatus.RUNNING;
        state.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        state.startTicks = startTicks;
    }

    public void addInvocation(TaskRef<?> ref, InvocationOutcome invocation) {
        TaskState state = stateFor(ref);
        if (state.status != TaskStatus.RUNNING) {
            throw new IllegalStateException("invocation recorded for non-running task: " + ref.id());
        }
        state.invocations.add(Objects.requireNonNull(invocation, "invocation"));
    }

    public void complete(TaskRef<?> ref, TaskCompletion completion, Instant finishedAt, long endTicks) {
        TaskState state = stateFor(ref);
        Objects.requireNonNull(completion, "completion");
        if (state.status == TaskStatus.PENDING && completion.status() == TaskStatus.NOT_RUN) {
            state.status = TaskStatus.NOT_RUN;
            state.reason = completion.reason();
        state.cancelReason = completion.cancelReason();
        state.failurePhase = completion.failurePhase();
            state.slot = ResultSlot.NoValue.INSTANCE;
            state.finishedAt = Optional.empty();
            state.durationMillis = 0;
            state.failure = Optional.empty();
            state.secondaryFailures = List.copyOf(completion.secondaryFailures());
            return;
        }
        if (state.status != TaskStatus.RUNNING) {
            throw new IllegalStateException("task is not RUNNING: " + ref.id());
        }
        if (completion.status() == TaskStatus.NOT_RUN) {
            throw new IllegalStateException("NOT_RUN can only be applied to PENDING tasks");
        }
        if (completion.status() == TaskStatus.SUCCEEDED
                && completion.slot() instanceof ResultSlot.Value value) {
            Class<?> resultType = definition.requireTask(ref).resultType();
            if (!resultType.isInstance(value.value())) {
                throw new ProviderContractException("TASK_RESULT_TYPE_MISMATCH",
                        "Task " + ref.id() + " returned " + value.value().getClass().getName()
                                + " statt " + resultType.getName());
            }
        }
        state.status = completion.status();
        state.slot = completion.slot();
        state.reason = completion.reason();
        state.cancelReason = completion.cancelReason();
        state.failurePhase = completion.failurePhase();
        state.finishedAt = Optional.of(Objects.requireNonNull(finishedAt, "finishedAt"));
        long ticks = Math.max(0, endTicks - state.startTicks);
        state.durationMillis = ticks / 1_000_000L;
        state.failure = Optional.ofNullable(completion.primaryFailure());
        state.secondaryFailures = List.copyOf(completion.secondaryFailures());
    }

    public <R> R require(TaskRef<?> consumer, TaskRef<R> producer) {
        TaskState state = accessState(consumer, producer);
        return switch (state.status) {
            case SUCCEEDED -> {
                if (state.slot instanceof ResultSlot.Value value) {
                    if (!producer.resultType().isInstance(value.value())) {
                        throw new ResultAccessException("RESULT_TYPE_MISMATCH",
                                "Ergebnis von " + producer.id() + " hat nicht den Typ "
                                        + producer.resultType().getName());
                    }
                    yield producer.resultType().cast(value.value());
                }
                throw new ResultAccessException("RESULT_NOT_PRODUCED",
                        "Task " + producer.id() + " did not return a value");
            }
            case SKIPPED -> throw new ResultAccessException("RESULT_NOT_PRODUCED",
                    "Task " + producer.id() + " was skipped");
            case FAILED, CANCELLED, NOT_RUN -> throw new ResultAccessException("RESULT_NOT_AVAILABLE",
                    "Task " + producer.id() + " did not complete successfully");
            case PENDING, RUNNING -> throw new ResultAccessException("RESULT_NOT_AVAILABLE",
                    "Task " + producer.id() + " has no result yet");
        };
    }

    public <R> Optional<R> optional(TaskRef<?> consumer, TaskRef<R> producer) {
        TaskState state = accessState(consumer, producer);
        return switch (state.status) {
            case SUCCEEDED -> {
                if (state.slot instanceof ResultSlot.Value value) {
                    if (!producer.resultType().isInstance(value.value())) {
                        throw new ResultAccessException("RESULT_TYPE_MISMATCH",
                                "Ergebnis von " + producer.id() + " hat nicht den Typ "
                                        + producer.resultType().getName());
                    }
                    yield Optional.of(producer.resultType().cast(value.value()));
                }
                yield Optional.empty();
            }
            case SKIPPED -> Optional.empty();
            case FAILED, CANCELLED, NOT_RUN, PENDING, RUNNING ->
                    throw new ResultAccessException("RESULT_NOT_AVAILABLE",
                            "Task " + producer.id() + " did not complete successfully or is still open");
        };
    }

    public TaskStatus status(TaskRef<?> ref) {
        return stateFor(ref).status;
    }

    public ResultSlot slot(TaskRef<?> ref) {
        return stateFor(ref).slot;
    }

    public TaskOutcome outcome(TaskRef<?> ref) {
        TaskState state = stateFor(ref);
        return new TaskOutcome(ref.id(), state.status, state.reason, state.slot,
                Optional.ofNullable(state.startedAt), state.finishedAt, state.durationMillis,
                state.invocations, state.failure, state.secondaryFailures, state.cancelReason, state.failurePhase);
    }

    public List<TaskOutcome> outcomes() {
        return plan.selectedTasks().stream().map(task -> outcome(task.ref())).toList();
    }

    private TaskState accessState(TaskRef<?> consumer, TaskRef<?> producer) {
        TaskDefinition<?> consumerDefinition = definition.task(consumer).orElseThrow(() ->
                new ResultAccessException("FOREIGN_OR_UNKNOWN_TASK_REF",
                        "Consumer-Referenz gehoert nicht zur JobDefinition: " + consumer));
        TaskDefinition<?> producerDefinition = definition.task(producer).orElseThrow(() ->
                new ResultAccessException("FOREIGN_OR_UNKNOWN_TASK_REF",
                        "Producer-Referenz gehoert nicht zur JobDefinition: " + producer));
        if (!definitionId.equals(plan.definitionId())) {
            throw new ResultAccessException("FOREIGN_OR_UNKNOWN_TASK_REF",
                    "Plan und Ergebnisablage gehoeren zu verschiedenen Definitionen");
        }
        Set<String> predecessors = plan.dataPredecessors(consumerDefinition.id());
        if (!predecessors.contains(producerDefinition.id())) {
            throw new ResultAccessException("UNDECLARED_RESULT_DEPENDENCY",
                    "Task " + consumerDefinition.id() + " reads " + producerDefinition.id()
                            + ", hat ihn aber nicht als Datenvorgaenger (dependsOn)");
        }
        TaskState state = states.get(producerDefinition.id());
        if (state == null) {
            throw new ResultAccessException("RESULT_NOT_AVAILABLE",
                    "Task " + producerDefinition.id() + " is not part of the selected plan");
        }
        return state;
    }

    private TaskState stateFor(TaskRef<?> ref) {
        TaskDefinition<?> task = definition.task(ref).orElseThrow(() ->
                new ResultAccessException("FOREIGN_OR_UNKNOWN_TASK_REF",
                        "TaskRef does not belong to the JobDefinition: " + ref));
        TaskState state = states.get(task.id());
        if (state == null) {
            throw new ResultAccessException("RESULT_NOT_AVAILABLE",
                    "Task " + task.id() + " is not part of the selected plan");
        }
        return state;
    }

    private static final class TaskState {
        private TaskStatus status = TaskStatus.PENDING;
        private ResultSlot slot = ResultSlot.NoValue.INSTANCE;
        private String reason;
        private Optional<ch.so.agi.gretl.runtime.api.CancelReason> cancelReason = Optional.empty();
        private String failurePhase;
        private Instant startedAt;
        private Optional<Instant> finishedAt = Optional.empty();
        private long startTicks;
        private long durationMillis;
        private Optional<FailureInfo> failure = Optional.empty();
        private List<FailureInfo> secondaryFailures = List.of();
        private final List<InvocationOutcome> invocations = new ArrayList<>();
    }
}
