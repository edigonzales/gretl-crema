package ch.so.agi.gretl.runtime.runner;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartBoundaryTest {
    @TempDir Path root;
    @Test void rejectedLeafIsNeverABegunRun() throws Exception {
        for (String kind : List.of("directory", "file", "symlink")) {
            Path work = Files.createDirectory(root.resolve(kind));
            UUID id = UUID.randomUUID();
            Path leaf = work.resolve(id.toString());
            if (kind.equals("directory")) Files.createDirectory(leaf);
            else if (kind.equals("file")) Files.writeString(leaf, "keep");
            else Files.createSymbolicLink(leaf, Files.createDirectory(root.resolve("target")));
            AtomicInteger cleanups = new AtomicInteger();
            var d = RunnerTestSupport.definition(root, b -> {
                b.register(RunnerTestSupport.task("never").body(c -> fail("body must not run")).build());
                b.cleanup("never", c -> cleanups.incrementAndGet()); b.defaults("never");
            });
            RecordingEventSink sink = new RecordingEventSink();
            RuntimeException failure = assertThrows(RuntimeException.class, () -> new SequentialJobRunner().run(d,
                    RunnerTestSupport.plan(d), RunnerTestSupport.input(root, new FakeMonotonicClock(), sink)
                            .workRoot(work).runId(id).build()));
            assertEquals("RunStartException", failure.getClass().getSimpleName());
            assertEquals(0, cleanups.get());
            assertEquals(0, sink.count("gretl.run.started"));
            assertEquals(0, sink.count("gretl.run.finished"));
            assertTrue(Files.exists(leaf));
        }
    }
}
