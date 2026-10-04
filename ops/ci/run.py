#!/usr/bin/env python3
"""Correctness gate: portable tests and isolated regression."""
import argparse
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import uuid
from reports import collect

ROOT = Path(__file__).resolve().parents[2]
ENV = {key: value for key, value in os.environ.items() if not key.upper().startswith(
    ("DB_", "MYSQL_", "REDIS_", "RABBIT", "SHORT_LINK_", "SPRING_", "APP_", "SERVER_", "MANAGEMENT_", "COMPOSE_"))
    and key.upper() not in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "MAVEN_OPTS")}


def command(argv, *, timeout=30):
    try:
        return subprocess.run(argv, cwd=ROOT, env=ENV, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, errors="replace", timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        return None


def preflight():
    versions = {"python": platform.python_version(), "platform": platform.platform()}
    for name, argv in (("java", ["java", "-version"]), ("node", ["node", "--version"]),
                       ("docker", ["docker", "info", "--format", "{{.ServerVersion}}"]),
                       ("compose", ["docker", "compose", "version", "--short"])):
        result = command(argv)
        if result is None or result.returncode:
            label = "Docker daemon" if name == "docker" else name
            raise RuntimeError("required " + label + " unavailable")
        versions[name] = result.stdout.strip()
    if not re.search(r'version "17[.\"]', versions["java"]):
        raise RuntimeError("required JDK 17 unavailable")
    if sys.version_info < (3, 10):
        raise RuntimeError("required Python 3.10+ unavailable")
    result = command(["git", "rev-parse", "HEAD"])
    versions["sourceCommit"] = result.stdout.strip() if result and result.returncode == 0 else "unavailable"
    result = command(["git", "status", "--porcelain", "--untracked-files=normal"])
    versions["sourceDirty"] = bool(result.stdout.strip()) if result and result.returncode == 0 else None
    return versions


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--preflight", action="store_true", help="Check mandatory runtimes and Docker, without running tests")
    args = parser.parse_args()
    run_id = uuid.uuid4().hex[:12]
    destination = ROOT / "target" / "ci" / run_id
    destination.mkdir(parents=True)
    artifact = destination / "reports"
    stages = []
    versions = {}
    failure = None
    try:
        versions = preflight()
        if args.preflight:
            print("Required CI facilities available.")
        else:
            print("CI safe reports: " + str(destination), flush=True)
            for directory in ("ops/ci",):
                if not list((ROOT / directory).glob("test_*.py")):
                    raise RuntimeError("required portable tests missing: " + directory)
                result = command([sys.executable, "-m", "unittest", "discover", "-s", directory, "-p", "test_*.py"], timeout=120)
                text = result.stdout if result else "portable test executable unavailable"
                match = re.search(r"Ran (\d+) tests?", text)
                passed = bool(result and not result.returncode and match and int(match.group(1)) > 0 and "skipped=" not in text)
                stages.append({"suite": directory, "exit": result.returncode if result else 1,
                               "tests": int(match.group(1)) if match else 0,
                               "failures": len(re.findall(r"^FAIL:", text, re.MULTILINE)),
                               "errors": len(re.findall(r"^ERROR:", text, re.MULTILINE)),
                               "skipped": int(skip.group(1)) if (skip := re.search(r"skipped=(\d+)", text)) else 0, "passed": passed})
                artifact.mkdir(parents=True, exist_ok=True)
                (artifact / (directory.replace("/", "-") + ".log")).write_text(text, encoding="utf-8")
                if not passed:
                    raise RuntimeError("portable correctness tests failed or skipped: " + directory)
            for label, script, arguments in (("regression", "ops/tests/run.py", ["all"]),):
                parent = ROOT / "target" / label
                result = command([sys.executable, str(ROOT / script), *arguments], timeout=None)
                # Bind artifact selection to the child announcement, never a target-wide glob
                # or directory timestamp that could include another concurrent/local run.
                log = result.stdout if result else "stage executable unavailable or timed out"
                produced = set()
                for announced in re.findall(r"^(?:Reports|Safe reports): (.+)$", log, re.MULTILINE):
                    path = Path(announced.strip()).resolve()
                    if path.parent == parent.resolve() and re.fullmatch(r"[0-9a-f]{12}", path.name) and path.is_dir():
                        produced.add(path)
                produced = sorted(produced)
                artifact.mkdir(parents=True, exist_ok=True)
                (artifact / (label + ".log")).write_text(log, encoding="utf-8")
                for report in produced:
                    collect(report, artifact / label / report.name)
                passed = bool(result and result.returncode == 0 and produced)
                stages.append({"suite": label, "exit": result.returncode if result else 1,
                               "reports": [path.name for path in produced], "passed": passed})
                print(json.dumps(stages[-1]), flush=True)
                if not passed:
                    failure = failure or label + " failed; inspect safe reports"
    except (Exception, KeyboardInterrupt) as error:
        # Fixed messages only; do not echo dependency output/exception details into public console.
        failure = str(error) if isinstance(error, RuntimeError) else type(error).__name__
    summary = {"run": run_id, "environment": versions, "stages": stages, "failure": failure,
               "githubHostedExecution": os.environ.get("GITHUB_ACTIONS") == "true",
               "performanceGate": False}
    artifact.mkdir(parents=True, exist_ok=True)
    (artifact / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write("artifact=" + str(artifact) + "\n")
    print(json.dumps(summary), flush=True)
    return 1 if failure else 0


if __name__ == "__main__":
    sys.exit(main())
