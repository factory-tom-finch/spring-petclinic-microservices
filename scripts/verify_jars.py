#!/usr/bin/env python3
"""Smoke-test every built jar and its Boot tools-extracted layout on the local JDK."""
import json
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / "target" / "migration-smoke"
SERVICES = (
    "config-server", "discovery-server", "customers-service", "visits-service",
    "vets-service", "api-gateway", "admin-server",
)
ASSETS = (
    "/css/petclinic.css", "/webjars/jquery/jquery.min.js",
    "/webjars/bootstrap/css/bootstrap.min.css", "/webjars/angularjs/angular.min.js",
    "/webjars/angular-ui-router/angular-ui-router.min.js",
)


def request(port, path, accept="application/json"):
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}", headers={"Accept": accept})
    with urllib.request.urlopen(request, timeout=2) as response:
        assert response.status == 200
        return response.read()


def check_body(service, body):
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
        assert b"<html" in body.lower()


def smoke(service, layout, fixture):
    module = "spring-petclinic-" + service
    jar = ROOT / module / "target" / (module + "-2.6.7.jar")
    with zipfile.ZipFile(jar) as archive:
        manifest = archive.read("META-INF/MANIFEST.MF").decode()
        assert "Spring-Boot-Version: 3.5.16" in manifest
        assert "org/springframework/boot/loader/launch/JarLauncher.class" in archive.namelist()
    with tempfile.TemporaryDirectory(prefix="petclinic-layout-") as directory:
        directory = Path(directory)
        if layout == "extracted":
            # Docker copies the executable jar to this name before extracting it.
            source = directory / "application.jar"
            shutil.copy2(jar, source)
            subprocess.run(
                ["java", "-Djarmode=tools", "-jar", str(source), "extract", "--layers",
                 "--destination", str(directory / "layers")], check=True,
                stdout=subprocess.DEVNULL)
            # Match the four Docker COPY instructions, without changing the launcher.
            for layer in ("dependencies", "spring-boot-loader", "snapshot-dependencies", "application"):
                shutil.copytree(directory / "layers" / layer, directory / "runtime", dirs_exist_ok=True)
            cwd = directory / "runtime"
            application = cwd / "application.jar"
        else:
            cwd = directory
            application = jar
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        args = [
            "java", "-Xmx384m", "-jar", str(application),
            "--server.address=127.0.0.1", f"--server.port={port}",
            "--spring.cloud.config.enabled=false",
            "--eureka.client.register-with-eureka=false", "--eureka.client.fetch-registry=false",
            "--spring.jpa.hibernate.ddl-auto=none", "--spring.jpa.open-in-view=false",
            "--spring.sql.init.schema-locations=classpath*:db/hsqldb/schema.sql",
            "--spring.sql.init.data-locations=classpath*:db/hsqldb/data.sql",
        ]
        if service == "config-server":
            args += ["--spring.profiles.active=native",
                     "--spring.cloud.config.server.native.search-locations=" + fixture.as_uri()]
        paths = {
            "config-server": "/application/default", "discovery-server": "/eureka/apps",
            "customers-service": "/owners", "visits-service": "/pets/visits?petId=7,8",
            "vets-service": "/vets",
        }
        path = paths.get(service, "/")
        log = EVIDENCE / f"{service}-{layout}.log"
        started = time.monotonic()
        with log.open("w") as output:
            process = subprocess.Popen(args, cwd=cwd, stdout=output, stderr=subprocess.STDOUT)
            try:
                deadline = started + 90
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise RuntimeError(f"{service}/{layout} exited: {log}")
                    try:
                        body = request(port, path, "text/html" if service in
                                       ("api-gateway", "admin-server") else "application/json")
                        break
                    except (urllib.error.URLError, TimeoutError, ConnectionError):
                        time.sleep(0.25)
                else:
                    raise TimeoutError(f"{service}/{layout} failed readiness: {log}")
                check_body(service, body)
                if service == "api-gateway":
                    for asset in ASSETS:
                        assert len(request(port, asset, "*/*")) > 100, asset
                result = {"service": service, "layout": layout, "endpoint": path,
                          "seconds": round(time.monotonic() - started, 3), "result": "pass"}
                print(json.dumps(result), flush=True)
                return result
            finally:
                process.terminate()
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


def main():
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    results = []
    with tempfile.TemporaryDirectory(prefix="petclinic-config-") as fixture:
        fixture = Path(fixture)
        (fixture / "application.yml").write_text("migration.marker: java21\n")
        for layout in ("jar", "extracted"):
            for service in SERVICES:
                results.append(smoke(service, layout, fixture))
    (EVIDENCE / "results.json").write_text(json.dumps(results, indent=2) + "\n")


if __name__ == "__main__":
    main()
