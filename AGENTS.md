# AGENTS.md

Spring PetClinic Microservices: seven Spring Boot services in one Maven multi-module build. This repo is the starting point for a Java and Spring Boot modernization. The code currently targets **Java 8, Spring Boot 2.6.7 and Spring Cloud 2021.0.2**.

## Modules

| Module | Role | Local port |
|---|---|---|
| `spring-petclinic-config-server` | Spring Cloud Config server | 8888 |
| `spring-petclinic-discovery-server` | Eureka service registry | 8761 |
| `spring-petclinic-customers-service` | Owners and pets REST API (JPA, HSQLDB) | 8081 |
| `spring-petclinic-visits-service` | Visits REST API (JPA, HSQLDB) | 8082 |
| `spring-petclinic-vets-service` | Vets REST API (JPA, HSQLDB, cache) | 8083 |
| `spring-petclinic-api-gateway` | Spring Cloud Gateway, Resilience4j, AngularJS UI | 8080 |
| `spring-petclinic-admin-server` | Spring Boot Admin | 9090 |

The root `pom.xml` is the parent of every module and owns the Java version, the Spring Boot parent and the Spring Cloud BOM.

## Build and test

`mvn -B verify` from the repo root is the source of truth. It compiles every module and runs all tests. On the legacy baseline it passes with 10 tests.

With a local JDK:

```bash
./mvnw -B verify
./mvnw -B -pl spring-petclinic-vets-service -am verify   # one module plus what it depends on
```

Without a local JDK, use Docker. Pick the image that matches the Java version the code targets:

```bash
# Legacy baseline (Java 8 bytecode, built on JDK 11)
docker run --rm -v "$PWD":/app -v petclinic-m2:/root/.m2 -w /app maven:3.8-eclipse-temurin-11 mvn -B verify

# After the migration (Java 21)
docker run --rm -v "$PWD":/app -v petclinic-m2:/root/.m2 -w /app maven:3.9-eclipse-temurin-21 mvn -B verify
```

Two builds in the same checkout overwrite each other's `target/` folders, so do not run them at the same time.

## Running the stack

- `scripts/run_all.sh` starts every service from its built jar and writes logs to `target/<app>.log`. It needs a local JDK and runs `docker-compose` for Zipkin, Prometheus and Grafana.
- `docker-compose up` needs images built with `./mvnw clean install -P buildDocker`. `docker/Dockerfile` uses the old `openjdk:11-jre` and `adoptopenjdk:11-jre-hotspot` base images and an amd64-only `dockerize` binary, so expect this path to need work.
- At runtime the config server reads from the external repo `spring-petclinic/spring-petclinic-microservices-config` (branch `main`), not from this repo. Do not change that repo. If a property rename affects it, record it in the migration plan instead.

## Migration goal

Move every module to **Java 21 and Spring Boot 3.x**, with the Spring Cloud release train that matches the chosen Boot version. Behaviour must stay the same: the same REST endpoints, JSON shapes, routes and UI.

Expected work, so nothing is missed:

- `javax.persistence` and `javax.validation` to `jakarta.*` in customers, vets and visits.
- Spring Cloud Sleuth (`spring-cloud-starter-sleuth`, `spring-cloud-sleuth-zipkin`) has no Boot 3 version. It must be replaced in customers, vets, visits and api-gateway.
- Lombok must be on a version that compiles on Java 21.
- Check `chaos-monkey-spring-boot`, `spring-boot-admin`, `jolokia-core` and the `wro4j-maven-plugin` setup in api-gateway (it has a Java 9+ workaround) for Boot 3 and Java 21 compatibility.
- Update `docker/Dockerfile` base images and `.github/workflows/maven-build.yml`. CI runs on pushes and PRs to `main` and currently uses JDK 11.

## Rules for agents

- **Ask before choosing a tracing backend** to replace Sleuth (for example Micrometer Tracing with Zipkin or with OpenTelemetry). This is a decision for the user.
- **Parent first.** The root `pom.xml` change (Java version, Boot parent, Cloud BOM, shared plugin versions) lands as its own PR before any service PR merges.
- **One PR per module.** Branch name `migrate/<module-name>`, PR title `[migration] <module-name>: <summary>`, base branch `main`.
- **Stay in your module.** Only edit files in the module you own. If you need a change in the parent POM or another module, report it to the orchestrator instead of making it.
- **No behaviour changes.** Do not rename endpoints, change JSON fields, change the database schema or seed data, or refactor beyond what the upgrade needs.
- **Do not weaken tests.** Do not delete, skip or loosen existing assertions to get a green build. Adding tests is fine.
- Keep the existing code style (see `.editorconfig`) and leave the Grafana and Prometheus config alone unless a metric name changes.

## Definition of done for a module PR

1. `mvn -B verify` passes for that module on JDK 21 (use `-pl <module> -am`), and the full build still passes.
2. No `javax.persistence`, `javax.validation` or Sleuth references remain in the module.
3. The PR description lists the dependency and property changes, and any follow-ups for other modules or for the external config repo.
