# FuncHole Backend

## Stack

- Java 25
- Gradle
- PostgreSQL
- Docker Compose
- Shared root Dockerfile with multi-target builds
- Spring Boot 4.1.1 for `controlplane`
- Raw Netty for `gateway`
- Flyway
- Spring Boot Actuator
- Testcontainers
- Spring Security + JWT
- Springdoc OpenAPI
- MapStruct
- Log4j2
- Testcontainers 2.0.5

## Modules

- `core`: shared base classes, response wrappers, and exception handling
- `controlplane`: Spring Boot service for management/admin concerns, auth, Flyway, and OpenAPI
- `gateway`: standalone raw Netty service with direct PostgreSQL access for entrypoint concerns
- `invocation`: placeholder module for orchestration concerns
- `runtime`: placeholder module for execution concerns

## Structure

```text
backend
├── core
├── controlplane
├── gateway
├── invocation
└── runtime
```

## Quick Start

1. Start PostgreSQL:

   ```bash
   docker compose up -d db
   ```

2. Run the controlplane application:

   ```bash
   ./gradlew :controlplane:bootRun
   ```

3. Run the raw Netty gateway:

   ```bash
   ./gradlew :gateway:run
   ```

   Or run the database and both services together:

   ```bash
   docker compose up -d --build
   ```

4. Smoke-test the controlplane API:

   ```bash
   curl http://localhost:7080/api/v1/system/ping
   curl http://localhost:7080/actuator/health
   ```

5. Smoke-test the gateway:

   ```bash
   curl http://localhost:7081/health
   curl http://localhost:7081/demo/bootstrap-metadata
   ```

6. Request a bootstrap JWT token:

   ```bash
   curl -X POST http://localhost:7080/api/v1/auth/token \
     -H 'Content-Type: application/json' \
     -d '{"username":"admin","password":"admin12345"}'
   ```

7. Open controlplane API docs:

   ```bash
   open http://localhost:7080/swagger-ui.html
   ```

## Docker Notes

- Compose uses an isolated `funchole-network` bridge network for the app and database.
- `controlplane` and `gateway` now share one root [Dockerfile](/Users/rafsan/Workspare/FuncHole/backend/Dockerfile) with separate build targets, so the JDK/JRE stage definitions are maintained in one place.
- Fixed `container_name` values are intentionally not used, so Compose can manage service instances without name collisions.
- The Docker build targets are:

  ```text
  controlplane -> Spring Boot bootJar
  gateway      -> standalone fatJar
  ```

- After changing the Dockerfile or switching the folder from an older project, use:

  ```bash
  docker compose down --remove-orphans
  docker compose up --build
  ```

- If Docker still starts an old cached image, remove old images and rebuild:

  ```bash
  docker image prune -a
  docker compose up --build
  ```

## Notes

- The project targets Java 25 through Gradle toolchains.
- Flyway migrations live in `controlplane/src/main/resources/db/migration`.
- Integration tests use PostgreSQL via Testcontainers.
- Change the default bootstrap credentials and JWT secret before using this outside local development.
- `gateway` is not a Spring Boot application. It is a plain Java service using Netty plus JDBC/HikariCP.
