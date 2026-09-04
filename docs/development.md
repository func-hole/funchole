# Development

This document keeps the practical local development notes that do not need to stay on the front page.

## Compose Files

There are two main Compose entrypoints:

* [docker-compose.dev.yml](/Users/rafsan/Workspare/FuncHole/backend/docker-compose.dev.yml) for source-mounted development
* [docker-compose.yml](/Users/rafsan/Workspare/FuncHole/backend/docker-compose.yml) for built-container startup

Recommended command for day-to-day work:

```bash
docker compose -f docker-compose.dev.yml up --build
```

## Local Services

| Service | Address |
| --- | --- |
| Controlplane | `http://localhost:7080` |
| Gateway | `https://localhost` |
| PostgreSQL | `localhost:5432` |
| OpenBao | `http://localhost:8200` |
| Technitium DNS UI | `http://localhost:5380` |

## Startup Order

Current startup sequence:

1. PostgreSQL becomes healthy
2. OpenBao starts
3. OpenBao bootstrap seeds required secrets
4. `controlplane` starts and runs Flyway migrations
5. `gateway` starts after `controlplane` is healthy

That dependency exists because the database schema is managed through the `controlplane` startup path.

## Hot Reload Style

In development mode:

* `controlplane` and `gateway` run from mounted source
* a lightweight polling watcher restarts the app process when source changes
* developers do not need to run Gradle directly on the host for ordinary development

Rebuilds are still needed when:

* Docker-related files change
* base image assumptions change
* dependency graph changes require container rebuild

## Useful Commands

Start development stack:

```bash
docker compose -f docker-compose.dev.yml up --build
```

Stop development stack:

```bash
docker compose -f docker-compose.dev.yml down
```

Reset development volumes:

```bash
docker compose -f docker-compose.dev.yml down -v
```

Re-run OpenBao bootstrap:

```bash
docker compose -f docker-compose.dev.yml up openbao-init
```

Compile major modules:

```bash
./gradlew :certificate:compileJava :core:compileJava :controlplane:compileJava :gateway:compileJava
```

Run tests:

```bash
./gradlew test
```

## Local Workflow

Typical backend workflow:

1. Start the stack
2. Create or use an app user
3. Create a domain
4. Add the required TXT verification record
5. Verify the domain
6. Create a gateway under the verified domain
7. Let `controlplane` provision the certificate
8. Let `gateway` pick up the certificate through registry polling
9. Send HTTPS traffic to the gateway hostname

## DNS Notes

Technitium DNS is included for local DNS experimentation and domain verification.

Important limitation:

* Docker can run the DNS server
* but Docker cannot automatically make every developer machine use that DNS server for a custom domain

So for custom local domains like:

```text
https://gw1.funchole.test/
```

there are still two separate layers:

* project-side DNS inside Docker
* host-machine DNS resolution

This is why wildcard records inside Technitium alone are not enough if the host machine is still using another DNS server.

## HTTPS Notes

`gateway` serves HTTPS on port `443` in development.

Why:

* it keeps the local URL shape close to production
* it avoids exposing a developer-only port in the public hostname pattern

Current development certificates are self-signed, so browser trust warnings are expected unless the local trust chain is configured.

## Gateway Registry Polling

The `gateway` keeps an in-memory registry of active hosts and TLS contexts.

Current behavior:

* it loads an initial snapshot on startup
* it uses a fallback TLS context when there are no gateways yet
* it polls PostgreSQL and OpenBao on a short interval
* new active gateway certificates can be picked up without restarting the container

## OpenBao Notes

OpenBao now runs with persistent local storage instead of in-memory dev mode.

That means:

* secret values survive container restart
* certificate bundles remain available after restart
* `controlplane` and `gateway` can read their bootstrap secrets from the persisted OpenBao state

If the local secret state becomes confusing during development, reset the stack volumes and start fresh.
