package ch.so.agi.gretl.runtime.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionPlannerTest {

    @TempDir
    Path tempDir;

    @Test
    void selectsExactlyTheTransitiveDependencyHull() {
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("a").body(context -> "a").build());
            builder.register(RunnerTestSupport.task("b").dependsOn("a")
                    .body(context -> "b").build());
            builder.register(RunnerTestSupport.task("c").body(context -> "c").build());
            builder.defaults("b");
        });

        ExecutionPlan plan = RunnerTestSupport.plan(definition);

        assertEquals(List.of("a", "b"), plan.selectedTasks().stream()
                .map(TaskDefinition::id).toList());
        assertFalse(plan.contains("c"));
        assertEquals(List.of("b"), plan.requestedTargets());
        assertEquals(List.of("a"), plan.dataPredecessors("b").stream().toList());
    }

    @Test
    void independentTargetsUseRegistrationOrder() {
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("first").body(context -> "1").build());
            builder.register(RunnerTestSupport.task("second").body(context -> "2").build());
            builder.register(RunnerTestSupport.task("third").body(context -> "3").build());
            builder.defaults("third", "first", "second");
        });

        ExecutionPlan plan = RunnerTestSupport.plan(definition);

        assertEquals(List.of("first", "second", "third"), plan.selectedTasks().stream()
                .map(TaskDefinition::id).toList());
    }

    @Test
    void afterDoesNotPullAnUnselectedTaskIntoThePlan() {
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("a").body(context -> "a").build());
            builder.register(RunnerTestSupport.task("b").after("a")
                    .body(context -> "b").build());
            builder.register(RunnerTestSupport.task("c").body(context -> "c").build());
            builder.defaults("b", "c");
        });

        ExecutionPlan plan = RunnerTestSupport.plan(definition);
        assertEquals(List.of("b", "c"), plan.selectedTasks().stream()
                .map(TaskDefinition::id).toList());
        assertFalse(plan.contains("a"));
        assertTrue(plan.dataPredecessors("b").isEmpty(),
                "after must not become a data dependency");
    }

    @Test
    void afterOrdersTwoSelectedTasksWithoutDependencySelection() {
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("earlier").body(context -> "earlier").build());
            builder.register(RunnerTestSupport.task("later").body(context -> "later").build());
            builder.register(RunnerTestSupport.task("last").after("earlier")
                    .body(context -> "last").build());
            builder.defaults("later", "last", "earlier");
        });

        ExecutionPlan plan = RunnerTestSupport.plan(definition);

        assertEquals(List.of("earlier", "later", "last"), plan.selectedTasks().stream()
                .map(TaskDefinition::id).toList());
        assertTrue(plan.dataPredecessors("last").isEmpty());
        assertTrue(plan.edges().stream().anyMatch(edge ->
                edge.fromId().equals("earlier") && edge.toId().equals("last")
                        && edge.type() == PlanEdge.EdgeType.AFTER));
    }

    @Test
    void duplicateExplicitTargetsAreReducedDeterministically() {
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("a").body(context -> "a").build());
            builder.register(RunnerTestSupport.task("b").body(context -> "b").build());
        });

        ExecutionPlan plan = new ExecutionPlanner().plan(definition,
                List.of("b", "a", "b", "a"));

        assertEquals(List.of("b", "a"), plan.requestedTargets());
        assertEquals(List.of("a", "b"), plan.selectedTasks().stream()
                .map(TaskDefinition::id).toList());
    }

    @Test
    void missingTargetsAreAConfigurationError() {
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder ->
                builder.register(RunnerTestSupport.task("a").body(context -> "a").build()));

        PlanException exception = assertThrows(PlanException.class,
                () -> RunnerTestSupport.plan(definition));
        assertEquals("MISSING_TARGET", exception.code());
    }

    @Test
    void unknownTargetIsAConfigurationError() {
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder ->
                builder.register(RunnerTestSupport.task("a").body(context -> "a").build()));

        PlanException exception = assertThrows(PlanException.class,
                () -> RunnerTestSupport.plan(definition, "missing"));
        assertEquals("UNKNOWN_TARGET", exception.code());
    }

    @Test
    void fullDependsOnGraphIsCheckedEvenWhenTheCycleIsUnselected() {
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("a").dependsOn("b")
                    .body(context -> "a").build());
            builder.register(RunnerTestSupport.task("b").dependsOn("a")
                    .body(context -> "b").build());
            builder.register(RunnerTestSupport.task("target").body(context -> "t").build());
            builder.defaults("target");
        });

        PlanException exception = assertThrows(PlanException.class,
                () -> RunnerTestSupport.plan(definition));
        assertEquals("DEPENDENCY_CYCLE", exception.code());
        assertTrue(exception.getMessage().contains("DEPENDS_ON"));
    }

    @Test
    void selectedAfterCycleIsReportedWithEdgeKinds() {
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("a").after("b")
                    .body(context -> "a").build());
            builder.register(RunnerTestSupport.task("b").after("a")
                    .body(context -> "b").build());
            builder.defaults("a", "b");
        });

        PlanException exception = assertThrows(PlanException.class,
                () -> RunnerTestSupport.plan(definition));
        assertEquals("PLAN_CYCLE", exception.code());
        assertTrue(exception.getMessage().contains("AFTER"));
    }

    @Test
    void selfDependencyIsACycle() {
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("a").dependsOn("a")
                    .body(context -> "a").build());
            builder.defaults("a");
        });

        PlanException exception = assertThrows(PlanException.class,
                () -> RunnerTestSupport.plan(definition));
        assertEquals("DEPENDENCY_CYCLE", exception.code());
    }
}
