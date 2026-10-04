#!/usr/bin/env python3
"""Isolated real four-service deployment acceptance (Python 3.10+, Docker Compose).
Creates independent secrets/random localhost ports; destroys only its own disposable project.
"""
import base64
import hashlib
import http.client as http_client
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.parse
import uuid

ROOT = Path(__file__).resolve().parents[2]
run_id = uuid.uuid4().hex[:12]
project = "link-smoke-" + run_id
report = ROOT / "target" / "compose-smoke" / run_id
report.mkdir(parents=True)
secret_file = report / ".env.local"
env = {k: v for k, v in os.environ.items() if not k.upper().startswith(
    ("DB_", "MYSQL_", "REDIS_", "RABBIT", "SHORT_LINK_", "SPRING_", "APP_", "SERVER_", "COMPOSE_"))
    and k.upper() not in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")}
secrets = []
checks = []

def check(condition, name):
    if not condition:
        raise RuntimeError("Acceptance failed: " + name)
    checks.append(name)
    print("PASS " + name, flush=True)

def safe(text):
    for value in secrets:
        text = text.replace(value, "[REDACTED]")
    return text

def cmd(argv, label, timeout=600, required=True):
    result = subprocess.run(argv, cwd=ROOT, env=env, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, errors="replace", timeout=timeout)
    (report / (label + ".log")).write_text(safe(result.stdout), encoding="utf-8")
    if required and result.returncode:
        raise RuntimeError(label + " failed; inspect safe report")
    return result.stdout

def until(test, name, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            value = test()
            if value:
                return value
        except (OSError, ValueError, http_client.HTTPException):
            pass
        time.sleep(1)
    raise RuntimeError("Timed out: " + name)

compose = []
started = False
failure = None
try:
    initializer = (["pwsh", "-NoProfile", "-File", str(ROOT / "ops/init-local-secrets.ps1"), "-Path"]
                   if os.name == "nt" else ["sh", str(ROOT / "ops/init-local-secrets.sh")])
    cmd(initializer + [str(secret_file)], "init-secrets")
    digest = hashlib.sha256(secret_file.read_bytes()).digest()
    values = dict(line.split("=", 1) for line in secret_file.read_text().splitlines() if "=" in line)
    secrets.extend(values[k] for k in values if any(s in k for s in ("PASSWORD", "TOKEN", "HMAC_KEY")))
    check(len(set(secrets)) == 5, "all five local credentials are independently generated")
    cmd(initializer + [str(secret_file)], "repeat-init")
    check(hashlib.sha256(secret_file.read_bytes()).digest() == digest, "repeat initialization preserves independent secrets")
    # Published ports are assigned by Docker, not selected with a racy free-port check.
    override = report / "ports.yml"
    override.write_text('services:\n  app:\n    ports: !override ["127.0.0.1::8080"]\n  rabbitmq:\n    ports: !override ["127.0.0.1::15672"]\n', encoding="utf-8")
    compose = ["docker", "compose", "--project-name", project, "--env-file", str(secret_file),
               "--file", str(ROOT / "compose.yml"), "--file", str(override)]
    cmd(["docker", "version", "--format", "{{.Server.Version}}"], "docker-version", 30)
    cmd(["docker", "compose", "version"], "compose-version", 30)
    cmd(compose + ["config", "--quiet"], "compose-validation", 30)
    print("Building real multistage image; isolated project " + project, flush=True)
    cmd(compose + ["build", "app"], "build", 1200)
    started = True
    cmd(compose + ["up", "--detach", "--wait", "--wait-timeout", "240"], "clean-up", 300)
    def port(service, number):
        return int(cmd(compose + ["port", service, str(number)], "port-" + service, 30).strip().rsplit(":", 1)[1])
    app_port = port("app", 8080)
    mq_port = port("rabbitmq", 15672)
    def http(method, path, payload=None, headers=None, management=False):
        connection = http_client.HTTPConnection("127.0.0.1", mq_port if management else app_port, timeout=10)
        body = json.dumps(payload).encode() if payload is not None else None
        headers = dict(headers or {})
        if body is not None:
            headers["Content-Type"] = "application/json"
        if management:
            token = base64.b64encode((values["RABBITMQ_USERNAME"] + ":" + values["RABBITMQ_PASSWORD"]).encode()).decode()
            headers["Authorization"] = "Basic " + token
        connection.request(method, path, body, headers)
        response = connection.getresponse()
        data = response.read()
        result = response.status, dict(response.getheaders()), json.loads(data) if data and response.getheader("Content-Type", "").startswith("application/json") else data
        connection.close()
        return result
    until(lambda: http("GET", "/")[0] == 200, "existing root HTTP ready")
    check(http("GET", "/")[0] == 200, "application HTTP ready")
    initial_vhost = urllib.parse.quote(values["RABBITMQ_VIRTUAL_HOST"], safe="")
    until(lambda: (reply := http("GET", "/api/queues/" + initial_vhost + "/shortlink.visit.stats.q", management=True))[0] == 200 and reply[2].get("consumers", 0) == 1, "actual queue/consumer startup")
    status, headers, created = http("POST", "/api/links", {"originalUrl": "https://example.com/compose-acceptance"})
    check(status == 201, "new account/schema supports actual create")
    code = created["shortCode"]
    status, headers, _ = http("GET", "/s/" + code)
    cookie = headers.get("Set-Cookie", "").split(";", 1)[0]
    check(status == 302 and headers["Location"] == "https://example.com/compose-acceptance" and headers["Cache-Control"] == "no-store" and bool(cookie), "redirect and collection cookie")
    def stats():
        status, _, data = http("GET", "/api/internal/links/" + code + "/stats", headers={"X-Internal-Token": values["SHORT_LINK_INTERNAL_TOKEN"]})
        return data if status == 200 else None
    until(lambda: (data := stats()) and data["pv"] == 1 and data["uv"] == 1, "asynchronous statistics")
    check(True, "publisher route consumer records PV and UV")
    encoded = urllib.parse.quote(values["RABBITMQ_VIRTUAL_HOST"], safe="")
    def queue():
        status, _, data = http("GET", "/api/queues/" + encoded + "/shortlink.visit.stats.q", management=True)
        return data if status == 200 else None
    def policies():
        status, _, data = http("GET", "/api/policies/" + encoded, management=True)
        return {item["name"]: {k: item[k] for k in ("pattern", "apply-to", "priority", "definition")} for item in data} if status == 200 else {}
    expected = {item["name"]: {k: v for k, v in item.items() if k != "name"} for item in json.loads((ROOT / "ops/visit-consumer-policies.json").read_text())}
    check(policies() == expected, "actual broker policies match authoritative source")
    q = until(lambda: (q := queue()) and q.get("consumers", 0) == 1 and q, "broker consumer observation")
    check(q and q["durable"] and q.get("consumers", 0) == 1, "durable business queue has live consumer")
    _, _, bindings = http("GET", "/api/bindings/" + encoded, management=True)
    check(any(b["source"] == "shortlink.visit.x" and b["destination"] == "shortlink.visit.stats.q" and b["routing_key"] == "visit.occurred.v1" for b in bindings), "actual exchange routing declared")
    # Disable only consuming in a recreated app, keeping collection and broker publishing.
    env["SHORT_LINK_STATS_CONSUMER_ENABLED"] = "false"
    cmd(compose + ["up", "--detach", "--force-recreate", "app"], "pause-consumer", 120)
    app_port = port("app", 8080)
    until(lambda: http("GET", "/")[0] == 200, "paused app")
    until(lambda: (q := queue()) and q.get("consumers", -1) == 0, "consumer paused")
    check(http("GET", "/s/" + code, headers={"Cookie": cookie})[0] == 302, "mapping survives app recreation")
    until(lambda: (q := queue()) and q.get("messages_ready", 0) >= 1, "durable queued backlog")
    check(stats()["pv"] == 1, "paused consumer leaves visit queued")
    cmd(compose + ["stop"], "ordinary-stop", 120)
    cmd(compose + ["up", "--detach", "--wait", "--wait-timeout", "240"], "ordinary-restart", 300)
    app_port = port("app", 8080)
    mq_port = port("rabbitmq", 15672)
    until(lambda: http("GET", "/")[0] == 200, "restarted app")
    until(lambda: (q := queue()) and q.get("messages_ready", 0) >= 1, "persisted backlog")
    check(stats()["pv"] == 1 and stats()["uv"] == 1, "ordinary restart preserves recorded visits")
    check(True, "ordinary restart preserves RabbitMQ queued backlog")
    check(policies() == expected, "repeat bootstrap preserves policy and backlog")
    env["SHORT_LINK_STATS_CONSUMER_ENABLED"] = "true"
    cmd(compose + ["up", "--detach", "--force-recreate", "app"], "resume-consumer", 120)
    app_port = port("app", 8080)
    until(lambda: (data := stats()) and data["pv"] == 2 and data["uv"] == 1, "backlog consumption and stable UV")
    check(hashlib.sha256(secret_file.read_bytes()).digest() == digest, "identity key remains stable across ordinary restarts")
    check(True, "persisted queued visit consumed once with same anonymous UV")
    ids = cmd(compose + ["ps", "--quiet"], "container-ids", 30).split()
    # Read metadata in memory only: inspect contains environment secrets.
    inspected_result = subprocess.run(["docker", "inspect"] + ids, cwd=ROOT, env=env,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=30)
    if inspected_result.returncode:
        raise RuntimeError("Container metadata inspection failed")
    inspected = json.loads(inspected_result.stdout)
    by_service = {c["Config"]["Labels"]["com.docker.compose.service"]: c for c in inspected}
    check(set(by_service) == {"app", "mysql", "redis", "rabbitmq"}, "exactly four resident services")
    check(by_service["app"]["Config"]["User"] == "10001:10001", "application runs non-root")
    for service, budget in (("app", 768), ("mysql", 1024), ("redis", 256), ("rabbitmq", 1024)):
        check(by_service[service]["HostConfig"]["Memory"] == budget * 1024 * 1024, service + " memory budget")
    for service, internal in (("mysql", "3306/tcp"), ("redis", "6379/tcp"), ("rabbitmq", "5672/tcp")):
        check(not by_service[service]["NetworkSettings"]["Ports"].get(internal), service + " business port not published")
    for service in ("app", "rabbitmq"):
        check(all(binding["HostIp"] == "127.0.0.1" for bindings in by_service[service]["NetworkSettings"]["Ports"].values() if bindings for binding in bindings), service + " published only on localhost")
    check(all(any(m["Type"] == "volume" for m in by_service[s]["Mounts"]) for s in ("mysql", "rabbitmq")), "database and broker named persistence volumes")
    cfg = cmd(compose + ["exec", "-T", "redis", "redis-cli", "CONFIG", "GET", "maxmemory", "maxmemory-policy", "save", "appendonly"], "redis-config", 30).splitlines()
    settings = dict(zip(cfg[::2], cfg[1::2]))
    check(settings == {"maxmemory": "134217728", "maxmemory-policy": "noeviction", "save": "", "appendonly": "no"}, "Redis bounded noeviction and nonpersistent")
    # Clean reconstruction with all writers stopped implements accepted recovery protocol.
    cmd(compose + ["stop", "app"], "recovery-stop-writer", 120)
    cmd(compose + ["rm", "--stop", "--force", "redis"], "recovery-remove-redis", 60)
    cmd(compose + ["up", "--detach", "--wait", "redis"], "recovery-clean-redis", 120)
    check(cmd(compose + ["exec", "-T", "redis", "redis-cli", "DBSIZE"], "recovery-empty", 30).strip() == "0", "Redis reconstructed empty with writers stopped")
    cmd(compose + ["up", "--detach", "app"], "recovery-app", 120)
    app_port = port("app", 8080)
    until(lambda: http("GET", "/")[0] == 200, "recovery HTTP")
    check(http("GET", "/s/" + code, headers={"Cookie": cookie})[0] == 302, "mapping survives controlled Redis reconstruction")
    until(lambda: (data := stats()) and data["pv"] == 3 and data["uv"] == 1, "recovery statistics")
    check(True, "controlled recovery rebuilds cache and retains identity")
    # Core startup only waits on MySQL: actually start with Redis/MQ stopped.
    cmd(compose + ["stop", "redis", "rabbitmq"], "optional-dependencies-stop", 120)
    cmd(compose + ["up", "--detach", "--force-recreate", "app"], "core-only-start", 120)
    app_port = port("app", 8080)
    until(lambda: http("GET", "/")[0] == 200, "core startup without Redis/MQ")
    check(http("GET", "/s/" + code, headers={"Cookie": cookie})[0] == 302,
          "core starts and redirects while Redis and MQ are stopped")
    # All writers stopped, then clean nonpersistent Redis recovery again.
    cmd(compose + ["stop", "app"], "core-only-stop", 120)
    cmd(compose + ["up", "--detach", "--wait", "redis", "rabbitmq"], "dependencies-resume", 180)
    check(cmd(compose + ["exec", "-T", "redis", "redis-cli", "DBSIZE"], "resume-empty", 30).strip() == "0", "optional dependencies recovered with no old Redis writers")
    cmd(compose + ["up", "--detach", "app"], "all-services-resume", 120)
    app_port = port("app", 8080)
    until(lambda: http("GET", "/")[0] == 200, "complete services resumed")
    cmd(compose + ["logs", "--no-color", "--tail", "200"], "safe-service-logs", 30)
    cmd(["docker", "stats", "--no-stream", "--format", "{{.Name}} {{.MemUsage}} {{.CPUPerc}}"] + cmd(compose + ["ps", "--quiet"], "final-container-ids", 30).split(), "resource-observation", 30)
except Exception as error:
    failure = type(error).__name__ + ": " + safe(str(error))
    print(failure, file=sys.stderr, flush=True)
    if started:
        cmd(compose + ["logs", "--no-color", "--tail", "200"], "failure-service-logs", 30, required=False)
finally:
    if started:
        try:
            cmd(compose + ["down", "--volumes", "--remove-orphans"], "isolated-cleanup", 120)
        except Exception:
            failure = failure or "Cleanup failed; isolated project: " + project
    secret_file.unlink(missing_ok=True)
    (report / "summary.json").write_text(json.dumps({"project": project, "passed": checks, "failure": failure}, indent=2), encoding="utf-8")
    print("Safe reports: " + str(report), flush=True)
if failure:
    sys.exit(1)
