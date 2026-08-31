# FuncHole Backend

- `core`: shared base classes, response wrappers, and exception handling
- `api`: runnable Spring Boot application with PostgreSQL, Flyway, Actuator, Docker, and Testcontainers

## Stack

- Java 25
- Spring Boot 3.5.16
- Gradle
- PostgreSQL
- Docker Compose
- Flyway
- Spring Boot Actuator
- Testcontainers

## Modules

- `core`
- `api`

## Quick Start

1. Start PostgreSQL:

   ```bash
   docker compose up -d db
   ```

2. Run the application:

   ```bash
   ./gradlew :api:bootRun
   ```

   Or run the database and application together:

   ```bash
   docker compose up -d --build
   ```

3. Smoke-test the API:

   ```bash
   curl http://localhost:8080/api/v1/system/ping
   curl http://localhost:8080/actuator/health
   ```

## Notes

- The project targets Java 25 through Gradle toolchains.
- Flyway migrations live in `api/src/main/resources/db/migration`.
- Integration tests use PostgreSQL via Testcontainers.
