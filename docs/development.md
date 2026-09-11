# Development

This document keeps the practical local development notes that do not need to stay on the front page.

## Compose Files

There are two main Compose entrypoints:

* [docker-compose.dev.yml](../docker-compose.dev.yml) for source-mounted development
* [docker-compose.yml](../docker-compose.yml) for built-container startup

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
| RustFS S3 API | `http://localhost:9000` |
| RustFS Console | `http://localhost:9001` |
| Technitium DNS UI | `http://localhost:5380` |

## Startup Order

Current startup sequence:

1. PostgreSQL becomes healthy
2. OpenBao starts
3. OpenBao bootstrap seeds required secrets
4. `controlplane` starts and runs Flyway migrations
5. RustFS starts for S3-compatible artifact storage
6. `rustfs-init` creates the local artifact bucket and seeds checked-in demo artifacts
7. `runtime` starts and connects to RustFS through generic S3 settings
8. `gateway` starts after `controlplane` is healthy

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

Re-seed checked-in demo Node artifacts into RustFS:

```bash
docker compose -f docker-compose.dev.yml run --rm rustfs-init
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

## Artifact Storage Notes

Runtime uses the `ArtifactStore` contract, so execution is storage-agnostic after an artifact is resolved.

Development mode uses RustFS as the local S3-compatible backend:

```text
ARTIFACT_STORE_TYPE=s3
S3_ARTIFACT_ENDPOINT=http://rustfs:9000
S3_ARTIFACT_BUCKET=funchole-artifacts
S3_ARTIFACT_ACCESS_KEY=funchole
S3_ARTIFACT_SECRET_KEY=funchole-secret
S3_ARTIFACT_REGION=us-east-1
S3_ARTIFACT_PATH_STYLE_ACCESS=true
```

RustFS uses one persisted Docker volume in local development. Production storage topology is future design work and should not copy this local-only setup blindly.

The `rustfs-init` service is development-only. It packages every checked-in demo artifact directory from `runtime/artifacts/dev/<componentVersionId>` and uploads it to RustFS so the seeded `/orders` flow can run without manual S3 setup. This is not the production artifact upload/build pipeline.

Artifact object keys are immutable and based only on the pinned component version:

```text
artifacts/<componentVersionId>/artifact.tar.gz
```

Cache behavior:

* cache hit resolves directly to the local `index.mjs`
* cache miss downloads the exact S3 object key
* the downloaded `artifact.tar.gz` is extracted into `ARTIFACT_CACHE_DIR`
* nested artifact directories are preserved
* `NodeExecutor` receives only the local artifact path

## OpenBao Notes

OpenBao now runs with persistent local storage instead of in-memory dev mode.

That means:

* secret values survive container restart
* certificate bundles remain available after restart
* `controlplane` and `gateway` can read their bootstrap secrets from the persisted OpenBao state

If the local secret state becomes confusing during development, reset the stack volumes and start fresh.
