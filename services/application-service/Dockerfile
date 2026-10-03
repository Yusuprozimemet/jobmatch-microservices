# Built as the gateway is: its own folder is the build context, so it sees nothing of
# backend/ (which is why application-service copies what it uses from shared, as job-service
# did on Day 17 and matching-service on Day 21).
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app

# Dependencies first, keyed on the pom alone, so a source change does not download them again.
COPY pom.xml .
RUN mvn -B -e dependency:go-offline

COPY src src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:25-jre
USER 1000
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# Not published by compose: the gateway and job-service reach it across the network.
EXPOSE 8080
# The management port. Publish it only where a scraper can reach it - never to the internet.
EXPOSE 9090
ENV OTEL_SERVICE_NAME=jobmatch-application-service
# No -javaagent. The OpenTelemetry Java agent produces no HTTP server span on Spring
# Framework 7 and Tomcat 11 (see backend/Dockerfile for the full rationale).
# The service does not start without INTERNAL_IDENTITY_URL and INTERNAL_JOBS_URL
# (see application.yaml); compose and the harness pass them.
CMD ["java", "-jar", "app.jar"]
