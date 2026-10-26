package ch.so.agi.gretl.runtime.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.so.agi.gretl.runtime.api.OperationProvider;
import ch.so.agi.gretl.runtime.api.OperationType;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OperationRegistryTest {
    private static final AtomicInteger HANDLERS = new AtomicInteger();

    @Test
    void loadsTheServiceProviderAndFindsByIdAndSymbol() {
        OperationRegistry registry = OperationRegistry.load(getClass().getClassLoader());

        assertEquals(1, registry.all().size());
        OperationType<?, ?> operation = registry.requireSymbol("echo");
        assertEquals("test.echo", operation.id());
        assertSame(operation, registry.requireId("test.echo"));
        assertTrue(registry.origin(operation).orElseThrow()
                .contains("TestOperationProvider"));
    }

    @Test
    void duplicateIdsAreRejectedWithBothOrigins() {
        OperationProvider first = provider(TestOperations.echo("duplicate", HANDLERS));
        OperationProvider second = provider(TestOperations.voidOperation("duplicate", HANDLERS));

        DefinitionException exception = assertThrows(DefinitionException.class,
                () -> OperationRegistry.fromProviders(List.of(first, second)));
        assertEquals("DUPLICATE_OPERATION_ID", exception.code());
        assertTrue(exception.getMessage().contains("Doppelte"));
    }

    @Test
    void duplicateSymbolsAreRejected() {
        OperationProvider first = provider(TestOperations.echo("one", HANDLERS));
        OperationProvider second = provider(TestOperations.echo("two", HANDLERS));

        DefinitionException exception = assertThrows(DefinitionException.class,
                () -> OperationRegistry.fromProviders(List.of(first, second)));
        assertEquals("DUPLICATE_OPERATION_SYMBOL", exception.code());
    }

    @Test
    void unknownSymbolAndIdAreClearErrors() {
        OperationRegistry registry = OperationRegistry.fromProviders(List.of());
        assertEquals(0, registry.all().size());
        assertEquals("UNKNOWN_OPERATION",
                assertThrows(DefinitionException.class, () -> registry.requireSymbol("Nope")).code());
        assertEquals("UNKNOWN_OPERATION",
                assertThrows(DefinitionException.class, () -> registry.requireId("nope")).code());
    }

    @Test
    void nullProviderOutputIsAProviderDefect() {
        OperationProvider broken = new OperationProvider() {
            @Override
            public Collection<OperationType<?, ?>> operations() {
                return null;
            }
        };
        DefinitionException exception = assertThrows(DefinitionException.class,
                () -> OperationRegistry.fromProviders(List.of(broken)));
        assertEquals("OPERATION_PROVIDER_DEFECT", exception.code());
    }

    @Test
    void nullDefinitionIsAProviderDefect() {
        OperationProvider broken = provider(null);
        DefinitionException exception = assertThrows(DefinitionException.class,
                () -> OperationRegistry.fromProviders(List.of(broken)));
        assertEquals("OPERATION_PROVIDER_DEFECT", exception.code());
    }

    private static OperationProvider provider(OperationType<?, ?> operation) {
        return new OperationProvider() {
            @Override
            public Collection<OperationType<?, ?>> operations() {
                return java.util.Collections.singletonList(operation);
            }
        };
    }
}
