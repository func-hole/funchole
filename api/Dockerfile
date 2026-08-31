FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

COPY . /workspace
RUN chmod +x gradlew && ./gradlew :api:bootJar --no-daemon

FROM eclipse-temurin:25-jre
WORKDIR /app

COPY --from=build /workspace/api/build/libs/funchole-api.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
