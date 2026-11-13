# Build the jar first (./mvnw -B verify), then: docker build -t reference-app .
#
# Stopping: the application drains for up to 30s (spring.lifecycle.timeout-per-shutdown-phase), and
# Docker kills a container 10s after SIGTERM by default. Give it longer than the drain:
# `docker run --stop-timeout 40`, compose `stop_grace_period: 40s`, Kubernetes
# `terminationGracePeriodSeconds: 40`, ECS `stopTimeout: 40`.
FROM eclipse-temurin:25-jdk-noble AS layers
WORKDIR /build
COPY target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# A JDK, not a JRE: jcmd and JFR must be at hand when an incident needs a thread dump or a recording.
FROM eclipse-temurin:25-jdk-noble
# curl for the HEALTHCHECK; the Temurin image doesn't ship it.
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app && useradd --system --gid app --no-create-home app \
    && mkdir /diagnostics && chown app:app /diagnostics
WORKDIR /app
COPY --from=layers /build/extracted/dependencies/ ./
COPY --from=layers /build/extracted/spring-boot-loader/ ./
COPY --from=layers /build/extracted/snapshot-dependencies/ ./
COPY --from=layers /build/extracted/application/ ./
USER app
# Heap dumps hold tokens and personal data: mount /diagnostics as a private volume.
VOLUME /diagnostics
# JDK_JAVA_OPTIONS is read by the java launcher only, so jcmd run in the container doesn't inherit
# these flags. 70% of the container limit leaves room for metaspace, threads and buffers.
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError"
# The application port. The management port (8081: health, metrics) is never published publicly.
EXPOSE 8080
# Liveness, not readiness: under overload readiness goes OUT_OF_SERVICE on purpose (the load balancer
# sends traffic elsewhere), and a restart then would only add to the outage.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8081/actuator/health/liveness || exit 1
# Each start writes its diagnostics to a directory of its own: the JVM is always PID 1 here, so a
# fixed java_pid1.hprof would already exist after the first out-of-memory restart and the next dump
# would be skipped. exec keeps java as PID 1, so it receives SIGTERM and drains.
ENTRYPOINT ["/bin/sh", "-c", "dir=/diagnostics/$(date -u +%Y%m%dT%H%M%SZ)-$(hostname) && mkdir -p \"$dir\" && exec java -XX:HeapDumpPath=\"$dir\" -XX:ErrorFile=\"$dir\"/hs_err_%p.log -Xlog:gc*:file=\"$dir\"/gc.log:time,uptime,level,tags:filecount=5,filesize=20m org.springframework.boot.loader.launch.JarLauncher"]
