package ch.so.agi.gretl.runtime.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CloseStackTest {

    @Test
    void closesResourcesInReverseOrderExactlyOnce() {
        List<String> order = new ArrayList<>();
        CloseStack stack = new CloseStack();
        stack.manage(() -> order.add("first"));
        stack.manage(() -> order.add("second"));
        stack.manage(() -> order.add("third"));

        assertTrue(stack.closeAll().isEmpty());
        assertEquals(List.of("third", "second", "first"), order);
        assertTrue(stack.isClosed());
        assertTrue(stack.closeAll().isEmpty());
        assertEquals(List.of("third", "second", "first"), order);
    }

    @Test
    void duplicateObjectIdentityIsRejectedButDifferentObjectsAreAllowed() {
        CloseStack stack = new CloseStack();
        AutoCloseable resource = () -> {};
        assertSame(resource, stack.manage(resource));
        assertThrows(IllegalArgumentException.class, () -> stack.manage(resource));
        stack.manage(() -> {});
        stack.closeAll();
    }

    @Test
    void allResourcesAreAttemptedAndRealErrorsAreReturned() {
        List<String> order = new ArrayList<>();
        CloseStack stack = new CloseStack();
        stack.manage(() -> order.add("first"));
        stack.manage(() -> {
            order.add("second");
            throw new IllegalStateException("second-close");
        });
        stack.manage(() -> {
            order.add("third");
            throw new java.io.IOException("third-close");
        });

        List<Throwable> errors = stack.closeAll();
        assertEquals(List.of("third", "second", "first"), order);
        assertEquals(2, errors.size());
        assertEquals(java.io.IOException.class, errors.get(0).getClass());
        assertEquals(IllegalStateException.class, errors.get(1).getClass());
    }

    @Test
    void managementAfterCloseIsRejected() {
        CloseStack stack = new CloseStack();
        stack.closeAll();
        assertThrows(IllegalStateException.class, () -> stack.manage(() -> {}));
    }
}
