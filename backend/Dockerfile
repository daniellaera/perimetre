# Périmètre backend. Built by CI (.gitlab-ci.yml) and pushed to
# registry.gitlab.com/daniellaera1/perimetre/backend:<short-sha> and :latest.
#
# Tag for people, digest for the build: a rebuild of the same commit is the
# same image. 21.0.12.1_1 is what 21-jre-jammy / 21-jdk-jammy pointed to when
# this was pinned (2026-10-26). Bump all three together.

# The jar is platform-independent, so the build stage runs on the builder's own
# platform (no emulation for Gradle when building amd64 on an arm64 Mac).
FROM --platform=$BUILDPLATFORM eclipse-temurin:21.0.12.1_1-jdk-jammy@sha256:742daacb9316be4b6fc60258f6e4ac2a11882f6c597373c92290a57bfad0d2e0 AS build
WORKDIR /src
# Wrapper and build files first: the dependency layer stays cached until they change.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon -q bootJar \
	&& find build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} /app.jar \;

# Temurin, not Oracle: its truststore has the WISeKey root CA vector.sitg.ge.ch
# chains to (see CLAUDE.md, "Backend conventions").
FROM eclipse-temurin:21.0.12.1_1-jre-jammy@sha256:affcab5356e1270ac35d2361ee83135af2f03e8d580eca78a6d5d6fdbf048db0

# Uid 1000, not root: it is the deploy user (ubuntu) on the server, which owns
# the Firebase key (600) mounted read-only under /secrets. Another uid could not
# read it, and the backend would fall back to LogPushSender.
RUN groupadd -g 1000 perimetre && useradd -u 1000 -g 1000 -M -d /app -s /usr/sbin/nologin perimetre

WORKDIR /app
# Owned by root, read-only to the app.
COPY --from=build /app.jar app.jar

# The heap follows the container's mem_limit (50 %), not the host. Exit on
# OutOfMemoryError so restart: always brings back a clean JVM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError"

USER 1000:1000
EXPOSE 8090

# Same probe as compose's; here for a plain `docker run`. curl -f exits
# non-zero on the 503 a DOWN health answers with.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
	CMD curl -fsS -o /dev/null http://localhost:8090/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
