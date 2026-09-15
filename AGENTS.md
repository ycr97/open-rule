# Repository Guidelines

## Project Structure & Module Organization

This Java 21 Maven reactor has four modules:

- `openrule-core/`: dependency-free definitions, compiler, execution runtime, SPIs, and results.
- `openrule-spring/`: Spring integration, flow loading, service orchestration, ports, and in-memory adapters.
- `openrule-api/`: REST controllers, DTOs, exception handling, and API tests.
- `openrule-jdbc/`: JDBC persistence, audit logging, schema resources, and Spring Boot auto-configuration.

Production code uses `src/main/java`; tests mirror packages in `src/test/java`; resources use `src/main/resources`.

Authority order is: accepted `docs/adr/`, `docs/design/`, active `docs/plans/`, `docs/roadmap/`, then the root technical specification as a historical baseline. Sessions belong in `docs/sessions/`. Do not implement a discovered gap until its design is ready.

## Build, Test, and Development Commands

Run from the repository root with JDK 21:

```bash
mvn clean verify
mvn -pl openrule-core test
mvn -pl openrule-api -am test
mvn -Pintegration -pl openrule-jdbc -am verify
```

`clean verify` builds the reactor and runs unit tests; the default build skips `*IT`. Use `-pl` to select modules and `-am` to build dependencies. The `integration` profile runs JDBC `*IT` through Failsafe; it requires Docker and fails when Docker is unavailable.

## Coding Style & Naming Conventions

Use four-space indentation, UTF-8, and standard Java conventions. Packages are lowercase under `io.openrule`; types use `UpperCamelCase`, members `lowerCamelCase`, and constants `UPPER_SNAKE_CASE`. Prefer immutable records for value objects. Lombok is available, but must not hide non-trivial behavior.

Dependencies flow API/JDBC → Spring → Core. Core must not depend on Spring, Jackson, logging facades, JDBC, Redis, Micrometer, or script runtimes. No formatter is enforced; match adjacent code and organize imports consistently.

## Testing Guidelines

Tests use JUnit 5 and AssertJ; Spring modules use `spring-boot-starter-test`. Name unit tests `*Test` and container tests `*IT`. Test behavior, add regression coverage, and run targeted tests plus `mvn clean verify`. Run affected MySQL IT when JDBC behavior or schema changes.

Before OSS 1.0, public Java APIs may change to correct architecture. Persisted Definition JSON v1 must remain readable; new writes use the current schema. Document API and data migrations explicitly.

## Commit & Pull Request Guidelines

Use focused Conventional Commit-style subjects such as `feat(jdbc): ...`, `feat(api): ...`, and `docs: ...`.

Pull requests must summarize behavior, list affected modules, link issues or design documents, and report verification commands. Include request/response examples for API changes and migration notes for schema or configuration changes. Screenshots are only required for documentation or UI changes.
