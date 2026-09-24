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
# 8081 until Day 16 moves the gateway to 8080.
EXPOSE 8081
CMD ["java", "-jar", "app.jar"]
