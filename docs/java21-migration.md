# Java 21 / Spring Boot 3 migration

## Temporary parent bridge

The user approved a temporary exception to the parent-first rule: the foundational
parent build uses Boot and Cloud BOMs instead of inheriting the Boot starter parent
while application modules migrate in separate PRs. The final cleanup PR must
restore `spring-boot-starter-parent:3.5.16` and remove the bridge.

All modules compile for Java 21. The wrapper runs Maven 3.9.9. Shared build plugins
use the Boot 3 packaging format, even when an application still uses the legacy
Boot 2 runtime. Unchanged modules select Boot 2.7.18 / Cloud 2021.0.9 by default.

A module migrating to the final platform adds these properties in its own POM:

```xml
<petclinic.boot.version>3.5.16</petclinic.boot.version>
<petclinic.cloud.version>2025.0.3</petclinic.cloud.version>
```

No local BOM imports or changes to the project-parent coordinate `2.6.7` are
needed. Business modules must also select compatible Chaos Monkey 3.3.0 while
upgrading their own dependencies; the temporary shared default remains 2.3.10
for unchanged services. The old MySQL coordinate is temporarily managed at its
original version 8.0.28 until the three database services change coordinates.
Do not override dependency versions just to suppress a mixed-platform graph.

Trace export remains Zipkin, using Boot-managed Micrometer Brave in migrated
modules. Preserve existing sampling, propagation and JSON/REST behavior.

## Validation

Run these commands on JDK 21 with tests enabled:

```bash
mvn -B -pl spring-petclinic-config-server -am verify
mvn -B verify
./mvnw -B verify
```

Replace the module name for each module PR. CI runs both full builds. Preserve all
nine existing test methods and their assertions; the earlier descriptive test
count of ten was inaccurate. Also check real seeded-database startup, routes,
UI/WebJar assets, fallback behavior, tracing, and any module-specific configuration.
Unit/slice tests alone do not establish runtime compatibility.

## Containers

The shared Dockerfile uses Java 21 and the Boot 3 packaging plugin's `tools`
extraction mode for both legacy and migrated runtime jars. All Compose command
overrides use the extracted `application.jar`, avoiding hard-coded loader class
names. Keep dockerize waits and service ports unchanged.

CI builds and starts all seven service images on a Docker-capable amd64 runner,
checks representative endpoints, generated CSS/WebJar assets, and exercises
dockerize plus all six Compose launcher overrides. The current local development
environment does not provide Docker.

This is an isolated image/launcher smoke test, using a native configuration
fixture and disabling remote configuration and Eureka client registration.
It does not start the complete Compose stack, Zipkin, Prometheus or Grafana,
and does not establish cross-service routing or trace export. CI also runs
`python3 scripts/verify_jars.py` for all seven executable jars and all seven
tools-extracted layouts before building the images.

The Git metadata plugin keeps its default JGit implementation and the existing
non-fatal behavior when Git metadata is unavailable. Plain clones report the
correct branch and commit. Linked Git worktrees have an upstream limitation:
the plugin may report the common checkout's metadata instead of the worktree's.
Do not use generated `git.properties` as migration evidence in a linked worktree.

## External configuration follow-ups

Do not modify the external configuration repository as part of this migration.
For Boot 3.5, `management.zipkin.tracing.endpoint` replaces obsolete Zipkin
endpoint keys, `management.tracing.sampling.probability` replaces Sleuth sampling,
and `management.prometheus.metrics.export.enabled` replaces the old metrics
export prefix. Keep necessary local deployment overrides in the owning module.
Gateway 4.3 uses `spring.cloud.gateway.server.webflux.*`.

Do not activate previously dormant validation, caching or Jolokia behavior.
`javax.cache` remains JCache; only applicable Jakarta EE namespaces migrate.
