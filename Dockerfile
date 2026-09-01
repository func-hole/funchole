FROM eclipse-temurin:25-jdk AS build-base
WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle build.gradle gradle.properties /workspace/
COPY gradle /workspace/gradle
COPY core/build.gradle /workspace/core/build.gradle
COPY controlplane/build.gradle /workspace/controlplane/build.gradle
COPY gateway/build.gradle /workspace/gateway/build.gradle
COPY invocation/build.gradle /workspace/invocation/build.gradle
COPY runtime/build.gradle /workspace/runtime/build.gradle
RUN chmod +x gradlew
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :controlplane:dependencies :gateway:dependencies --no-daemon >/dev/null 2>&1 || true

COPY core/src /workspace/core/src
COPY controlplane/src /workspace/controlplane/src
COPY gateway/src /workspace/gateway/src
COPY invocation/src /workspace/invocation/src
COPY runtime/src /workspace/runtime/src
COPY docker /workspace/docker

FROM build-base AS build-controlplane
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :controlplane:bootJar --no-daemon

FROM build-base AS build-gateway
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :gateway:fatJar --no-daemon

FROM eclipse-temurin:25-jre AS runtime-base
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl jq \
    && rm -rf /var/lib/apt/lists/*
COPY docker/openbao-common.sh /opt/funchole/openbao-common.sh
RUN chmod +x /opt/funchole/openbao-common.sh

FROM runtime-base AS controlplane
COPY --from=build-controlplane /workspace/controlplane/build/libs/funchole-controlplane.jar app.jar
COPY docker/controlplane-entrypoint.sh /opt/funchole/entrypoint.sh
RUN chmod +x /opt/funchole/entrypoint.sh
EXPOSE 7080
ENTRYPOINT ["/opt/funchole/entrypoint.sh"]

FROM runtime-base AS gateway
COPY --from=build-gateway /workspace/gateway/build/libs/funchole-gateway.jar app.jar
COPY docker/gateway-entrypoint.sh /opt/funchole/entrypoint.sh
RUN chmod +x /opt/funchole/entrypoint.sh
EXPOSE 7081
ENTRYPOINT ["/opt/funchole/entrypoint.sh"]
