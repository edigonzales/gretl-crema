package ch.so.agi.gretl.runtime.runner;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Common builders for M1 runner tests. */
final class RunnerTestSupport {
    private RunnerTestSupport() {
    }

    static JobDefinition definition(Path jobRoot, Consumer<JobDefinitionBuilder> configure) {
        JobDefinitionBuilder builder = new JobDefinitionBuilder(jobRoot);
        configure.accept(builder);
        return builder.freeze();
    }

    static ExecutionPlan plan(JobDefinition definition, String... targets) {
        return new ExecutionPlanner().plan(definition, java.util.List.of(targets));
    }

    static ResolvedParameters noParameters() {
        return new ResolvedParameters(Map.of(), Map.of(), java.util.List.of(), Set.of());
    }

    static RunInput.Builder input(Path tempDir, FakeMonotonicClock monotonic, RunEventSink sink) {
        return RunInput.builder()
                .startCwd(tempDir)
                .jobRoot(tempDir)
                .workRoot(tempDir.resolve("work"))
                .jobName("m1-test")
                .runId(UUID.randomUUID())
                .clock(Clock.fixed(Instant.parse("2026-10-18T10:00:00Z"), ZoneOffset.UTC))
                .monotonicClock(monotonic)
                .eventSink(sink)
                .parameters(noParameters());
    }

    static TaskRegistration.Builder<Object> task(String id) {
        return TaskRegistration.builder(id, Object.class);
    }
}
