# Reconcile - Payment & Settlement Reconciliation Engine
#
# Decision D11: the build runs ONLY here, on Java 25. The local JDK 21 is never used to
# compile. `maven.compiler.release=25` means an older JDK fails loudly rather than quietly
# producing a build that does not match the pinned toolchain.

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /build

# Dependencies first: this layer is cached until the POM itself changes.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---------- runtime ----------
FROM eclipse-temurin:25-jre AS runtime

WORKDIR /app

# curl is installed solely because HEALTHCHECK needs an HTTP client. The temurin JRE image ships
# neither curl nor wget, so a healthcheck written against either one can never succeed and the
# container reports unhealthy forever while serving traffic perfectly well.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Never run as root.
RUN groupadd --system reconcile && useradd --system --gid reconcile --home /app reconcile

COPY --from=build /build/target/reconcile-*.jar /app/reconcile.jar
RUN chown -R reconcile:reconcile /app

USER reconcile

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

# The container is the health check target, so the image cannot report healthy while broken.
# Probes the actuator, which is unauthenticated and is wired to the real liveness state.
# (/api/v1/health is the operator-facing endpoint documented in 06-api-contract; it reports the
# deeper checks - migration version, L7 balance verification, stuck batches. An earlier draft of
# this comment said it was "not yet built"; it is built, tested by OperationsApiIT, and returns 503
# when a projection row goes missing. The actuator probe is still the right liveness target here
# because a container whose ledger no longer balances is running and should be restarted, not
# merely reported.)
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=5 \
  CMD ["sh", "-c", "curl -fsS http://127.0.0.1:8080/actuator/health || exit 1"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/reconcile.jar"]