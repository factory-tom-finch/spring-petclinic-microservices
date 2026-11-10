#!/usr/bin/env python3
"""Run strict mysql profiles against the isolated CI MySQL 5.7 fixture only."""
import json
from pathlib import Path
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
CONFIG_REVISION = "323993ce2519c6d02df63e08bf4458d123d3b611"
SERVICES = ("customers-service", "visits-service", "vets-service")


def check_seeds(service, value):
    if service == "customers-service":
        assert len(value) == 10
        assert {item["id"] for item in value} == set(range(1, 11))
        assert sum(len(item["pets"]) for item in value) == 13
    elif service == "visits-service":
        assert len(value["items"]) == 4
        assert {item["id"] for item in value["items"]} == {1, 2, 3, 4}
        assert {item["petId"] for item in value["items"]} == {7, 8}
    else:
        assert len(value) == 6
        assert {item["id"] for item in value} == set(range(1, 7))


def smoke(service, config, evidence):
    module = "spring-petclinic-" + service
    jars = list((ROOT / module / "target").glob(module + "-*.jar"))
    assert len(jars) == 1, f"Expected one executable jar for {module}"
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    args = [
        "java", "-Xmx384m", "-jar", str(jars[0]),
        "--spring.profiles.active=mysql",
        "--spring.config.additional-location=" + (config / "application.yml").as_uri()
        + "," + (config / (service + ".yml")).as_uri(),
        "--spring.cloud.config.enabled=false", "--eureka.client.enabled=false",
        "--management.tracing.enabled=false",
        "--server.address=127.0.0.1", f"--server.port={port}",
    ]
    # External mysql config keeps init.mode=ALWAYS. All SQL errors remain fatal.
    endpoint = {
        "customers-service": "/owners",
        "visits-service": "/pets/visits?petId=7,8",
        "vets-service": "/vets",
    }[service]
    log = evidence / (service + ".log")
    started = time.monotonic()
    with log.open("w") as output:
        process = subprocess.Popen(args, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
        try:
            deadline = started + 75
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(f"{service} exited before readiness")
                try:
                    request = urllib.request.Request(
                        f"http://127.0.0.1:{port}{endpoint}", headers={"Accept": "application/json"})
                    with urllib.request.urlopen(request, timeout=2) as response:
                        assert response.status == 200
                        value = json.load(response)
                    break
                except (urllib.error.URLError, TimeoutError, ConnectionError):
                    time.sleep(0.5)
            else:
                raise TimeoutError(f"{service} failed MySQL readiness")
            check_seeds(service, value)
            result = {"service": service, "profile": "mysql", "endpoint": endpoint,
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
    config = Path(sys.argv[1]).resolve()
    revision = subprocess.check_output(
        ["git", "-C", str(config), "rev-parse", "HEAD"], text=True).strip()
    assert revision == CONFIG_REVISION, "Unexpected external configuration fixture revision"
    evidence = ROOT / "target" / "mysql-smoke"
    evidence.mkdir(parents=True, exist_ok=True)
    results = []
    try:
        # One shared DB: customers must create pets before visits creates its FK.
        for service in SERVICES:
            results.append(smoke(service, config, evidence))
    except Exception:
        for service in SERVICES:
            log = evidence / (service + ".log")
            if log.exists():
                print(f"--- {service}: last startup log lines ---", file=sys.stderr)
                print("\n".join(log.read_text().splitlines()[-60:]), file=sys.stderr)
        raise
    (evidence / "results.json").write_text(json.dumps(results, indent=2) + "\n")


if __name__ == "__main__":
    main()
