#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
work=$(mktemp -d)
prefix="petclinic-ci-$$"
network="$prefix-network"
containers=""
cleanup() {
    for container in $containers; do
        docker rm -f "$container" >/dev/null 2>&1 || true
    done
    docker network rm "$network" >/dev/null 2>&1 || true
    rm -r "$work"
}
trap cleanup EXIT HUP INT TERM

docker compose -f docker-compose.yml config --format json > "$work/compose.json"
python3 - "$work/compose.json" <<'PY'
import json
import sys

services = json.load(open(sys.argv[1]))["services"]
for service in ("discovery-server", "customers-service", "visits-service",
                "vets-service", "api-gateway", "admin-server"):
    dependency = "config-server:8888" if service == "discovery-server" else "discovery-server:8761"
    expected = ["./dockerize", "-wait=tcp://" + dependency, "-timeout=60s",
                "--", "java", "-jar", "application.jar"]
    assert services[service]["entrypoint"] == expected, service
PY

mkdir "$work/config"
printf 'migration.marker: java21\n' > "$work/config/application.yml"
docker network create --internal "$network" >/dev/null

for service in config-server discovery-server customers-service visits-service vets-service api-gateway admin-server; do
    module="spring-petclinic-$service"
    jar="$module/target/$module-2.6.7.jar"
    test -f "$jar"
    image="petclinic-ci/$service:java21"
    echo "Building $service"
    docker build --file docker/Dockerfile --tag "$image" \
        --build-arg "ARTIFACT_NAME=$module-2.6.7" \
        --build-arg EXPOSED_PORT=8080 --build-arg DOCKERIZE_VERSION=v0.6.1 "$module/target"

    name="$prefix-$service"
    containers="$containers $name"
    port=8080
    accept=application/json
    case "$service" in
        config-server) port=8888; path=/application/default ;;
        discovery-server) port=8761; path=/eureka/apps ;;
        customers-service) path=/owners ;;
        visits-service) path='/pets/visits?petId=7,8' ;;
        vets-service) path=/vets ;;
        api-gateway|admin-server) path=/; accept=text/html ;;
    esac
    set -- "--server.port=$port" --spring.cloud.config.enabled=false \
        --eureka.client.register-with-eureka=false --eureka.client.fetch-registry=false \
        --spring.jpa.hibernate.ddl-auto=none --spring.jpa.open-in-view=false \
        '--spring.sql.init.schema-locations=classpath*:db/hsqldb/schema.sql' \
        '--spring.sql.init.data-locations=classpath*:db/hsqldb/data.sql'
    if [ "$service" = config-server ]; then
        docker run --detach --name "$name" --network "$network" --network-alias "$service" \
            --memory 512m --env JAVA_TOOL_OPTIONS=-Xmx384m --env SPRING_PROFILES_ACTIVE=native \
            --volume "$work/config:/fixture:ro" "$image" "$@" \
            --spring.cloud.config.server.native.search-locations=file:/fixture >/dev/null
    else
        dependency=discovery-server:8761
        if [ "$service" = discovery-server ]; then
            dependency=config-server:8888
        fi
        # Exercise the exact Compose launcher, including dockerize's wait and exec.
        docker run --detach --name "$name" --network "$network" --network-alias "$service" \
            --memory 512m --env JAVA_TOOL_OPTIONS=-Xmx384m --env SPRING_PROFILES_ACTIVE=container-test \
            --entrypoint ./dockerize "$image" "-wait=tcp://$dependency" -timeout=60s \
            -- java -jar application.jar "$@" >/dev/null
    fi

    ready=false
    attempt=0
    while [ "$attempt" -lt 90 ]; do
        if docker exec "$name" wget -qO- --header="Accept: $accept" \
            "http://127.0.0.1:$port$path" > "$work/response"; then
            ready=true
            break
        fi
        if [ "$(docker inspect --format '{{.State.Running}}' "$name")" != true ]; then
            break
        fi
        attempt=$((attempt + 1))
        sleep 1
    done
    if [ "$ready" != true ]; then
        docker logs "$name"
        echo "$service failed its startup/endpoint check" >&2
        exit 1
    fi
    python3 - "$service" "$work/response" <<'PY'
import json
import sys

service, path = sys.argv[1:]
body = open(path).read()
if service == "config-server":
    config = json.loads(body)
    assert any(source["source"].get("migration.marker") == "java21"
               for source in config["propertySources"])
elif service == "discovery-server":
    assert "applications" in json.loads(body)
elif service in ("customers-service", "vets-service"):
    values = json.loads(body)
    assert isinstance(values, list) and values and values[0]["id"] == 1
elif service == "visits-service":
    assert isinstance(json.loads(body)["items"], list)
else:
    assert "<html" in body.lower()
PY
    if [ "$service" = api-gateway ]; then
        for asset in /css/petclinic.css /webjars/jquery/jquery.min.js \
            /webjars/bootstrap/css/bootstrap.min.css /webjars/angularjs/angular.min.js \
            /webjars/angular-ui-router/angular-ui-router.min.js; do
            docker exec "$name" wget -qO- "http://127.0.0.1:$port$asset" > "$work/asset"
            test "$(wc -c < "$work/asset")" -gt 100
        done
    fi
    docker exec "$name" java -version
    echo "Verified $service image and launcher"
    case "$service" in
        config-server|discovery-server) ;;
        *) docker rm -f "$name" >/dev/null ;;
    esac
done
