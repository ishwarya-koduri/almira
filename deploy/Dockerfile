# =============================================================================
# Almira — production image.
#
# Two stages: a JDK to build the jar, a JRE to run it. The runtime layer carries
# no Gradle, no sources and no compiler, which is both smaller and one less
# thing to keep patched.
#
#   docker build -f deploy/Dockerfile -t almira:latest .
#
# Build context is the REPOSITORY ROOT, not deploy/ — the migrations live at
# db/migrations and are copied into the jar by the build (see backend/build.gradle.kts).
# =============================================================================

# --- build -------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /src

# The wrapper and the build files first, so a change to application code does
# not re-download the dependency graph.
COPY backend/gradlew backend/gradlew
COPY backend/gradle backend/gradle
COPY backend/settings.gradle.kts backend/build.gradle.kts backend/
RUN cd backend && chmod +x gradlew && ./gradlew --no-daemon dependencies --quiet || true

COPY db db
COPY backend backend

# `-x test` on purpose: tests need PostgreSQL and Redis, and a build that
# silently skipped them would be worse than one that never claimed to run them.
# They run in CI against real services (.github/workflows) and locally through
# ./scripts/dev.sh test.
RUN cd backend && ./gradlew --no-daemon clean bootJar -x test \
 && cp build/libs/*.jar /src/almira.jar

# --- runtime -----------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

# curl for the healthcheck below; nothing else is added.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --home /app --shell /usr/sbin/nologin almira \
 && mkdir -p /app/var/documents \
 && chown -R almira:almira /app

WORKDIR /app
COPY --from=build --chown=almira:almira /src/almira.jar /app/almira.jar

USER almira
EXPOSE 8080

# Documents are encrypted before they are written, but they are still the
# household's papers: mount a real volume here, and back it up with the database.
VOLUME ["/app/var"]

ENV ALMIRA_ENV=production \
    ALMIRA_PORT=8080 \
    ALMIRA_STORAGE_ROOT=/app/var/documents \
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"

# /health reports the database role it is actually connected as. If that ever
# says `almira` (the schema owner) instead of `almira_app`, row-level security
# is being bypassed — which is why the check looks at more than the status code.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/health | grep -q '"rlsEnforced":true' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/almira.jar"]
