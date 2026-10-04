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
import socket
import sys
import time
import urllib.parse
import uuid

ROOT = Path(__file__).resolve().parents[2]
run_id = uuid.uuid4().hex[:12]
project = "link-smoke-" + run_id
report = ROOT / "target" / "compose-smoke" / run_id
report.mkdir(parents=True)
print("Safe reports: " + str(report), flush=True)
secret_file = report / ".env.local"
env = {k: v for k, v in os.environ.items() if not k.upper().startswith(
    ("DB_", "MYSQL_", "REDIS_", "RABBIT", "SHORT_LINK_", "SPRING_", "APP_", "SERVER_", "MANAGEMENT_", "COMPOSE_"))
    and k.upper() not in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")}
secrets = []
checks = []
versions = {"python": sys.version.split()[0], "host": sys.platform}

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
image_built = False
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
    override.write_text('services:\n  app:\n    image: link-smoke-app-' + run_id + ':acceptance\n    ports: !override ["127.0.0.1::8080", "127.0.0.1::8081"]\n  rabbitmq:\n    ports: !override ["127.0.0.1::15672"]\n', encoding="utf-8")
    compose = ["docker", "compose", "--project-name", project, "--env-file", str(secret_file),
               "--file", str(ROOT / "compose.yml"), "--file", str(override)]
    versions["docker"] = cmd(["docker", "version", "--format", "{{.Server.Version}}"], "docker-version", 30).strip()
    versions["compose"] = cmd(["docker", "compose", "version"], "compose-version", 30).strip()
    versions["sourceCommit"] = cmd(["git", "rev-parse", "HEAD"], "source-commit", 30).strip()
    cmd(compose + ["config", "--quiet"], "compose-validation", 30)
    print("Building real multistage image; isolated project " + project, flush=True)
    cmd(compose + ["build", "app"], "build", 1200)
    image_built = True
    started = True
    cmd(compose + ["up", "--detach", "--wait", "--wait-timeout", "240"], "clean-up", 300)
    def port(service, number):
        return int(cmd(compose + ["port", service, str(number)], "port-" + service, 30).strip().rsplit(":", 1)[1])
    app_port = port("app", 8080)
    mq_port = port("rabbitmq", 15672)
    operations_port = port("app", 8081)
    def http(method, path, payload=None, headers=None, management=False, operational=False):
        connection = http_client.HTTPConnection("127.0.0.1", operations_port if operational else mq_port if management else app_port, timeout=10)
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
        result = response.status, dict(response.getheaders()), json.loads(data) if data and response.getheader("Content-Type", "").split(";", 1)[0].endswith(("application/json", "+json")) else data
        connection.close()
        return result
    until(lambda: http("GET", "/")[0] == 200, "existing root HTTP ready")
    check(http("GET", "/")[0] == 200, "application HTTP ready")
    # Query the real generated contract and shipped Swagger resources in the final image.
    status, _, document = http("GET", "/v3/api-docs")
    expected_paths = {"/api/links", "/s/{code}", "/api/links/{code}/enabled",
                      "/api/internal/links/{code}/stats", "/api/internal/links/{code}/visits"}
    check(status == 200 and set(document["paths"]) == expected_paths,
          "final image generates exactly the five business OpenAPI routes")
    check(not any(value in json.dumps(document) for value in secrets) and "/actuator/" not in json.dumps(document),
          "actual business document has no credentials or Actuator operations")
    for path in expected_paths:
        method = "post" if path == "/api/links" else "put" if path.endswith("/enabled") else "get"
        operation = document["paths"][path][method]
        check("503" in operation["responses"] and
              {"Retry-After", "Cache-Control"}.issubset(operation["responses"]["429"]["headers"]),
              "actual contract preserves distinct 503 and rate rejection headers " + path)
        if method == "get":
            head = document["paths"][path]["head"]
            check(all("content" not in response for response in head["responses"].values()),
                  "all documented HEAD results have no response body " + path)
        if "internal" in path or path.endswith("/enabled"):
            check(operation["security"] == [{"InternalToken": []}],
                  "actual management contract requires the token header " + path)
    check(document["components"]["securitySchemes"]["InternalToken"]["name"] == "X-Internal-Token",
          "actual security scheme identifies the existing management header")
    for code_name in ("RATE_LIMIT_UNAVAILABLE", "REDIRECT_LOAD_BUSY", "STATS_BUSY", "STATS_QUERY_TIMEOUT",
                      "CREATE_CACHE_COORDINATION_UNCONFIRMED", "LINK_STATE_CACHE_COORDINATION_UNCONFIRMED"):
        check(code_name in json.dumps(document), "final contract distinguishes error " + code_name)
    status, _, swagger = http("GET", "/v3/api-docs/swagger-config")
    check(status == 200 and swagger.get("persistAuthorization") is False
          and not any(value in json.dumps(swagger) for value in secrets),
          "actual Swagger config never persists or prefills the generated credentials")
    check(http("GET", "/swagger-ui/index.html")[0] == 200, "final image serves local Swagger UI")
    status, _, initializer_js = http("GET", "/swagger-ui/swagger-initializer.js")
    check(status == 200 and b"preauthorizeApiKey" not in initializer_js
          and not any(value.encode() in initializer_js for value in secrets),
          "shipped Swagger initializer does not preauthorize a secret")
    check(http("GET", "/v3/api-docs", operational=True)[0] != 200,
          "separate operational port does not publish the business OpenAPI")
    # Real pre-filter Tomcat parser rejection: application filters cannot sanitize this target.
    with socket.create_connection(("127.0.0.1", app_port), timeout=5) as connection:
        connection.sendall(b"GET /s/Ab12?parser-query-canary=< HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
        response = connection.recv(4096)
    check(response.startswith(b"HTTP/1.1 400"), "actual final Tomcat rejects malformed pre-filter request")
    parser_logs = cmd(compose + ["logs", "--no-color", "app"], "observation-parser-logs", 30)
    check("category=http" in parser_logs and "parser-query-canary" not in parser_logs
          and "Invalid character found in the request target" not in parser_logs,
          "actual final image parser logs preserve a safe category without private target")
    def health(group):
        return http("GET", "/actuator/health/" + group, operational=True)
    def probe_pair():
        return http("GET", "/livez")[2] == {"status": "UP"} and http("GET", "/readyz")[2] == {"status": "UP"}
    check(probe_pair(), "actual main HTTP liveness/readiness probes contain only UP status")
    check(health("liveness")[2] == {"status": "UP"} and health("readiness")[2] == {"status": "UP"}, "management core groups agree with actual main probes")
    for path in ("/actuator/health", "/actuator/metrics", "/actuator/info", "/actuator/health/dependencies"):
        check(http("GET", path)[0] == 404, "main port excludes " + path)
    for endpoint in ("env", "configprops", "heapdump", "loggers", "beans", "mappings", "shutdown", "threaddump"):
        check(http("GET", "/actuator/" + endpoint, operational=True)[0] == 404, "management excludes dangerous " + endpoint)
    check(http("GET", "/actuator", operational=True)[0] == 404, "management discovery is disabled")
    status, _, info = http("GET", "/actuator/info", operational=True)
    check(status == 200 and set(info) == {"build"} and set(info["build"]) == {"artifact", "group", "name", "version"}, "info exposes only safe build metadata without old management token")
    initial_vhost = urllib.parse.quote(values["RABBITMQ_VIRTUAL_HOST"], safe="")
    until(lambda: (reply := http("GET", "/api/queues/" + initial_vhost + "/shortlink.visit.stats.q", management=True))[0] == 200 and reply[2].get("consumers", 0) == 1, "actual queue/consumer startup")
    status, headers, created = http("POST", "/api/links", {"originalUrl": "https://example.com/compose-acceptance"})
    check(status == 201, "new account/schema supports actual create")
    code = created["shortCode"]
    status, headers, _ = http("GET", "/s/" + code)
    cookie = headers.get("Set-Cookie", "").split(";", 1)[0]
    check(status == 302 and headers["Location"] == "https://example.com/compose-acceptance" and headers["Cache-Control"] == "no-store" and bool(cookie), "redirect and collection cookie")
    status, head_headers, body = http("HEAD", "/s/" + code)
    check(status == 302 and body == b"" and head_headers.get("Location") == "https://example.com/compose-acceptance"
          and head_headers.get("Cache-Control") == "no-store" and "Set-Cookie" not in head_headers,
          "actual successful HEAD preserves redirect headers without cookie or body")
    status, error_headers, error = http("GET", "/s/invalid-code")
    check(status == 404 and error["code"] == "LINK_NOT_FOUND" and error_headers.get("Cache-Control") == "no-store"
          and "Set-Cookie" not in error_headers, "actual illegal short code rejects without a visit cookie")
    status, error_headers, body = http("HEAD", "/s/invalid-code")
    check(status == 404 and body == b"" and error_headers.get("Cache-Control") == "no-store"
          and "Set-Cookie" not in error_headers, "actual HEAD error has no response body or visit cookie")
    for suffix in ("stats", "visits"):
        status, error_headers, error = http("GET", "/api/internal/links/" + code + "/" + suffix)
        check(status == 401 and error["code"] == "INTERNAL_UNAUTHORIZED" and error_headers.get("Cache-Control") == "no-store",
              "actual unauthorized management contract " + suffix)
        status, error_headers, body = http("HEAD", "/api/internal/links/" + code + "/" + suffix)
        check(status == 401 and body == b"" and error_headers.get("Cache-Control") == "no-store",
              "actual unauthorized management HEAD has no body " + suffix)
    def stats():
        status, _, data = http("GET", "/api/internal/links/" + code + "/stats", headers={"X-Internal-Token": values["SHORT_LINK_INTERNAL_TOKEN"]})
        return data if status == 200 else None
    until(lambda: (data := stats()) and data["pv"] == 1 and data["uv"] == 1, "asynchronous statistics")
    check(True, "publisher route consumer records PV and UV")
    _, _, metric_names = http("GET", "/actuator/metrics", operational=True)
    check({"http.server.requests", "jdbc.connections.active", "jdbc.connections.max", "hikaricp.connections"}.issubset(set(metric_names["names"])), "existing HTTP and both pool metrics are available")
    _, _, http_metric = http("GET", "/actuator/metrics/http.server.requests", operational=True)
    check(all(code not in value for tag in http_metric["availableTags"] for value in tag["values"]), "HTTP metric labels do not include short-code identity")
    _, _, pool_metric = http("GET", "/actuator/metrics/jdbc.connections.max", operational=True)
    check(any(tag["tag"] == "name" and set(tag["values"]) == {"dataSource", "stats"} for tag in pool_metric["availableTags"]), "core and statistics pool metrics remain separately named")
    # Independently exercise the observation output through real business and management HTTP.
    def metric(name, **tags):
        query = urllib.parse.urlencode([("tag", key + ":" + value) for key, value in tags.items()])
        status, _, data = http("GET", "/actuator/metrics/" + name + ("?" + query if query else ""), operational=True)
        if status != 200:
            raise RuntimeError("Missing actual metric: " + name)
        return data
    def metric_value(name, **tags):
        data = metric(name, **tags)
        return next(item["value"] for item in data["measurements"] if item["statistic"] in ("COUNT", "VALUE"))
    check(metric_value("shortlink.mq.publish.outcomes", result="accepted") >= 1
          and metric_value("shortlink.statistics.write.outcomes", result="saved") >= 1,
          "actual broker-confirmed publishing and separately saved statistics are observed")
    check(metric_value("shortlink.mq.consumer.event.delay.samples") >= 1
          and metric_value("shortlink.mq.consumer.completed.average") > 0,
          "saved-event delay samples and cumulative consumer average have distinct metrics")
    canaries = ("query-secret-canary", "client-id-canary", "request-cookie-canary", "referer-canary", "agent-canary", "original-url-canary", "parser-query-canary")
    ids = set()
    for i in range(10):
        status, headers, _ = http("HEAD", "/s/" + code + "?query-secret-canary", headers={
            "X-Request-ID": "client-id-canary", "Cookie": "request-cookie-canary",
            "Referer": "https://referer-canary/private", "User-Agent": "agent-canary"})
        server_id = {key.lower(): value for key, value in headers.items()}.get("x-request-id", "")
        check(status == 302 and len(server_id) == 36 and server_id not in ids and server_id != "client-id-canary",
              "server-generated unique HEAD request identity " + str(i + 1))
        ids.add(server_id)
    # Actual wrong-type cache error: restore only this idle key, with no Redis reconstruction.
    cache_key = "shortlink:redirect:v2:" + code
    cmd(compose + ["exec", "-T", "redis", "redis-cli", "DEL", cache_key], "observe-cache-delete", 30)
    cmd(compose + ["exec", "-T", "redis", "redis-cli", "LPUSH", cache_key, "payload-canary"], "observe-cache-wrongtype", 30)
    check(http("HEAD", "/s/" + code)[0] == 302 and metric_value("shortlink.cache.read", result="failed") >= 1,
          "actual cache dependency error is counted while HEAD falls back safely")
    cmd(compose + ["exec", "-T", "redis", "redis-cli", "DEL", cache_key], "observe-cache-restore", 30)
    check(http("HEAD", "/s/" + code)[0] == 302, "same running application observes successful cache recovery")
    rejected = 0
    for _ in range(12):
        status, headers, data = http("POST", "/api/links?query-secret-canary", {"originalUrl": "https://original-url-canary.example/private"},
                                   headers={"X-Request-ID": "client-id-canary"})
        if status == 429:
            rejected += 1
            check(data["code"] == "RATE_LIMIT_EXCEEDED" and int(headers["Retry-After"]) >= 1,
                  "actual creation rejection contract " + str(rejected))
        elif status != 201:
            raise RuntimeError("Unexpected creation observation result")
    check(rejected > 0 and metric_value("shortlink.rate.admission", group="create", result="rejected") == rejected
          and metric_value("shortlink.http.requests", group="create", result="rejected") == rejected,
          "actual rejected requests agree with admission and HTTP outcome counters")
    for suffix in ("first", "second", "third"):
        check(http("GET", "/unknown-path-canary-" + suffix)[0] == 404, "unknown route observation " + suffix)
    _, _, names = http("GET", "/actuator/metrics", operational=True)
    observations = {name: metric(name) for name in names["names"] if name.startswith(("shortlink.", "http.server.requests"))}
    check(all(tag["tag"] in {"route", "group", "result", "category"} for data in observations.values() for tag in data["availableTags"])
          and all(code not in value and not any(canary in value for canary in canaries + ("unknown-path-canary", "payload-canary"))
                  for data in observations.values() for tag in data["availableTags"] for value in tag["values"]),
          "queried HTTP and custom metrics have finite safe labels without identity or arbitrary paths")
    (report / "observation-metrics.json").write_text(json.dumps(observations, indent=2), encoding="utf-8")
    app_logs = cmd(compose + ["logs", "--no-color", "app"], "observation-app-logs", 30)
    check(not any(value in app_logs for value in secrets + list(canaries) + ["payload-canary", "unknown-path-canary"]),
          "actual application output excludes request and dependency privacy canaries and credentials")
    check(sum("operation=create result=rejected" in line for line in app_logs.splitlines()) == 1
          and "operation=redirect result=redirect" not in app_logs,
          "creation rejection burst is bounded and successful redirects have no per-request INFO")
    check("Dependency recovery: category=cache-read" in app_logs and "category=cache-read" in app_logs
          and "operation=lifecycle result=started" in app_logs,
          "actual cache failure recovery and application startup produce safe operational logs")
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
    operations_port = port("app", 8081)
    until(lambda: http("GET", "/")[0] == 200, "paused app")
    until(lambda: (q := queue()) and q.get("consumers", -1) == 0, "consumer paused")
    check(http("GET", "/s/" + code, headers={"Cookie": cookie})[0] == 302, "mapping survives app recreation")
    until(lambda: (q := queue()) and q.get("messages_ready", 0) >= 1, "durable queued backlog")
    check(stats()["pv"] == 1, "paused consumer leaves visit queued")
    for _ in range(3):
        _, _, state = health("dependencies")
        actual = state["components"]["statistics"]["details"]
        check(actual["consumerIntent"] == "disabled" and actual["consumerActual"] == "stopped", "probe observes paused consumer intent/actual without resuming")
    check(queue().get("consumers", -1) == 0, "repeated dependency probes do not create consumers")
    cmd(compose + ["stop"], "ordinary-stop", 120)
    cmd(compose + ["up", "--detach", "--wait", "--wait-timeout", "240"], "ordinary-restart", 300)
    app_port = port("app", 8080)
    mq_port = port("rabbitmq", 15672)
    operations_port = port("app", 8081)
    until(lambda: http("GET", "/")[0] == 200, "restarted app")
    until(lambda: (q := queue()) and q.get("messages_ready", 0) >= 1, "persisted backlog")
    check(stats()["pv"] == 1 and stats()["uv"] == 1, "ordinary restart preserves recorded visits")
    check(True, "ordinary restart preserves RabbitMQ queued backlog")
    check(policies() == expected, "repeat bootstrap preserves policy and backlog")
    env["SHORT_LINK_STATS_CONSUMER_ENABLED"] = "true"
    cmd(compose + ["up", "--detach", "--force-recreate", "app"], "resume-consumer", 120)
    app_port = port("app", 8080)
    operations_port = port("app", 8081)
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
    versions["images"] = {service: {"reference": container["Config"]["Image"], "id": container["Image"]}
                          for service, container in by_service.items()}
    for service, argv in (("app", ["java", "-version"]), ("mysql", ["mysql", "--version"]),
                          ("redis", ["redis-server", "--version"]),
                          ("rabbitmq", ["rabbitmq-diagnostics", "-q", "server_version"])):
        versions[service] = cmd(compose + ["exec", "-T", service, *argv], "runtime-version-" + service, 30).strip()
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
    operations_port = port("app", 8081)
    until(lambda: http("GET", "/")[0] == 200, "recovery HTTP")
    check(http("GET", "/s/" + code, headers={"Cookie": cookie})[0] == 302, "mapping survives controlled Redis reconstruction")
    until(lambda: (data := stats()) and data["pv"] == 3 and data["uv"] == 1, "recovery statistics")
    check(True, "controlled recovery rebuilds cache and retains identity")
    # Core startup only waits on MySQL: actually start with Redis/MQ stopped.
    cmd(compose + ["stop", "redis", "rabbitmq"], "optional-dependencies-stop", 120)
    until(lambda: (state := health("dependencies"))[2]["components"]["mqDependency"]["status"] == "DOWN" and state[2]["components"]["redisDependency"]["status"] == "DOWN", "live optional dependency outage observation", timeout=40)
    check(probe_pair() and health("readiness")[0] == 200 and health("dependencies")[0] == 503,
          "live Redis/MQ outage is separate from healthy actual main core probes")
    denied_status, _, denied = http("POST", "/api/links", {"originalUrl": "https://original-url-canary.example/private"})
    check(denied_status == 503 and denied["code"] == "RATE_LIMIT_UNAVAILABLE"
          and metric_value("shortlink.rate.admission", group="create", result="unavailable") >= 1,
          "actual stopped Redis admission failure is observed independently of quota rejection")
    for suffix in ("stats", "visits"):
        status, unavailable_headers, unavailable_body = http("HEAD", "/api/internal/links/" + code + "/" + suffix,
                headers={"X-Internal-Token": values["SHORT_LINK_INTERNAL_TOKEN"]})
        check(status == 503 and unavailable_body == b"" and unavailable_headers.get("Cache-Control") == "no-store",
              "actual Redis outage management HEAD failure has no body " + suffix)
    check("shortCode" not in denied and "COORDINATION_UNCONFIRMED" not in json.dumps(denied),
          "actual business-before-start 503 never claims a committed mapping")
    check(http("HEAD", "/s/" + code)[0] == 302
          and metric_value("shortlink.rate.admission", group="redirect", result="unavailable") >= 1,
          "actual stopped Redis fail-open redirect retains an unavailable admission metric")
    fault_logs = cmd(compose + ["logs", "--no-color", "app"], "observation-live-fault-logs", 30)
    check("category=rate-create" in fault_logs and "category=rate-redirect" in fault_logs
          and not any(value in fault_logs for value in secrets + list(canaries)),
          "actual Redis outage uses safe fixed dependency categories")
    cmd(compose + ["up", "--detach", "--force-recreate", "app"], "core-only-start", 120)
    app_port = port("app", 8080)
    operations_port = port("app", 8081)
    until(lambda: http("GET", "/")[0] == 200, "core startup without Redis/MQ")
    check(http("GET", "/s/" + code, headers={"Cookie": cookie})[0] == 302,
          "core starts and redirects while Redis and MQ are stopped")
    check(probe_pair() and health("readiness")[0] == 200, "Redis/MQ downtime leaves liveness and core readiness UP")
    _, _, state = health("dependencies")
    check(state["components"]["redisDependency"]["status"] == "DOWN" and state["components"]["mqDependency"]["status"] == "DOWN", "optional dependency group actually reports stopped Redis/MQ")
    check(all(value not in json.dumps(state) for value in secrets) and all(marker not in json.dumps(state) for marker in ("jdbc:", "redis://", "amqp://", "exception", "SELECT")), "dependency fault health reveals fixed safe categories only")
    # All writers stopped, then clean nonpersistent Redis recovery again.
    cmd(compose + ["stop", "app"], "core-only-stop", 120)
    cmd(compose + ["up", "--detach", "--wait", "redis", "rabbitmq"], "dependencies-resume", 180)
    check(cmd(compose + ["exec", "-T", "redis", "redis-cli", "DBSIZE"], "resume-empty", 30).strip() == "0", "optional dependencies recovered with no old Redis writers")
    cmd(compose + ["up", "--detach", "app"], "all-services-resume", 120)
    app_port = port("app", 8080)
    operations_port = port("app", 8081)
    until(lambda: http("GET", "/")[0] == 200, "complete services resumed")
    # True MySQL outage must fail readiness while preserving self-only liveness.
    cmd(compose + ["stop", "mysql"], "core-database-stop", 120)
    until(lambda: http("GET", "/readyz")[0] == 503, "core database readiness failure")
    check(http("GET", "/livez")[0] == 200 and health("liveness")[0] == 200 and health("readiness")[0] == 503,
          "actual MySQL outage fails both core probes while self-only liveness stays UP")
    until(lambda: json.loads(cmd(compose + ["ps", "--all", "--format", "json", "app"], "unhealthy-state", 30).splitlines()[0]).get("Health") == "unhealthy", "container core health failure", timeout=100)
    check(True, "Compose JRE probe marks actual core database failure unhealthy")
    cmd(compose + ["up", "--detach", "--wait", "mysql"], "core-database-recover", 120)
    until(probe_pair, "core database readiness recovery")
    check(health("readiness")[0] == 200, "actual core database recovery restores readiness")
    cmd(compose + ["stop", "app"], "observation-lifecycle-stop", 60)
    lifecycle_logs = cmd(compose + ["logs", "--no-color", "app"], "observation-lifecycle-logs", 30)
    check("operation=lifecycle result=stopping" in lifecycle_logs and "operation=lifecycle result=started" in lifecycle_logs,
          "actual application start and terminal stop are observable")
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
    if image_built:
        try:
            cmd(["docker", "image", "rm", "link-smoke-app-" + run_id + ":acceptance"], "isolated-image-cleanup", 60)
        except Exception:
            failure = failure or "Isolated image cleanup failed: link-smoke-app-" + run_id
    secret_file.unlink(missing_ok=True)
    (report / "summary.json").write_text(json.dumps({"project": project, "environment": versions, "passed": checks, "failure": failure}, indent=2), encoding="utf-8")
    print("Safe reports: " + str(report), flush=True)
if failure:
    sys.exit(1)
