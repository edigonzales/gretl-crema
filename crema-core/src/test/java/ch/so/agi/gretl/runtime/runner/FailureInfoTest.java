package ch.so.agi.gretl.runtime.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class FailureInfoTest {

    @Test
    void preservesCauseAndSuppressedGraph() {
        IOException root = new IOException("root");
        IllegalStateException outer = new IllegalStateException("outer", root);
        outer.addSuppressed(new IllegalArgumentException("suppressed"));

        FailureInfo info = FailureInfo.from(outer);

        assertEquals(IllegalStateException.class.getName(), info.type());
        assertEquals("outer", info.message());
        assertEquals(IOException.class.getName(), info.causes().get(0).type());
        assertEquals("root", info.causes().get(0).message());
        assertEquals(IllegalArgumentException.class.getName(), info.suppressed().get(0).type());
        assertEquals("suppressed", info.suppressed().get(0).message());
        assertEquals(info.id(), info.causes().get(0).id());
        assertFalse(info.truncated());
    }

    @Test
    void cyclicThrowableGraphTerminatesWithTruncation() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        FailureInfo info = FailureInfo.from(first);
        assertTrue(info.truncated());
        assertTrue(info.causes().get(0).causes().get(0).message()
                .contains("cyclic"));
    }

    @Test
    void longMessagesAreBounded() {
        String message = "x".repeat(3000);
        FailureInfo info = FailureInfo.from(new IllegalStateException(message));
        assertTrue(info.message().length() < 3000);
        assertTrue(info.message().endsWith("[truncated]"));
    }

    @Test
    void nullThrowableIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> FailureInfo.from(null));
    }
}
