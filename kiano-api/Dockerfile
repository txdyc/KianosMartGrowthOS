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
# ffprobe (from ffmpeg) for video QC; tesseract-ocr for template OCR prechecks.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg tesseract-ocr \
    && rm -rf /var/lib/apt/lists/*
# Playwright Chromium for template rendering (ZIP layout supports PropertiesLauncher).
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
RUN java -Dloader.main=com.microsoft.playwright.CLI -cp app.jar \
    org.springframework.boot.loader.launch.PropertiesLauncher install --with-deps chromium
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
