# Stage 1: build the JAR (needs JDK, Maven and your source code).
# --platform=$BUILDPLATFORM pins this stage to the machine doing the build, so a
# multi-architecture build compiles once natively instead of once per architecture
# under emulation. Java bytecode is the same everywhere, so the JAR is portable.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app

# Dependencies first, keyed on pom.xml alone. Docker reuses this layer for as long as the
# pom does not change, so editing a source file no longer re-downloads the dependency tree
# on every commit. That download is what kept tripping Maven Central's rate limit and
# turning the build red on changes that never touched a dependency.
COPY pom.xml .
RUN mvn -B -e dependency:go-offline

# Sources last, because they change on every commit and invalidate whatever follows.
# checkstyle.xml is not needed by `package` today - the plugin has no phase binding - but
# it is copied so that binding it later does not break the image build.
COPY checkstyle.xml ./
COPY src ./src
RUN mvn -B package -DskipTests

# The OpenTelemetry Java agent, fetched in its own stage so the version is pinned in one
# place and the download is cached like any other layer. The agent instruments Spring MVC,
# JDBC and outgoing HTTP without a line of application code, which is the whole reason it
# is an agent and not a library.
FROM --platform=$BUILDPLATFORM curlimages/curl:8.11.1 AS otel
ARG OTEL_AGENT_VERSION=2.11.0
RUN curl -sSLo /tmp/opentelemetry-javaagent.jar     https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar

# Stage 3: run the JAR (JRE is enough)
FROM eclipse-temurin:25-jre
USER 1000
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
COPY --from=otel /tmp/opentelemetry-javaagent.jar opentelemetry-javaagent.jar
EXPOSE 8080
# The management port. Publish it only where a scraper can reach it - never to the internet.
EXPOSE 9090
ENV SPRING_PROFILES_DEFAULT=prod

# Telemetry is off unless somewhere to send it is configured. The agent otherwise defaults
# to a collector on localhost and retries against nothing, filling the logs on every
# deployment that has no collector. Turn it on by setting OTEL_SDK_DISABLED=false and
# OTEL_EXPORTER_OTLP_ENDPOINT together - docker-compose's obs profile does exactly that.
ENV OTEL_SDK_DISABLED=true
ENV OTEL_SERVICE_NAME=jobmatch-backend
# Metrics come from Micrometer through the actuator, so the agent only carries traces.
ENV OTEL_METRICS_EXPORTER=none
ENV OTEL_LOGS_EXPORTER=none

CMD ["java", "-javaagent:opentelemetry-javaagent.jar", "-jar", "app.jar"]
