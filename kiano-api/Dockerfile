# Multi-stage build: Maven on the JDK, then run on the JRE with ffprobe.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
# Warm the dependency cache so source changes don't re-download everything.
RUN ./mvnw -q -DskipTests dependency:go-offline || true
COPY src src
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:21-jre
# ffprobe (from ffmpeg) is required for video QC.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
