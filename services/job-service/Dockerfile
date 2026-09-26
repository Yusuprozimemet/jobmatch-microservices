# Built as the gateway is: its own folder is the build context, so it sees nothing of
# backend/ (which is why job-service copies what it uses from shared, Day 17).
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
# Not published by compose: the gateway and the monolith reach it across the network.
EXPOSE 8080
# The management port. Publish it only where a scraper can reach it - never to the internet.
EXPOSE 9090
ENV OTEL_SERVICE_NAME=jobmatch-job-service
# No -javaagent. The OpenTelemetry Java agent produces no HTTP server span on Spring
# Framework 7 and Tomcat 11 (see backend/Dockerfile for the full rationale).
CMD ["java", "-jar", "app.jar"]
