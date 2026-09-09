FROM node:22-alpine AS client
WORKDIR /build/client
COPY client/package*.json ./
RUN npm ci
COPY client/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS server
WORKDIR /build/server
COPY server/pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY server/src ./src
COPY --from=client /build/client/dist ./src/main/resources/static
RUN mvn -B -ntp package -DskipTests

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 weave && useradd --uid 10001 --gid weave --no-create-home weave
WORKDIR /app
COPY --from=server --chown=weave:weave /build/server/target/weave-server-0.2.0.jar app.jar
COPY --chown=weave:weave --chmod=755 infra/docker/entrypoint.sh /app/entrypoint.sh
USER weave
ENV WEAVE_BIND=0.0.0.0 PORT=8080 JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 CMD curl -fsS http://127.0.0.1:${PORT}/api/v1/health || exit 1
ENTRYPOINT ["/app/entrypoint.sh"]
