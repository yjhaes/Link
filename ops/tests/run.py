#!/usr/bin/env python3
"""Run tests without a personal database/broker. Requires Python 3.10+, JDK 17."""
import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import urllib.request
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("suite", choices=("unit", "integration", "all"))
args = parser.parse_args()
run_id = uuid.uuid4().hex[:12]
report = ROOT / "target" / "regression" / run_id
report.mkdir(parents=True)
project = "link-tests-" + run_id
# Never inherit personal connection settings or secrets.
env = {k: v for k, v in os.environ.items() if not k.upper().startswith(("DB_", "MYSQL_TEST_", "REDIS_", "RABBIT", "SHORT_LINK_", "SPRING_")) and k.upper() not in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "MAVEN_OPTS")}
test_env = dict(MYSQL_TEST_USERNAME="linktest", MYSQL_TEST_PASSWORD=secrets.token_hex(24),
           MYSQL_ROOT_PASSWORD=secrets.token_hex(24), RABBITMQ_USERNAME="linktest",
           RABBITMQ_PASSWORD=secrets.token_hex(24), RABBITMQ_HOST="127.0.0.1",
           RABBITMQ_VIRTUAL_HOST="/")
secret_values = [test_env[k] for k in ("MYSQL_TEST_PASSWORD", "MYSQL_ROOT_PASSWORD", "RABBITMQ_PASSWORD")]

def safe(text):
    for value in secret_values:
        text = text.replace(value, "[REDACTED]")
    return text

def command(argv, name, *, check=True, timeout=900):
    try:
        result = subprocess.run(argv, cwd=ROOT, env=env, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, errors="replace", timeout=timeout)
    except OSError as failure:
        raise RuntimeError(f"{name}: required executable {argv[0]} unavailable") from failure
    (report / (name + ".log")).write_text(safe(result.stdout), encoding="utf-8")
    if check and result.returncode:
        raise RuntimeError(f"{name} failed (exit {result.returncode}); inspect {report / (name + '.log')}")
    return result

compose = ["docker", "compose", "--project-name", project, "--file", str(ROOT / "ops/tests/compose.yml")]
started = False

def api(method, suffix, data):
    body = json.dumps(data).encode()
    request = urllib.request.Request(env["RABBIT_MANAGEMENT_URL"] + "/api/" + suffix, body, method=method)
    credentials = (env["RABBITMQ_USERNAME"] + ":" + env["RABBITMQ_PASSWORD"]).encode()
    request.add_header("Authorization", "Basic " + base64.b64encode(credentials).decode())
    request.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(request, timeout=10) as response:
        if not 200 <= response.status < 300:
            raise RuntimeError("Rabbit initialization failed")

def facilities():
    global started
    env.update(test_env)
    command(["docker", "info", "--format", "{{.ServerVersion}}"], "docker-preflight", timeout=30)
    command(["docker", "compose", "version"], "compose-preflight", timeout=30)
    started = True  # Also clean partial startup failures.
    command(compose + ["up", "--detach", "--wait", "--wait-timeout", "240"], "facilities-up", timeout=300)
    command(compose + ["exec", "-T", "rabbitmq", "rabbitmqctl", "set_user_tags", "linktest", "administrator"], "rabbit-account")
    def port(service, internal):
        output = command(compose + ["port", service, str(internal)], f"port-{service}-{internal}").stdout.strip()
        return output.rsplit(":", 1)[1]
    mysql = port("mysql", 3306)
    env.update(MYSQL_TEST_URL=f"jdbc:mysql://127.0.0.1:{mysql}/short_link_test?serverTimezone=UTC",
               REDIS_HOST="127.0.0.1", REDIS_PORT=port("redis", 6379),
               RABBIT_TEST_PORT=port("rabbitmq", 5672),
               RABBIT_MANAGEMENT_URL="http://127.0.0.1:" + port("rabbitmq", 15672))
    env.update(DB_URL=env["MYSQL_TEST_URL"], DB_USERNAME=env["MYSQL_TEST_USERNAME"],
               DB_PASSWORD=env["MYSQL_TEST_PASSWORD"], RABBITMQ_PORT=env["RABBIT_TEST_PORT"])
    policies = json.loads((ROOT / "ops/visit-consumer-policies.json").read_text(encoding="utf-8-sig"))
    for vhost in ("/", "link-consumer-test", "link-lifecycle-test"):
        import urllib.parse
        encoded = urllib.parse.quote(vhost, safe="")
        api("PUT", "vhosts/" + encoded, {})
        api("PUT", "permissions/" + encoded + "/linktest", {"configure": ".*", "write": ".*", "read": ".*"})
        for policy in policies:
            definition = {k: v for k, v in policy.items() if k != "name"}
            api("PUT", "policies/" + encoded + "/" + policy["name"], definition)
    print("Isolated MySQL/Redis/RabbitMQ healthy; accounts, vhosts and policies initialized.", flush=True)

# Discover top-level test classes. Nested fixtures are not standalone tests.
classes = {}
for source in (ROOT / "src/test/java").rglob("*Test.java"):
    import re
    text = source.read_text(encoding="utf-8-sig")
    package = re.search(r"package\s+([\w.]+);", text).group(1)
    name = package + "." + source.stem
    external = "@SpringBootTest" in text or "@Testcontainers" in text or source.stem == "VisitPublisherBrokerTest"
    classes[name] = external

def run_tests(names, label, vhost="/"):
    if not names:
        raise RuntimeError("No tests selected for " + label)
    if label.startswith("integration"):
        env["SHORT_LINK_STATS_RABBIT_VIRTUAL_HOST"] = vhost
        env["RABBITMQ_VIRTUAL_HOST"] = vhost
    destination = report / label
    destination.mkdir()
    wrapper = str(ROOT / ("mvnw.cmd" if os.name == "nt" else "mvnw"))
    argv = ([wrapper] if os.name == "nt" else ["sh", wrapper])
    result = command(argv + ["--batch-mode", "-Dstyle.color=never", "-Dtest=" + ",".join(sorted(names)),
                            "-Dtest.reportsDirectory=" + str(destination), "test"], label, check=False)
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    reports = list(destination.glob("TEST-*.xml"))
    for xml in reports:
        content = xml.read_text(encoding="utf-8")
        xml.write_text(safe(content), encoding="utf-8")
        suite = ET.fromstring(safe(content))
        for key in totals:
            totals[key] += int(suite.attrib.get(key, 0))
    for path in destination.glob("*.txt"):
        path.write_text(safe(path.read_text(encoding="utf-8")), encoding="utf-8")
    discovered = {ET.parse(xml).getroot().attrib["name"] for xml in reports}
    summary = {"suite": label, "exit": result.returncode, **totals,
               "missingClasses": sorted(set(names) - discovered), "unexpectedClasses": sorted(discovered - set(names))}
    (destination / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary), flush=True)
    if result.returncode or discovered != set(names) or totals["tests"] == 0 or any(totals[k] for k in ("failures", "errors", "skipped")):
        raise RuntimeError(f"{label} failed or skipped required tests; inspect {destination}")

try:
    print(f"Reports: {report}\nCompose project: {project}", flush=True)
    if args.suite in ("unit", "all"):
        page_tests = sorted((ROOT / "src/test/js").glob("*.test.mjs"))
        if page_tests:
            import re
            result = command(["node", "--test", "--test-reporter=tap", *[str(path) for path in page_tests]], "node-page", check=False)
            counts = {key: int(re.search(r"^# " + field + r" (\d+)$", result.stdout, re.MULTILINE).group(1))
                      for key, field in (("tests", "tests"), ("failures", "fail"), ("skipped", "skipped"), ("errors", "cancelled"))}
            summary = {"suite": "node-page", "exit": result.returncode, **counts}
            (report / "node-page-summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
            print(json.dumps(summary), flush=True)
            if result.returncode or not counts["tests"] or any(counts[k] for k in ("failures", "errors", "skipped")):
                raise RuntimeError("Node page tests failed or skipped; inspect node-page.log")
        run_tests([n for n, external in classes.items() if not external], "unit")
    if args.suite in ("integration", "all"):
        facilities()
        consumer = [n for n in classes if n.endswith(".VisitConsumerIntegrationTest")]
        lifecycle = [n for n in classes if n.rsplit(".", 1)[1] in ("VisitBacklogIntegrationTest", "VisitCollectionLifecycleTest", "VisitPausedRecoveryTest")]
        main = [n for n, external in classes.items() if external and n not in consumer + lifecycle]
        failures = []
        for names, label, vhost in ((main, "integration-main", "/"), (consumer, "integration-consumer", "link-consumer-test"), (lifecycle, "integration-lifecycle", "link-lifecycle-test")):
            try:
                run_tests(names, label, vhost)
            except RuntimeError as failure:
                failures.append(str(failure))
        if failures:
            raise RuntimeError("; ".join(failures))
except (Exception, KeyboardInterrupt) as failure:
    print(safe(str(failure)), file=sys.stderr, flush=True)
    sys.exit(1)
finally:
    if started:
        result = command(compose + ["down", "--volumes", "--remove-orphans"], "facilities-down", check=False, timeout=120)
        if result.returncode:
            print(f"Cleanup failed; retry docker compose --project-name {project} --file ops/tests/compose.yml down --volumes", file=sys.stderr)
            sys.exit(1)