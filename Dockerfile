FROM eclipse-temurin:25-jdk AS build-base
WORKDIR /workspace

COPY . /workspace
RUN chmod +x gradlew

FROM build-base AS build-controlplane
RUN ./gradlew :controlplane:bootJar --no-daemon

FROM build-base AS build-gateway
RUN ./gradlew :gateway:fatJar --no-daemon

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
