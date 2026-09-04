# Contributing to FuncHole Backend

Thanks for contributing.

This backend is still in active foundation work, so the most helpful contributions are the ones that improve clarity, keep module boundaries clean, and avoid locking us into the wrong abstractions too early.

## Before You Start

Please read:

* [README.md](/Users/rafsan/Workspare/FuncHole/backend/README.md)
* [docs/architecture.md](/Users/rafsan/Workspare/FuncHole/backend/docs/architecture.md)
* [docs/development.md](/Users/rafsan/Workspare/FuncHole/backend/docs/development.md)
* the relevant module you want to change
* existing Docker and migration setup if your change affects local development or persistence

## Development Principles

Try to preserve these project rules:

* keep frameworks at the edges
* keep shared modules framework-independent where practical
* avoid mixing `controlplane` concerns into `gateway`
* treat `gateway` as a standalone raw Netty service, not as a Spring Boot app
* treat OpenBao as secret storage and PostgreSQL as metadata storage
* keep host identity derived from `gateway.unique_key + app_domain.domain_name`

## Local Setup

Recommended setup:

```bash
docker compose -f docker-compose.dev.yml up --build
```

Useful commands:

```bash
docker compose -f docker-compose.dev.yml down
./gradlew test
./gradlew :controlplane:compileJava :gateway:compileJava
```

If OpenBao or PostgreSQL state becomes confusing during local development:

```bash
docker compose -f docker-compose.dev.yml down -v
docker compose -f docker-compose.dev.yml up --build
```

## Project Conventions

### Java

* use Java 25
* keep code explicit and readable
* prefer simple records for DTO-style immutable transport shapes
* keep comments short and only where they add real clarity

### Modules

* `controlplane` may use Spring Boot and Spring-managed patterns
* `gateway` should stay lightweight and Netty-native
* `certificate` should avoid Spring and Netty types
* `core` should only hold genuinely shared concerns

### Database

* schema changes go through Flyway migrations
* prefer additive migrations
* use plural table names
* keep SQL readable and deterministic

### Docker

* keep local development friendly
* avoid forcing developers to run app processes manually on the host unless there is a strong reason
* optimize rebuilds where possible, but not at the cost of clarity

## When Making Changes

Please try to keep the following in mind:

* update docs when behavior changes
* update Compose files if service startup assumptions change
* update migrations and entities together
* think about how the change behaves in a fresh database
* think about how the change behaves after container restart

## Pull Request Expectations

A good contribution usually includes:

* a clear summary of what changed
* the reason for the change
* notes about migration, Docker, or local setup impact
* test coverage where the change is behaviorally meaningful

If there are tradeoffs or unfinished edges, mention them directly.

## Good Contribution Areas

High-value contribution areas right now:

* controlplane API cleanup
* gateway request pipeline improvements
* certificate lifecycle improvements
* OpenBao integration hardening
* Docker developer experience
* testing
* documentation

## Things to Avoid

Please avoid:

* introducing Spring-specific code into `gateway`
* introducing Netty-specific types into `certificate`
* storing sensitive secret material directly in PostgreSQL when OpenBao should own it
* broad refactors that rename or relocate everything without clear architectural gain

## Questions and Draft Changes

If a change has architectural consequences, keep the diff small and make the intention obvious. In this stage, a well-shaped foundation change is usually more valuable than a large feature dump.
