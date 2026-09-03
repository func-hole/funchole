# FuncHole

> **An open-source, self-hosted platform for running, routing, and composing portable functions.**

FuncHole is an experimental function execution platform built around a simple idea:

> **Functions should be portable, addressable, composable, and runnable without being tightly coupled to a specific cloud provider.**

FuncHole separates management, ingress, invocation, and execution into independent architectural components. The platform is designed to support lightweight function deployment, language-neutral function-to-function communication, self-hosted infrastructure, and consistent execution across local, staging, and production environments.

> [!WARNING]
> FuncHole is currently in early development. APIs, schemas, module boundaries, and runtime behavior may change significantly before the first stable release.

---

## Why FuncHole?

Serverless platforms make deploying functions easy, but execution, networking, runtime management, deployment, and operational tooling are often tightly coupled to a specific cloud provider.

FuncHole explores a different model.

```text
Function
   ↓
Portable Artifact
   ↓
FuncHole
   ↓
Any Supported Environment
```

The goal is to provide a self-hosted function platform where:

* functions have stable identities;
* runtimes are managed by the platform;
* public traffic is handled by a dedicated Gateway;
* functions can invoke other functions through a common contract;
* deployment revisions are immutable;
* infrastructure components can evolve independently;
* local and production environments follow the same execution model.

---

## Local Development Stack

The development environment in `[docker-compose.dev.yml](/Users/rafsan/Workspare/FuncHole/backend/docker-compose.dev.yml)` includes:

* `controlplane` on `http://localhost:7080`
* `gateway` on `http://localhost:7081`
* PostgreSQL on `localhost:5432`
* OpenBao on `http://localhost:8200`
* Technitium DNS on `localhost:53` with the admin UI on `http://localhost:5380`

Technitium is there for local DNS experimentation during development. Its state is persisted in the Docker volume `technitium-dev-data`.

The current dev defaults are:

* `DNS_SERVER_DOMAIN=dns.funchole.local`
* `DNS_SERVER_ADMIN_PASSWORD=admin`

Change the admin password if this stack is used anywhere beyond a local-only machine.

---

## Core Philosophy

FuncHole is being designed around a few fundamental principles.

### Portable

Function code should not depend on the physical location where it executes.

### Composable

Functions should be able to invoke other functions through FuncHole instead of manually discovering and calling infrastructure endpoints.

### Runtime Independent

The invocation contract should remain independent of Node.js, Python, or any future runtime.

### Self-Hosted

The complete platform should be deployable on infrastructure controlled by the user.

### Modular

Control Plane, Gateway, Invocation Engine, Runtime Engine, certificate management, and storage should have explicit boundaries.

### Immutable

Function artifacts and deployment revisions should be reproducible and promoted without silently changing their contents.

---

# Architecture

At a high level, FuncHole separates the platform into three major planes.

```text
                    ┌─────────────────────┐
                    │    Control Plane    │
                    │                     │
                    │ Configuration       │
                    │ Deployment          │
                    │ Domain Management   │
                    │ Gateway Management  │
                    │ Function Management │
                    └──────────┬──────────┘
                               │
                         configuration
                               │
                               ▼
Internet ─────────► ┌─────────────────────┐
                    │       Gateway       │
                    │                     │
                    │ HTTP / HTTPS        │
                    │ TLS                 │
                    │ Host Resolution     │
                    │ Routing             │
                    └──────────┬──────────┘
                               │
                            invoke
                               │
                               ▼
                    ┌─────────────────────┐
                    │  Invocation Engine  │
                    │                     │
                    │ invoke()            │
                    │ dispatch()          │
                    │ Permissions         │
                    │ Tracing             │
                    └──────────┬──────────┘
                               │
                            execute
                               │
                               ▼
                    ┌─────────────────────┐
                    │   Runtime Engine    │
                    │                     │
                    │ Runtime Resolution  │
                    │ Isolation           │
                    │ Execution           │
                    └──────────┬──────────┘
                               │
                               ▼
                           Function
```

Conceptually:

```text
Control Plane  → What should exist?
Gateway        → Where should this request go?
Invocation     → How should functions communicate?
Runtime        → How should this function execute?
```

---

# Control Plane

The Control Plane is the management application of FuncHole.

It is implemented using **Spring Boot** and is responsible for administrative and configuration concerns rather than serving function traffic.

Current and planned responsibilities include:

* authentication and authorization;
* domain management;
* gateway management;
* function management;
* deployment management;
* environment configuration;
* certificate provisioning;
* configuration distribution;
* deployment history;
* platform APIs.

The Control Plane owns persistent management state through PostgreSQL.

```text
Admin / CLI / API
        │
        ▼
┌─────────────────┐
│  Control Plane  │
│   Spring Boot   │
└────────┬────────┘
         │
         ▼
    PostgreSQL
```

The Control Plane is independently deployable from the Gateway.

Because the Control Plane now runs on **Spring Boot 4.1.1**, Flyway auto-migration support is enabled through `spring-boot-starter-flyway`. Using only `flyway-core` is not enough in Boot 4.x if you expect migrations to run automatically at startup.

---

# Gateway

The FuncHole Gateway is a standalone Java application built directly on **Netty**.

It acts as a **function-aware web server**.

Traditional reverse proxies typically work like this:

```text
Request
   ↓
Nginx / Caddy
   ↓
Upstream Server
```

FuncHole instead aims for:

```text
Request
   ↓
FuncHole Gateway
   ↓
Function Resolution
   ↓
Invocation Engine
   ↓
Function
```

The Gateway is responsible for concerns such as:

* HTTP/HTTPS listeners;
* TLS termination;
* SNI and certificate selection;
* host parsing;
* gateway resolution;
* function resolution;
* request normalization;
* gateway rule matching;
* request and trace identifiers;
* ingress policies;
* HTTP response mapping;
* access logging;
* graceful connection handling.

The Gateway **does not execute functions**.

It delegates execution through the Invocation Engine.

---

## Gateway Request Flow

A future public request may look like:

```text
https://f12344.g1.example.com/orders/10
```

The Gateway can resolve the hostname as:

```text
function_key = f12344
gateway_key  = g1
domain       = example.com
```

Then:

```text
TCP Connection
      ↓
TLS Handshake
      ↓
HTTP Decode
      ↓
Host Resolution
      ↓
Gateway Resolution
      ↓
Function Resolution
      ↓
Gateway Rule Matching
      ↓
Invocation Engine
      ↓
Runtime Engine
      ↓
Function
```

The request-serving hot path is intended to use in-memory routing and TLS configuration wherever possible.

PostgreSQL or secret storage should not need to be queried for every incoming request.

---

# Domain and Gateway Model

FuncHole separates domain ownership from Gateway identity.

```text
Domain
   ↓
Gateway
   ↓
Gateway Rule
   ↓
Function
```

Suppose a verified domain is:

```text
example.com
```

and FuncHole creates a Gateway with:

```text
gateway_key = g1
```

The Gateway receives the namespace:

```text
g1.example.com
```

and its functions live underneath:

```text
*.g1.example.com
```

For example:

```text
f12344.g1.example.com
f99210.g1.example.com
f81abc.g1.example.com
```

A wildcard DNS record can direct the complete namespace toward one or more Gateway instances:

```text
*.g1.example.com
        ↓
   Load Balancer
        ↓
┌───────┼───────┐
▼       ▼       ▼
GW-1    GW-2    GW-3
```

This avoids creating a DNS record for every individual function.

---

# Function Identity

Function display names and network identities are intentionally separate concepts.

A function may have:

```text
name         = create-order
function_key = f12344
```

Its hostname becomes:

```text
f12344.g1.example.com
```

Renaming:

```text
create-order
```

to:

```text
place-order
```

does not need to change its stable network identity.

The hostname can be derived from:

```text
{function_key}.{gateway_key}.{domain}
```

and therefore does not need to be duplicated as persistent state.

---

# Function Types

FuncHole is intended to support at least two function exposure models.

## HTTP Functions

HTTP functions are reachable through the Gateway.

```text
Internet
   ↓
Gateway
   ↓
HTTP Function
```

## Internal Functions

Internal functions have no public HTTP identity.

They can only be invoked through FuncHole.

```text
Function A
   ↓
Invocation Engine
   ↓
Function B
```

This allows internal implementation details to remain inaccessible from the public internet.

---

# Function Invocation

A core goal of FuncHole is language-neutral function communication.

Conceptually:

```text
fh.invoke("inventory.reserve", payload)
```

for synchronous execution and:

```text
fh.dispatch("email.send", payload)
```

for asynchronous execution.

This enables compositions such as:

```text
create-order
     ↓
reserve-inventory
     ↓
capture-payment
     ↓
send-confirmation
```

without requiring each function to know the physical endpoint or implementation language of another function.

The Invocation Engine is expected to eventually manage:

* synchronous invocation;
* asynchronous dispatch;
* function permissions;
* trace propagation;
* invocation graphs;
* execution routing;
* retry policies;
* execution metadata.

---

# Runtime Engine

The Runtime Engine is responsible for actually executing functions.

The intended flow is:

```text
Invocation
    ↓
Runtime Resolution
    ↓
Artifact Preparation
    ↓
Isolation Backend
    ↓
Environment Injection
    ↓
Handler Execution
    ↓
Result
```

Expected responsibilities include:

* selecting the correct runtime and version;
* preparing dependencies;
* preparing function artifacts;
* injecting environment configuration;
* enforcing execution timeouts;
* applying resource limits;
* collecting stdout and stderr;
* executing handlers;
* handling runtime failures;
* cleaning up execution resources.

---

## Runtime Providers

Runtime support is designed to be pluggable.

Conceptually:

```text
RuntimeProvider
├── NodeRuntimeProvider
├── PythonRuntimeProvider
└── ...
```

Node.js is the initial runtime target.

Additional runtimes can be introduced without changing the Gateway or invocation contract.

---

# Runtime Isolation

The initial execution model may use operating-system processes for trusted self-hosted workloads.

Isolation is intended to remain replaceable:

```text
IsolationBackend
├── ProcessBackend
├── LinuxSandboxBackend
└── MicroVMBackend
```

Language-level sandboxing mechanisms should not be considered a sufficient security boundary for arbitrary malicious code.

Strong hostile multi-tenancy will require operating-system or virtual-machine-level isolation.

---

# Certificate Management

TLS certificates belong to **Gateway namespaces**, not individual functions.

For:

```text
gateway = g1
domain  = example.com
```

the Gateway certificate can cover:

```text
g1.example.com
*.g1.example.com
```

Therefore a function created later:

```text
f12344.g1.example.com
```

is already covered by the Gateway certificate.

There is no need to issue a new certificate for every function.

---

## Certificate Architecture

Certificate management is planned as a shared, framework-independent Java module.

```text
certificate
├── generator
├── loader
└── renewer
```

The intended relationship is:

```text
Control Plane
     ↓
Certificate Generator
     ↓
Certificate Store
```

while:

```text
Gateway
   ↓
Certificate Loader
   ↓
Certificate Store
   ↓
Netty SslContext
```

The Gateway does not generate certificates.

The Control Plane initiates certificate provisioning.

The Gateway only loads active TLS material required to serve traffic.

---

## Certificate Generator

The generator will support different certificate providers.

```text
CertificateGenerator
├── SelfSignedCertificateGenerator
└── LetsEncryptCertificateGenerator
```

Local development can use self-signed certificates.

Production deployments can use Let's Encrypt through ACME.

For wildcard certificates such as:

```text
*.g1.example.com
```

production certificate issuance will require an appropriate DNS-based ACME validation flow.

---

## Certificate Loader

Certificates should not be loaded from persistent storage for every HTTP request.

Instead:

```text
Gateway Startup
      ↓
Certificate Loader
      ↓
OpenBao
      ↓
Certificate Bundle
      ↓
Netty SslContext
      ↓
In-Memory TLS Registry
```

Incoming TLS connections can then select the appropriate `SslContext` from memory.

```text
f12344.g1.example.com
        ↓
       SNI
        ↓
*.g1.example.com
        ↓
In-Memory SslContext
```

This keeps secret-storage access outside the request hot path.

---

## Certificate Renewer

The renewal component is responsible for detecting certificates approaching expiration and replacing them.

Conceptually:

```text
Scheduler
   ↓
Certificate Renewer
   ↓
Certificate Provider
   ↓
New Certificate
   ↓
Certificate Store
   ↓
Gateway Configuration Update
   ↓
New SslContext
```

Existing connections may continue using their current TLS context while new connections receive the renewed certificate.

---

# OpenBao

FuncHole uses OpenBao for local secret-management development.

Sensitive material such as TLS private keys should not be stored as plaintext application configuration.

The intended separation is:

```text
PostgreSQL
-------------------------
Certificate metadata
Gateway relationship
Provider
Status
Expiration
Secret reference


OpenBao
-------------------------
Certificate PEM
Private key
Certificate chain
```

The Gateway accesses certificate material through a storage abstraction rather than directly depending on the physical OpenBao representation.

Conceptually:

```java
public interface CertificateStore {

    CertificateBundle load(CertificateReference reference);

}
```

This makes alternative secret stores possible in the future.

---

# Framework Boundaries

A major architectural rule in FuncHole is:

> **Frameworks belong at application boundaries. Shared modules should remain framework-independent whenever practical.**

The Control Plane uses Spring Boot:

```text
Spring Boot
    ↓
Control Plane
    ↓
Shared Java Modules
```

The Gateway uses Netty:

```text
Netty
  ↓
Gateway
  ↓
Shared Java Modules
```

For example, the certificate module should return something similar to:

```java
public record CertificateBundle(
    byte[] certificateChain,
    byte[] privateKey
) {}
```

It should **not** return:

```java
SslContext
```

because `SslContext` is a Netty concern.

The Gateway converts the framework-independent certificate representation into a Netty TLS context.

Similarly, the certificate module should not require Spring annotations such as:

```text
@Component
@Service
@Autowired
@Scheduled
```

Spring-specific dependency wiring and scheduling remain inside the Control Plane application.

---

# Repository Structure

FuncHole is organized as a Gradle multi-module project.

```text
funchole/
├── controlplane/
├── core/
├── docker/
├── gateway/
├── invocation/
├── runtime/
├── build.gradle
├── settings.gradle
├── Dockerfile
└── docker-compose.yml
```

Current modules:

| Module         | Responsibility                                              |
| -------------- | ----------------------------------------------------------- |
| `core`         | Shared base classes, response wrappers, and common concerns |
| `controlplane` | Spring Boot management application                          |
| `gateway`      | Standalone raw Netty ingress service                        |
| `invocation`   | Function invocation and orchestration layer                 |
| `runtime`      | Function execution and runtime abstraction                  |

A dedicated framework-independent `certificate` module is part of the planned architecture.

---

# Current Technology Stack

| Area                | Technology              |
| ------------------- | ----------------------- |
| Language            | Java 25                 |
| Build               | Gradle                  |
| Control Plane       | Spring Boot 4.1.1       |
| Gateway             | Raw Netty               |
| Database            | PostgreSQL              |
| Database Migration  | Flyway                  |
| Connection Pool     | HikariCP                |
| Secret Management   | OpenBao                 |
| Containers          | Docker / Docker Compose |
| Integration Testing | Testcontainers          |
| Authentication      | Spring Security + JWT   |
| API Documentation   | Springdoc OpenAPI       |
| Mapping             | MapStruct               |
| Logging             | Log4j2                  |

---

# Local Development

The current `docker-compose.yml` is intentionally a development environment.

It is optimized for:

* local PostgreSQL;
* local OpenBao secret bootstrap;
* local application containers for end-to-end verification.

It is not yet intended to be the final production deployment definition.

For the Docker-only development workflow, use the separate development compose file:

```bash
docker compose -f docker-compose.dev.yml up -d --build
```

## Requirements

You should have:

* Java 25
* Docker
* Docker Compose

available locally.

---

## Start PostgreSQL

```bash
docker compose up -d db
```

For development-oriented Docker usage, prefer:

```bash
docker compose -f docker-compose.dev.yml up -d db
```

---

## Start PostgreSQL and OpenBao

```bash
docker compose up -d db openbao
```

If you want the full local secret bootstrap flow:

```bash
docker compose up -d db openbao openbao-init
```

Development in Docker should normally use:

```bash
docker compose -f docker-compose.dev.yml up -d --build
```

If you change files under `docker/openbao-init/secrets`, rerun the seeding step:

```bash
docker compose -f docker-compose.dev.yml up openbao-init
```

---

## Start the Full Local App Stack in Docker

```bash
docker compose up -d --build
```

This Compose file runs the built application containers.

It is useful for:

* end-to-end local verification;
* Flyway migration checks;
* OpenBao secret bootstrap checks;
* verifying the real container startup path.

It is not a hot-reload workflow.

The local Compose environment seeds OpenBao before the Control Plane and Gateway start.

---

## Run the Control Plane

Docker-only development loop:

```bash
docker compose -f docker-compose.dev.yml up -d --build
```

If you prefer to run the application outside Docker, `./gradlew :controlplane:bootRun` still works, but it is optional.

The development container runs from mounted source and polls for file changes under `core/src` and `controlplane/src`. When a change is detected, it restarts the Control Plane process automatically inside the container.

The dev container uses an internal Gradle project cache directory instead of the bind-mounted workspace cache to avoid `.gradle` lock conflicts between services.

If the application fails to compile or start, the watcher now stays idle and waits for the next file change instead of restarting in a loop.

The current Control Plane runs on:

```text
http://localhost:7080
```

Smoke test:

```bash
curl http://localhost:7080/api/v1/system/ping
```

Health check:

```bash
curl http://localhost:7080/actuator/health
```

API documentation:

```text
http://localhost:7080/swagger-ui.html
```

---

## Run the Gateway

Docker-only development loop:

```bash
docker compose -f docker-compose.dev.yml up -d --build
```

If you prefer to run the application outside Docker, `./gradlew :gateway:run` still works, but it is optional.

The development container runs from mounted source and polls for file changes under `core/src` and `gateway/src`. When a change is detected, it restarts the Gateway process automatically inside the container.

The dev container uses an internal Gradle project cache directory instead of the bind-mounted workspace cache to avoid `.gradle` lock conflicts between services.

If the application fails to compile or start, the watcher now stays idle and waits for the next file change instead of restarting in a loop.

The Gateway currently runs on:

```text
http://localhost:7081
```

Health check:

```bash
curl http://localhost:7081/health
```

The Gateway is intentionally **not a Spring Boot application**.

It is a standalone Java application using Netty.

---

# Docker Architecture

The repository currently uses a shared root Dockerfile with separate build targets.

For the current development stage:

* `docker compose up` starts the full local development stack by default;
* `docker compose up db openbao openbao-init` is the lighter infra-only workflow;
* `docker compose -f docker-compose.dev.yml up -d --build` is the recommended Docker-only development workflow;
* local Gradle execution is optional, not required;
* dev containers poll mounted source files and restart the app process automatically when code changes;
* container rebuilds are mainly needed when Docker-related files or dependencies change.

```text
Dockerfile
   │
   ├── controlplane
   │      ↓
   │   Spring Boot bootJar
   │
   └── gateway
          ↓
      standalone fatJar
```

This allows both applications to share common JDK/JRE build definitions while remaining independently deployable.

To reduce rebuild time, the Dockerfile now copies Gradle metadata before service source code and uses a Gradle cache mount during build steps. On September 2, 2026, this is the main optimization in place for local Docker rebuild speed.

Conceptually:

```text
┌─────────────────────────┐
│ Control Plane Container │
│      Spring Boot        │
└─────────────────────────┘

┌─────────────────────────┐
│   Gateway Container     │
│        Netty            │
└─────────────────────────┘
```

The applications can therefore be scaled independently.

---

# Scaling Model

Control Plane and Gateway workloads have very different traffic characteristics.

A deployment might initially contain:

```text
Control Plane × 1
Gateway       × 1
```

while a larger deployment could become:

```text
Control Plane × 1

       Load Balancer
             │
    ┌────────┼────────┐
    ▼        ▼        ▼
Gateway-1 Gateway-2 Gateway-3
```

The Gateway should not require direct database access in the final request-routing architecture.

Instead, the Control Plane can distribute configuration snapshots that Gateway instances maintain in memory.

```text
Control Plane
      ↓
Configuration Snapshot
      ↓
Gateway Registry
      ↓
Atomic Configuration Swap
```

---

# Function Artifacts

The intended function artifact model is lightweight and immutable.

A function artifact may eventually contain:

```text
Function Artifact
├── Runtime Reference
├── Dependency Layer
├── Code Layer
├── Manifest
└── Configuration Metadata
```

Content-addressed layers can allow dependencies and runtime assets to be shared rather than duplicated for every function.

---

# Deployment Revisions

FuncHole aims to use immutable deployment revisions.

Every deployment creates a new revision.

```text
Revision 1
    ↓
Revision 2
    ↓
Revision 3
```

Rollback should not mutate an old revision.

Instead:

```text
Revision 1
Revision 2
Revision 3
    ↓ rollback to state of Revision 1
Revision 4
```

where Revision 4 contains the desired state derived from Revision 1.

This preserves complete deployment history.

The model enables:

* deployment history;
* revision inspection;
* configuration diffing;
* rollback;
* environment promotion;
* provenance tracking;
* reproducible deployments.

---

# Environment Management

Environment configuration is intended to be a first-class FuncHole capability.

Future environment management may include:

* environment scopes;
* variable schemas;
* secret references;
* validation;
* environment-specific configuration;
* deployment-time checks.

Secrets should remain references to secure storage rather than being embedded directly into immutable function artifacts.

---

# Security Model

FuncHole is currently designed primarily for trusted self-hosted workloads.

The project does **not** currently claim secure arbitrary multi-tenant execution.

Important security boundaries include:

```text
Internet
   ↓
Gateway
   ↓
Invocation Permissions
   ↓
Runtime Isolation
   ↓
Function
```

Future hostile multi-tenancy requires stronger isolation mechanisms than language-level sandboxing.

TLS private keys and other sensitive infrastructure credentials should be managed through dedicated secret-management infrastructure.

---

# Current Status

FuncHole is under active early development.

### Available foundations

* Gradle multi-module structure
* Java 25 toolchain
* Spring Boot Control Plane
* standalone Netty Gateway
* PostgreSQL
* Flyway migrations
* Docker Compose environment
* shared multi-target Dockerfile
* OpenBao local integration
* JWT-based Control Plane authentication
* Testcontainers integration
* Invocation module foundation
* Runtime module foundation

### Planned architecture

Major areas still under development include:

* Gateway domain routing;
* wildcard TLS;
* certificate lifecycle management;
* function registration;
* stable function keys;
* Invocation Engine;
* `invoke()` and `dispatch()`;
* Node.js Runtime Provider;
* function artifact management;
* deployment revisions;
* environment management;
* configuration distribution;
* in-memory Gateway registry;
* tracing and invocation graphs;
* runtime isolation.

---

# Design Principles

When contributing to FuncHole, prefer designs that follow these principles:

1. **Keep management and request-serving responsibilities separate.**
2. **Keep the Gateway independent from individual runtimes.**
3. **Keep shared modules independent from Spring Boot and Netty where practical.**
4. **Avoid database and secret-store access in the Gateway request hot path.**
5. **Use stable generated network identities rather than mutable display names.**
6. **Treat deployment revisions and function artifacts as immutable.**
7. **Do not duplicate state that can be deterministically derived.**
8. **Do not introduce abstractions or database fields without a concrete requirement.**
9. **Keep infrastructure implementations replaceable behind focused contracts.**
10. **Do not claim strong multi-tenant security until the execution boundary actually provides it.**

---

# Vision

The long-term goal of FuncHole is not simply to become another HTTP-to-code server.

The goal is to create a portable function platform where:

```text
Write Function
      ↓
Build Once
      ↓
Deploy
      ↓
Expose or Keep Internal
      ↓
Compose with Other Functions
      ↓
Run Across Environments
```

while the underlying infrastructure remains observable, replaceable, and self-hostable.

```text
                    FuncHole

        ┌──────── Control Plane ────────┐
        │                               │
        │ Domains                      │
        │ Gateways                     │
        │ Functions                    │
        │ Deployments                  │
        │ Environments                 │
        │ Certificates                 │
        │                               │
        └──────────────┬────────────────┘
                       │
                       ▼
                ┌─────────────┐
Internet ──────►│   Gateway   │
                │    Netty    │
                └──────┬──────┘
                       │
                       ▼
                ┌─────────────┐
                │ Invocation  │
                │   Engine    │
                └──────┬──────┘
                       │
                       ▼
                ┌─────────────┐
                │   Runtime   │
                │   Engine    │
                └──────┬──────┘
                       │
                       ▼
                   Functions
```

---

# Contributing

FuncHole is still at an early stage, which makes this a good time to discuss architecture and influence the direction of the project.

Contributions are welcome in areas such as:

* architecture discussions;
* Gateway and networking;
* function runtimes;
* invocation protocols;
* certificate management;
* runtime isolation;
* deployment infrastructure;
* testing;
* documentation.

For substantial architectural changes, consider opening an issue before implementation so the design can be discussed first.

---

# License

License information will be added as the project matures.

---

**FuncHole — portable functions, self-hosted infrastructure, composable execution.**
