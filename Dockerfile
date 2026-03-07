# ─── Stage 1: Build ───────────────────────────────────────────────────────────
FROM gradle:8-jdk21 AS builder

WORKDIR /app

# Cache dependencies first
COPY build.gradle.kts settings.gradle.kts ./
RUN gradle dependencies --no-daemon --quiet 2>/dev/null || true

# Copy source and build fat jar
COPY . .
RUN gradle shadowJar --no-daemon --no-build-cache -x test

# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: run as non-root
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=builder /app/build/libs/claims-api.jar app.jar

# Ensure non-root ownership
RUN chown appuser:appgroup app.jar

USER appuser

EXPOSE 8080

# JVM tuning for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseG1GC \
  -XX:+ExitOnOutOfMemoryError \
  -Djava.security.egd=file:/dev/./urandom \
  -Dvertx.disableFileCaching=false"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
