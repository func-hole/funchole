# FuncHole Backend

- `core`: shared base classes, response wrappers, and exception handling
- `api`: runnable Spring Boot application with PostgreSQL, Flyway, Actuator, Docker, and Testcontainers

## Stack

- Java 25
- Spring Boot 4.1.1
- Gradle
- PostgreSQL
- Docker Compose
- Flyway
- Spring Boot Actuator
- Testcontainers
- Spring Security + JWT
- Springdoc OpenAPI
- MapStruct
- Log4j2
- Testcontainers 2.0.5

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
   curl http://localhost:7080/api/v1/system/ping
   curl http://localhost:7080/actuator/health
   ```

4. Request a bootstrap JWT token:

   ```bash
   curl -X POST http://localhost:7080/api/v1/auth/token \
     -H 'Content-Type: application/json' \
     -d '{"username":"admin","password":"admin12345"}'
   ```

5. Open API docs:

   ```bash
   open http://localhost:7080/swagger-ui.html
   ```

## Docker Notes

- Compose uses an isolated `funchole-network` bridge network for the app and database.
- Fixed `container_name` values are intentionally not used, so Compose can manage service instances without name collisions.
- After changing the Dockerfile or switching the folder from an older project, use:

  ```bash
  docker compose down --remove-orphans
  docker compose up --build
  ```

- If Docker still starts an old cached application image, remove the old app image and rebuild:

  ```bash
  docker image prune -a
  docker compose up --build
  ```

## Notes

- The project targets Java 25 through Gradle toolchains.
- Flyway migrations live in `api/src/main/resources/db/migration`.
- Integration tests use PostgreSQL via Testcontainers.
- Change the default bootstrap credentials and JWT secret before using this outside local development.
