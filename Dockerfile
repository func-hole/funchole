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

FROM runtime-base AS controlplane
COPY --from=build-controlplane /workspace/controlplane/build/libs/funchole-controlplane.jar app.jar
EXPOSE 7080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM runtime-base AS gateway
COPY --from=build-gateway /workspace/gateway/build/libs/funchole-gateway.jar app.jar
EXPOSE 7081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
