package ch.so.agi.gretl.runtime.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.so.agi.gretl.runtime.api.TaskRef;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobDefinitionBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void forwardReferencesByNameResolveAtFreeze() {
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("a").dependsOn("b")
                    .body(context -> "a").build());
            builder.register(RunnerTestSupport.task("b").body(context -> "b").build());
            builder.defaults("a");
        });

        TaskDefinition<?> a = definition.task("a").orElseThrow();
        assertEquals(List.of("b"), a.dependsOn().stream().map(TaskRef::id).toList());
        assertEquals(List.of("a"), definition.defaultTargets());
    }

    @Test
    void duplicateTaskIdIsRejectedImmediately() {
        JobDefinitionBuilder builder = new JobDefinitionBuilder(tempDir);
        builder.register(RunnerTestSupport.task("a").body(context -> "a").build());
        DefinitionException exception = assertThrows(DefinitionException.class,
                () -> builder.register(RunnerTestSupport.task("a").body(context -> "b").build()));
        assertEquals("DUPLICATE_TASK_ID", exception.code());
    }

    @Test
    void unknownLinkIsRejectedAtFreeze() {
        JobDefinitionBuilder builder = new JobDefinitionBuilder(tempDir);
        builder.register(RunnerTestSupport.task("a").dependsOn("missing")
                .body(context -> "a").build());
        DefinitionException exception = assertThrows(DefinitionException.class, builder::freeze);
        assertEquals("UNKNOWN_TASK_REFERENCE", exception.code());
    }

    @Test
    void foreignTaskRefIsRejectedAtFreeze() {
        JobDefinitionBuilder other = new JobDefinitionBuilder(tempDir);
        TaskRef<Object> foreign = other.register(
                RunnerTestSupport.task("foreign").body(context -> "x").build());
        JobDefinitionBuilder builder = new JobDefinitionBuilder(tempDir);
        builder.register(RunnerTestSupport.task("a").dependsOn(foreign)
                .body(context -> "a").build());
        DefinitionException exception = assertThrows(DefinitionException.class, builder::freeze);
        assertEquals("FOREIGN_TASK_REFERENCE", exception.code());
    }

    @Test
    void duplicateLinkIsRejectedAtFreeze() {
        JobDefinitionBuilder builder = new JobDefinitionBuilder(tempDir);
        builder.register(RunnerTestSupport.task("a").body(context -> "a").build());
        builder.register(RunnerTestSupport.task("b").dependsOn("a", "a")
                .body(context -> "b").build());
        DefinitionException exception = assertThrows(DefinitionException.class, builder::freeze);
        assertEquals("DUPLICATE_TASK_LINK", exception.code());
    }

    @Test
    void registrationAfterFreezeIsRejected() {
        JobDefinitionBuilder builder = new JobDefinitionBuilder(tempDir);
        builder.register(RunnerTestSupport.task("a").body(context -> "a").build());
        builder.freeze();
        DefinitionException exception = assertThrows(DefinitionException.class,
                () -> builder.register(RunnerTestSupport.task("b").body(context -> "b").build()));
        assertEquals("DYNAMIC_TASK_REGISTRATION_UNSUPPORTED", exception.code());
    }

    @Test
    void secondFreezeIsRejected() {
        JobDefinitionBuilder builder = new JobDefinitionBuilder(tempDir);
        builder.register(RunnerTestSupport.task("a").body(context -> "a").build());
        JobDefinition first = builder.freeze();
        assertEquals(first.definitionId(), builder.definitionId());
        DefinitionException exception = assertThrows(DefinitionException.class, builder::freeze);
        assertEquals("DEFINITION_ALREADY_FROZEN", exception.code());
    }

    @Test
    void unknownDefaultTargetIsRejectedAtFreeze() {
        JobDefinitionBuilder builder = new JobDefinitionBuilder(tempDir);
        builder.register(RunnerTestSupport.task("a").body(context -> "a").build());
        builder.defaults("missing");
        DefinitionException exception = assertThrows(DefinitionException.class, builder::freeze);
        assertEquals("UNKNOWN_DEFAULT_TARGET", exception.code());
    }

    @Test
    void parametersMustBeUniqueAndDescriptionsAreRequired() {
        JobDefinitionBuilder builder = new JobDefinitionBuilder(tempDir);
        builder.registerParameter(new ParameterDefinition("p", ParameterValueType.TEXT,
                "Beschreibung", false, Optional.empty()));
        ParameterException duplicate = assertThrows(ParameterException.class,
                () -> builder.registerParameter(new ParameterDefinition("p",
                        ParameterValueType.TEXT, "Andere", false, Optional.empty())));
        assertEquals("DUPLICATE_PARAMETER", duplicate.code());

        ParameterException noDescription = assertThrows(ParameterException.class,
                () -> new ParameterDefinition("q", ParameterValueType.TEXT,
                        " ", false, Optional.empty()));
        assertEquals("INVALID_PARAMETER_DECLARATION", noDescription.code());
    }

    @Test
    void cleanupNamesAreUnique() {
        JobDefinitionBuilder builder = new JobDefinitionBuilder(tempDir);
        builder.cleanup("cleanup", context -> {});
        DefinitionException exception = assertThrows(DefinitionException.class,
                () -> builder.cleanup("cleanup", context -> {}));
        assertEquals("DUPLICATE_CLEANUP_NAME", exception.code());
    }

    @Test
    void frozenDefinitionIsImmutable() {
        JobDefinition definition = RunnerTestSupport.definition(tempDir, builder -> {
            builder.register(RunnerTestSupport.task("a").body(context -> "a").build());
            builder.defaults("a");
        });
        assertThrows(UnsupportedOperationException.class, () -> definition.tasks().clear());
        assertThrows(UnsupportedOperationException.class, () -> definition.defaultTargets().clear());
        assertTrue(definition.task("a").isPresent());
        assertTrue(definition.task("missing").isEmpty());
    }
}
