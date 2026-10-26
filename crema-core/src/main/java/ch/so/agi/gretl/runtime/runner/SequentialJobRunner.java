package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.i18n.Messages;
import ch.so.agi.gretl.runtime.api.CancelReason;
import ch.so.agi.gretl.runtime.api.ExecutionIdentity;
import ch.so.agi.gretl.runtime.api.LogLevel;
import ch.so.agi.gretl.runtime.api.TaskCancelledException;
import ch.so.agi.gretl.runtime.api.TimeBudget;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Sequential, fail-fast job runner. One run owns one workspace and one result
 * store; tasks are never retried or resumed by the runner.
 */
public final class SequentialJobRunner {

    public JobOutcome run(JobDefinition definition, ExecutionPlan plan, RunInput input) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(input, "input");
        if (!definition.definitionId().equals(plan.definitionId())) {
            throw new IllegalArgumentException("execution plan belongs to another definition");
        }

        WorkspaceManager manager = new WorkspaceManager(input.workRoot());
        WorkspaceManager.Workspace workspace;
        try {
            workspace = manager.openRun(input.runId());
        } catch (IOException | SecurityException exception) {
            throw new RunStartException(exception);
        }

        RunEnvironment environment = new RunEnvironment(input, definition, plan, workspace);
        ResultStore results = new ResultStore(definition, plan);
        OperationInvoker invoker = new OperationInvoker(environment);
        FailureArbiter arbiter = new FailureArbiter();
        ExecutionIdentity runIdentity = environment.identity(null, null);
        try (var runScope = environment.openScope(runIdentity, Optional.empty())) {
            Instant startedAt = environment.now();
            long startTicks = environment.monotonicClock().ticks();

            environment.emit("gretl.run.started", LogLevel.INFO, runIdentity, Optional.empty(),
                    "run", null, 0,
                    Map.of("targets", String.join(",", plan.requestedTargets())),
                    Optional.empty());

            FailureInfo runPrimary = null;
            List<FailureInfo> runSecondary = new ArrayList<>();
            boolean failed = false;
            boolean cancelled = false;
            boolean finishRequested = false;
            String finishReason = null;

            List<TaskDefinition<?>> selected = plan.selectedTasks();
            int position = 0;
            for (; position < selected.size(); position++) {
                if (failed || cancelled || finishRequested) {
                    break;
                }
                try {
                    environment.control().checkBeforeWork();
                } catch (FinishSignal signal) {
                    finishRequested = true;
                    finishReason = signal.reason();
                    break;
                } catch (TaskCancelledException cancellation) {
                    cancelled = true;
                    break;
                }

                TaskOutcome outcome;
                try {
                    outcome = executeTask(selected.get(position), environment, workspace,
                            results, invoker, arbiter);
                } catch (IOException | SecurityException exception) {
                    TaskDefinition<?> task = selected.get(position);
                    results.complete(task.ref(), new TaskCompletion(TaskStatus.FAILED, ResultSlot.NoValue.INSTANCE, null,
                                    FailureInfo.from(exception), List.of(), Optional.empty(), "task-setup"),
                            environment.now(), environment.monotonicClock().ticks());
                    outcome = results.outcome(task.ref());
                    emitTaskTerminal(environment, task, outcome);
                }

                if (outcome.status() == TaskStatus.FAILED) {
                    failed = true;
                    runPrimary = outcome.failure().orElseThrow();
                    runSecondary.addAll(outcome.secondaryFailures());
                } else if (outcome.status() == TaskStatus.CANCELLED) {
                    cancelled = true;
                    runSecondary.addAll(outcome.secondaryFailures());
                }
                if (environment.control().isFinishRequested() && !finishRequested) {
                    finishRequested = true;
                    finishReason = environment.control().finishReason().orElse("JOB_FINISHED");
                }
                if (!failed) {
                    try {
                        environment.control().throwIfCancellationRequested();
                    } catch (TaskCancelledException cancellation) {
                        cancelled = true;
                    }
                }
            }

            if (failed || cancelled || finishRequested) {
                String reason = failed ? "FAIL_FAST"
                        : cancelled ? "CANCELLED_BEFORE_START" : "JOB_FINISHED";
                for (; position < selected.size(); position++) {
                    TaskDefinition<?> task = selected.get(position);
                    results.complete(task.ref(), TaskCompletion.notRun(reason),
                            environment.now(), environment.monotonicClock().ticks());
                    emitTaskTerminal(environment, task, results.outcome(task.ref()));
                }
            }

            boolean restoreInterrupt = Thread.interrupted() | environment.control().interrupted();
            try {
                List<FailureInfo> cleanupFailures = runCleanups(environment, workspace, definition);

                RunStatus status;
                FailureInfo primaryFailure = null;
                List<FailureInfo> secondaryFailures = new ArrayList<>();
                if (failed) {
                    status = RunStatus.FAILED;
                    primaryFailure = runPrimary;
                    secondaryFailures.addAll(runSecondary);
                    secondaryFailures.addAll(cleanupFailures);
                } else if (cancelled) {
                    status = RunStatus.CANCELLED;
                    secondaryFailures.addAll(runSecondary);
                    secondaryFailures.addAll(cleanupFailures);
                } else if (!cleanupFailures.isEmpty()) {
                    status = RunStatus.FAILED;
                    primaryFailure = cleanupFailures.get(0);
                    secondaryFailures.addAll(cleanupFailures.subList(1, cleanupFailures.size()));
                    secondaryFailures.addAll(runSecondary);
                } else if (finishRequested) {
                    status = RunStatus.NO_DATA;
                } else {
                    status = RunStatus.SUCCEEDED;
                }

                Instant finishedAt = environment.now();
                long endTicks = environment.monotonicClock().ticks();
                long durationMillis = Math.max(0, endTicks - startTicks) / 1_000_000L;
                boolean workRetained = false;
                try {
                    if (workspace != null) {
                        workRetained = manager.applyRetention(workspace, status, input.keepWork());
                    }
                } catch (IOException | SecurityException exception) {
                    workRetained = true;
                    secondaryFailures.add(FailureInfo.from(exception));
                    environment.emit("gretl.workspace.retention_failed", LogLevel.WARN,
                            runIdentity, Optional.empty(), "run", status.name(), 0,
                            Map.of("workspace.path", workspace.runRoot().toString()),
                            Optional.of(FailureInfo.from(exception)));
                }

                environment.emit("gretl.run.finished", runLevel(status), runIdentity, Optional.empty(),
                        "run", status.name(), durationMillis,
                        terminalAttributes(status == RunStatus.CANCELLED ? environment.control().snapshot().cancellationReason() : Optional.empty(),
                                Map.of("exit_code", Integer.toString(status.exitCode()), "targets", String.join(",", plan.requestedTargets()))),
                        Optional.ofNullable(primaryFailure));

                List<TaskOutcome> taskOutcomes = results.outcomes();
                JobOutcome outcome = new JobOutcome(
                        input.runId(), definition.definitionId(), input.jobName(),
                        status, status.exitCode(), plan.requestedTargets(),
                        taskOutcomes, environment.diagnostics(),
                        Optional.ofNullable(primaryFailure), secondaryFailures,
                        startedAt, finishedAt, durationMillis,
                        finishRequested, Optional.ofNullable(finishReason), workRetained,
                        status == RunStatus.CANCELLED ? environment.control().snapshot().cancellationReason() : Optional.empty());
                environment.close();
                return outcome;
            } finally {
                if (restoreInterrupt || environment.control().interrupted()) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private TaskOutcome executeTask(
            TaskDefinition<?> task, RunEnvironment environment,
            WorkspaceManager.Workspace workspace, ResultStore results,
            OperationInvoker invoker, FailureArbiter arbiter) throws IOException {
        TimeBudget budget = task.timeout() == null
                ? environment.control() : environment.control().childBudget(task.timeout());
        try (var taskScope = environment.openScope(environment.identity(task.id(), null), Optional.empty())) {
            results.start(task.ref(), environment.now(), environment.monotonicClock().ticks());
            environment.emit("gretl.task.started", LogLevel.INFO,
                    environment.identity(task.id(), null),
                    Optional.ofNullable(task.operation() == null ? null : task.operation().id()),
                    "task", null, 0,
                    Map.of("task.description", task.description()), Optional.empty());
            Path taskDirectory = workspace.taskDirectory(task.registrationIndex());
            TaskFrame frame = new TaskFrame(task, task.registrationIndex(), taskDirectory, budget);
            TaskContextImpl context = new TaskContextImpl(environment, task.ref(), frame,
                    taskDirectory, frame.closeStack(), budget, results, invoker);
            frame.context(context);

            FailureArbiter.WorkCompletion work;
            try {
                context.checkExecution();
                boolean enabled = task.condition() == null || task.condition().test(context);
                context.checkExecution();
                if (!enabled) {
                    work = new FailureArbiter.WorkCompletion.Skipped("PREDICATE_FALSE");
                    return completeTask(environment, task, frame, context, work, results, arbiter);
                }
                Object value = task.body().execute(context);
                context.checkExecution();
                if (value == null && task.operation() != null && task.resultType() != Void.class) {
                    work = new FailureArbiter.WorkCompletion.Failure(
                            new ProviderContractException("NULL_TASK_RESULT",
                                    Messages.format(environment.locale(), "runner.nullTaskResult",
                                            task.id(), task.resultType().getName())));
                } else if (value != null && !task.resultType().isInstance(value)) {
                    work = new FailureArbiter.WorkCompletion.Failure(
                            new ProviderContractException("TASK_RESULT_TYPE_MISMATCH",
                                    Messages.format(environment.locale(),
                                            "runner.taskResultMismatch", task.id(),
                                            value.getClass().getName(),
                                            task.resultType().getName())));
                } else {
                    work = new FailureArbiter.WorkCompletion.Value(value);
                }
            } catch (FinishSignal signal) {
                work = new FailureArbiter.WorkCompletion.Finished(signal.reason());
            } catch (TaskCancelledException cancellation) {
                environment.control().observeCancel(cancellation.reason());
                work = new FailureArbiter.WorkCompletion.Cancelled(
                        cancellation.getCause() != null || cancellation.getSuppressed().length > 0
                                ? cancellation : null);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                environment.control().observeCancel(CancelReason.INTERRUPTED);
                work = new FailureArbiter.WorkCompletion.Cancelled(interrupted);
            } catch (Throwable failure) {
                FailureArbiter.rethrowFatal(failure);
                work = environment.control().cancellationObserved()
                        && !frame.failureBeforeCancellation(failure)
                        ? new FailureArbiter.WorkCompletion.Cancelled(failure)
                        : new FailureArbiter.WorkCompletion.Failure(failure);
            }
            return completeTask(environment, task, frame, context, work, results, arbiter);
        }
    }

    private TaskOutcome completeTask(
            RunEnvironment environment, TaskDefinition<?> task, TaskFrame frame,
            TaskContextImpl context, FailureArbiter.WorkCompletion work,
            ResultStore results, FailureArbiter arbiter) {
        List<Throwable> closeErrors = context.closeResources();
        TaskCompletion completion = arbiter.resolve(
                work, environment.control().snapshot(), closeErrors);
        context.markClosed(completion.cancelReason());
        for (InvocationOutcome invocation : frame.invocations()) {
            results.addInvocation(task.ref(), invocation);
        }
        results.complete(task.ref(), completion,
                environment.now(), environment.monotonicClock().ticks());
        TaskOutcome outcome = results.outcome(task.ref());
        emitTaskTerminal(environment, task, outcome);
        return outcome;
    }

    private static void emitTaskTerminal(
            RunEnvironment environment, TaskDefinition<?> task, TaskOutcome outcome) {
        String eventName = switch (outcome.status()) {
            case SUCCEEDED -> "gretl.task.finished";
            case SKIPPED -> "gretl.task.skipped";
            case FAILED -> "gretl.task.failed";
            case CANCELLED -> "gretl.task.cancelled";
            case NOT_RUN -> "gretl.task.not_run";
            case PENDING, RUNNING -> "gretl.task.unknown";
        };
        LogLevel level = switch (outcome.status()) {
            case FAILED -> LogLevel.ERROR;
            case CANCELLED -> LogLevel.WARN;
            default -> LogLevel.INFO;
        };
        Map<String, String> attributes = new java.util.LinkedHashMap<>();
        attributes.put("task.description", task.description());
        if (outcome.reason() != null) {
            attributes.put("reason", outcome.reason());
        }
        environment.emit(eventName, level, environment.identity(task.id(), null),
                Optional.ofNullable(task.operation() == null ? null : task.operation().id()),
                outcome.failurePhase() == null ? "task" : outcome.failurePhase(), outcome.status().name(), outcome.durationMillis(),
                terminalAttributes(outcome.cancelReason(), attributes),
                outcome.failure());
    }

    private List<FailureInfo> runCleanups(
            RunEnvironment environment, WorkspaceManager.Workspace workspace,
            JobDefinition definition) {
        List<FailureInfo> failures = new ArrayList<>();
        var completionBudget = environment.control().completionBudget();
        List<CleanupDefinition> cleanups = definition.cleanups();
        for (int index = cleanups.size() - 1; index >= 0; index--) {
            CleanupDefinition cleanup = cleanups.get(index);
            Path cleanupDirectory = environment.input().workRoot()
                    .resolve(environment.runId().toString())
                    .resolve("cleanup-%03d".formatted(cleanup.registrationIndex()));
            CloseStack closeStack = new CloseStack();
            CleanupContextImpl context = new CleanupContextImpl(environment, cleanupDirectory,
                    closeStack, completionBudget, cleanup.name(), () -> {
                        workspace.cleanupDirectory(cleanup.registrationIndex());
                    });
            ExecutionIdentity identity = environment.identity(null, null);
            environment.emit("gretl.cleanup.started", LogLevel.INFO, identity,
                    Optional.empty(), "cleanup", null, 0,
                    Map.of("cleanup.name", cleanup.name()), Optional.empty());
            Throwable primary = null;
            try {
                context.checkExecution();
                cleanup.body().execute(context);
                context.checkExecution();
            } catch (Throwable failure) {
            FailureArbiter.rethrowFatal(failure);
                primary = failure;
            }
            List<Throwable> closeErrors = context.closeResources();
            context.markClosed(Optional.empty());
            if (primary != null) {
                for (Throwable closeError : closeErrors) {
                    if (closeError != primary) {
                        primary.addSuppressed(closeError);
                    }
                }
            } else if (!closeErrors.isEmpty()) {
                primary = closeErrors.get(0);
                for (int errorIndex = 1; errorIndex < closeErrors.size(); errorIndex++) {
                    if (closeErrors.get(errorIndex) != primary) {
                        primary.addSuppressed(closeErrors.get(errorIndex));
                    }
                }
            }
            if (primary == null) {
                environment.emit("gretl.cleanup.finished", LogLevel.INFO, identity,
                        Optional.empty(), "cleanup", "SUCCEEDED", 0,
                        Map.of("cleanup.name", cleanup.name()), Optional.empty());
            } else {
                FailureInfo failure = FailureInfo.from(primary);
                failures.add(failure);
                for (Throwable closeError : closeErrors) {
                    if (closeError != primary) {
                        failures.add(FailureInfo.from(closeError));
                    }
                }
                environment.emit("gretl.cleanup.failed", LogLevel.ERROR, identity,
                        Optional.empty(), "cleanup", "FAILED", 0,
                        Map.of("cleanup.name", cleanup.name()), Optional.of(failure));
            }
        }
        return failures;
    }

    static Map<String,String> terminalAttributes(Optional<CancelReason> reason, Map<String,String> attributes) {
        Map<String,String> result = new java.util.LinkedHashMap<>(attributes);
        reason.ifPresent(value -> result.put("cancel_reason", value.name()));
        return result;
    }

    private static LogLevel runLevel(RunStatus status) {
        return switch (status) {
            case SUCCEEDED, NO_DATA -> LogLevel.INFO;
            case CANCELLED -> LogLevel.WARN;
            case FAILED -> LogLevel.ERROR;
        };
    }
}
