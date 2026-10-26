package ch.so.agi.gretl.runtime.runner;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Explicit wiring for one run. It is an internal runner type, not part of the
 * public gretl-api SPI.
 */
public final class RunInput {
    private final ResolvedParameters parameters;
    private final Map<String, Map<String, Object>> taskConfig;
    private final Path jobRoot;
    private final Path startCwd;
    private final Path workRoot;
    private final String jobName;
    private final UUID runId;
    private final KeepWork keepWork;
    private final Clock clock;
    private final MonotonicClock monotonicClock;
    private final RunEventSink eventSink;
    private final SecretResolver secretResolver;
    private final SecretRegistry secretRegistry;
    private final RunControl runControl;
    private final java.util.Locale locale;

    private RunInput(Builder builder) {
        this.parameters = builder.parameters;
        this.taskConfig = deepCopy(builder.taskConfig);
        this.startCwd = builder.startCwd.toAbsolutePath().normalize();
        this.jobRoot = builder.jobRoot == null
                ? startCwd : builder.jobRoot.toAbsolutePath().normalize();
        this.workRoot = builder.workRoot.toAbsolutePath().normalize();
        this.jobName = builder.jobName;
        this.runId = builder.runId;
        this.keepWork = builder.keepWork;
        this.clock = builder.clock;
        this.monotonicClock = builder.monotonicClock;
        this.eventSink = builder.eventSink;
        this.secretResolver = builder.secretResolver;
        this.secretRegistry = builder.secretRegistry;
        this.runControl = builder.runControl != null
                ? builder.runControl
                : new RunControl(builder.monotonicClock, builder.timeout);
        this.locale = builder.locale;
    }

    public static Builder builder() {
        return new Builder();
    }

    public ResolvedParameters parameters() {
        return parameters;
    }

    public Map<String, Map<String, Object>> taskConfig() {
        return taskConfig;
    }

    public Path jobRoot() {
        return jobRoot;
    }

    public Path startCwd() {
        return startCwd;
    }

    public Path workRoot() {
        return workRoot;
    }

    public String jobName() {
        return jobName;
    }

    public UUID runId() {
        return runId;
    }

    public KeepWork keepWork() {
        return keepWork;
    }

    public Clock clock() {
        return clock;
    }

    public MonotonicClock monotonicClock() {
        return monotonicClock;
    }

    public RunEventSink eventSink() {
        return eventSink;
    }

    public SecretResolver secretResolver() {
        return secretResolver;
    }

    public SecretRegistry secretRegistry() {
        return secretRegistry;
    }

    public RunControl runControl() {
        return runControl;
    }

    public java.util.Locale locale() {
        return locale;
    }

    private static Map<String, Map<String, Object>> deepCopy(
            Map<String, Map<String, Object>> source) {
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        source.forEach((alias, value) -> {
            if (alias == null || alias.isBlank()) {
                throw new IllegalArgumentException("taskConfig alias must not be blank");
            }
            copy.put(alias, ImmutableJson.map(value));
        });
        return java.util.Collections.unmodifiableMap(copy);
    }

    public static final class Builder {
        private ResolvedParameters parameters = new ResolvedParameters(
                Map.of(), Map.of(), java.util.List.of(), Set.of());
        private Map<String, Map<String, Object>> taskConfig = Map.of();
        private Path jobRoot;
        private Path startCwd = Path.of("").toAbsolutePath().normalize();
        private Path workRoot = Path.of(System.getProperty("java.io.tmpdir"), "gretl-work");
        private String jobName = "job";
        private UUID runId = UUID.randomUUID();
        private KeepWork keepWork = KeepWork.ON_FAILURE;
        private Clock clock = Clock.systemUTC();
        private MonotonicClock monotonicClock = SystemMonotonicClock.INSTANCE;
        private RunEventSink eventSink = RunEventSink.NO_OP;
        private SecretResolver secretResolver = SecretResolver.none();
        private SecretRegistry secretRegistry = new SecretRegistry();
        private RunControl runControl;
        private Optional<Duration> timeout = Optional.empty();
        private java.util.Locale locale = java.util.Locale.GERMAN;

        public Builder parameters(ResolvedParameters value) {
            this.parameters = Objects.requireNonNull(value, "parameters");
            return this;
        }

        public Builder taskConfig(Map<String, Map<String, Object>> value) {
            this.taskConfig = Objects.requireNonNull(value, "taskConfig");
            return this;
        }

        public Builder jobRoot(Path value) {
            this.jobRoot = Objects.requireNonNull(value, "jobRoot");
            return this;
        }

        public Builder startCwd(Path value) {
            this.startCwd = Objects.requireNonNull(value, "startCwd");
            return this;
        }

        public Builder workRoot(Path value) {
            this.workRoot = Objects.requireNonNull(value, "workRoot");
            return this;
        }

        public Builder jobName(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("jobName must not be blank");
            }
            this.jobName = value;
            return this;
        }

        public Builder runId(UUID value) {
            this.runId = Objects.requireNonNull(value, "runId");
            return this;
        }

        public Builder keepWork(KeepWork value) {
            this.keepWork = Objects.requireNonNull(value, "keepWork");
            return this;
        }

        public Builder clock(Clock value) {
            this.clock = Objects.requireNonNull(value, "clock");
            return this;
        }

        public Builder monotonicClock(MonotonicClock value) {
            this.monotonicClock = Objects.requireNonNull(value, "monotonicClock");
            return this;
        }

        public Builder eventSink(RunEventSink value) {
            this.eventSink = Objects.requireNonNull(value, "eventSink");
            return this;
        }

        public Builder secretResolver(SecretResolver value) {
            this.secretResolver = Objects.requireNonNull(value, "secretResolver");
            return this;
        }

        public Builder secretRegistry(SecretRegistry value) {
            this.secretRegistry = Objects.requireNonNull(value, "secretRegistry");
            return this;
        }

        public Builder runControl(RunControl value) {
            this.runControl = Objects.requireNonNull(value, "runControl");
            return this;
        }

        public Builder locale(java.util.Locale value) {
            this.locale = Objects.requireNonNull(value, "locale");
            return this;
        }

        public Builder timeout(Duration value) {
            if (value != null && (value.isZero() || value.isNegative())) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.timeout = Optional.ofNullable(value);
            return this;
        }

        public RunInput build() {
            return new RunInput(this);
        }
    }
}
