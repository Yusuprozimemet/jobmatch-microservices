# Built as the backend is (backend/Dockerfile): compile once on the build machine's own
# architecture, run on a JRE.
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
# Compose publishes it on 8080 (Day 16).
EXPOSE 8081
# One JSON object per line in the image (Day 42), as identity's prod profile does; tests and a
# local spring-boot:run keep the readable format. Micrometer Tracing puts traceId and spanId in MDC.
ENV LOGGING_STRUCTURED_FORMAT_CONSOLE=logstash
CMD ["java", "-jar", "app.jar"]
