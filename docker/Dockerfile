FROM ubuntu:24.04 AS tools
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    ca-certificates python3 gcc g++ zlib1g-dev libstdc++6 time procps iproute2 \
    && rm -rf /var/lib/apt/lists/*

FROM tools AS native-builder
COPY .tools/linux/graal/ /opt/graal/
COPY crema-runner/target/crema-runner-0.1.0-SNAPSHOT.jar /app/runtime/runner.jar
COPY crema-runner/target/lib/*.jar /app/runtime/lib/
COPY docker/ /app/bench/
WORKDIR /app

FROM native-builder AS runtime
COPY .tools/linux/jdk25/ /opt/jdk25/
COPY .tools/linux/jdk27/ /opt/jdk27/
COPY .tools/linux/jdk28/ /opt/jdk28/
COPY .tools/linux/crac25/ /opt/crac25/
COPY crema-compiler/target/crema-compiler-0.1.0-SNAPSHOT.jar /app/compiler/compiler.jar
COPY examples/demo/ /app/jobs/h2/
COPY examples/jit/hotloop.groovy /app/jobs/hotloop/hotloop.groovy
COPY tests/DatabaseCheck.java /app/support/DatabaseCheck.java
COPY docker/ /app/bench/
RUN mkdir -p /work /native /aot /checkpoint /results /app/support/classes \
    && /opt/jdk25/bin/javac -d /app/support/classes /app/support/DatabaseCheck.java \
    && /opt/jdk25/bin/javac -cp '/app/compiler/compiler.jar:/app/runtime/runner.jar:/app/runtime/lib/*' -d /app/support/classes /app/bench/CracLauncher.java \
    && /opt/jdk25/bin/java -cp '/app/compiler/compiler.jar:/app/runtime/runner.jar:/app/runtime/lib/*' ch.so.agi.gretl.crema.compiler.CompilerMain /app/jobs/h2/job.groovy --output /app/jobs/h2/job.jar \
    && /opt/jdk25/bin/java -cp '/app/compiler/compiler.jar:/app/runtime/runner.jar:/app/runtime/lib/*' ch.so.agi.gretl.crema.compiler.CompilerMain /app/jobs/hotloop/hotloop.groovy --output /app/jobs/hotloop/hotloop.jar
WORKDIR /app
ENV JAVA_HOME=/opt/graal
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8
