# syntax=docker/dockerfile:1

# Étape de compilation : la distribution (scripts + jars) est indépendante de l'architecture.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
# Les fichiers Gradle d'abord, pour profiter du cache tant que les dépendances ne changent pas.
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew installDist -x test --no-daemon

# Étape d'exécution : JRE seul, utilisateur non root.
FROM eclipse-temurin:25-jre
# curl est installé explicitement pour le HEALTHCHECK (absent de l'image JRE).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 w2d \
    && useradd --uid 10001 --gid 10001 --create-home --home-dir /home/w2d --shell /usr/sbin/nologin w2d

WORKDIR /app
COPY --from=build /workspace/build/install/webhookToDiscord/ ./

USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl --fail --silent "http://localhost:${PORT:-8080}/health" || exit 1

# Les options JVM (polyglot, sun-misc-unsafe) viennent de applicationDefaultJvmArgs.
ENTRYPOINT ["/app/bin/webhookToDiscord"]
