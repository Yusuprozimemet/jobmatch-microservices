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

# Stage 2: run the JAR (JRE is enough)
FROM eclipse-temurin:25-jre
USER 1000
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
# The management port. Publish it only where a scraper can reach it - never to the internet.
EXPOSE 9090
ENV SPRING_PROFILES_DEFAULT=prod

# No -javaagent. The OpenTelemetry Java agent produces no HTTP server span on Spring
# Framework 7 and Tomcat 11, and - measured, not assumed - it also stops Spring's own
# instrumentation producing one. With the agent attached, /api/jobs yields only loose JDBC
# spans; with it removed, the same request yields `GET /api/jobs` with those calls beneath
# it. Traces now come from spring-boot-starter-opentelemetry, configured in
# application.yaml. Revisit when the agent supports this stack.
ENV OTEL_SERVICE_NAME=jobmatch-backend

CMD ["java", "-jar", "app.jar"]
