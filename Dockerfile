# Stage 1 — Build
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Copy build files first for layer caching of dependency downloads
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon --quiet

# Copy source and build fat JAR
COPY src ./src
RUN ./gradlew bootJar -x test --no-daemon --quiet

# Stage 2 — Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S swiftlink && adduser -S swiftlink -G swiftlink

COPY --from=builder /app/build/libs/*.jar app.jar
COPY docker-entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

USER swiftlink
EXPOSE 8080

ENTRYPOINT ["/entrypoint.sh"]
