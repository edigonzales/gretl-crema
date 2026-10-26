package ch.so.agi.gretl.runtime.runner;

import static org.junit.jupiter.api.Assertions.*;
import ch.so.agi.gretl.runtime.api.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Scope hooks are telemetry; neither opening nor closing is a business resource. */
class LoggingScopeTest {
    @TempDir Path root;
    @ParameterizedTest @CsvSource({"none,false", "open,false", "close,false", "close,true"})
    void noOpAndBrokenHooksKeepBusinessOutcomeAndCleanup(String fault, boolean businessFailure) {
        List<RunEvent> events = new ArrayList<>(); List<String> work = new ArrayList<>();
        RunEventSink sink = fault.equals("none") ? events::add : new RunEventSink() {
            public void emit(RunEvent event) { events.add(event); }
            public Scope openScope(ExecutionIdentity identity, Optional<String> operation) {
                if (fault.equals("open")) throw new IllegalStateException("scope-open");
                return () -> { throw new IllegalStateException("scope-close"); };
            }
        };
        var d = RunnerTestSupport.definition(root, b -> {
            b.register(RunnerTestSupport.task("work").body(c -> {
                work.add("body"); if (businessFailure) throw new IOException("original-business-error"); return null;
            }).build());
            b.cleanup("release", c -> work.add("cleanup")); b.defaults("work");
        });
        var result = new SequentialJobRunner().run(d, RunnerTestSupport.plan(d),
                RunnerTestSupport.input(root, new FakeMonotonicClock(), sink).build());
        assertEquals(businessFailure ? RunStatus.FAILED : RunStatus.SUCCEEDED, result.status());
        if (businessFailure) assertEquals("original-business-error", result.primaryFailure().orElseThrow().message());
        assertEquals(List.of("body", "cleanup"), work);
        assertEquals(1, events.stream().filter(e -> e.eventName().equals("gretl.run.finished")).count());
        assertEquals(fault.equals("none") ? 0 : 1,
                events.stream().filter(e -> e.eventName().equals("gretl.logging.degraded")).count());
    }
}
