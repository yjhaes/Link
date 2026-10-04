#!/usr/bin/env python3
"""Collect only known text reports, redacting credentials left by interrupted smoke.
CLI: python reports.py SOURCE DESTINATION. No images, env, inspect or expanded config.
"""
import json
from pathlib import Path
import re
import sys

# Explicit report producers; never copy a whole target directory into artifacts.
LOGS = {"build.log", "docker-version.log", "compose-version.log", "docker-preflight.log",
        "compose-preflight.log", "node-page.log", "unit.log", "integration-main.log",
        "integration-consumer.log", "integration-lifecycle.log", "facilities-up.log",
        "facilities-down.log", "isolated-cleanup.log", "isolated-image-cleanup.log", "safe-service-logs.log",
        "failure-service-logs.log", "observation-app-logs.log", "observation-live-fault-logs.log",
        "observation-lifecycle-logs.log", "observation-parser-logs.log", "resource-observation.log", "ci.log"}
JSONS = {"summary.json", "node-page-summary.json", "observation-metrics.json"}


def collect(source, destination):
    source = source.resolve()
    destination = destination.resolve()
    if destination == source or source in destination.parents:
        raise ValueError("artifact destination must be outside source")
    secrets = []
    for path in source.rglob(".env*"):
        if path.is_file() and not path.is_symlink():
            for line in path.read_text(encoding="utf-8-sig").splitlines():
                key, separator, value = line.partition("=")
                if separator and any(marker in key.upper() for marker in ("PASSWORD", "TOKEN", "HMAC_KEY")) and value:
                    secrets.append(value)
    count = 0
    for path in source.rglob("*"):
        relative = path.relative_to(source)
        if not path.is_file() or path.is_symlink() or any(part.startswith(".") for part in relative.parts):
            continue
        name = path.name
        allowed = name in LOGS or name in JSONS or re.fullmatch(r"TEST-[\w.$-]+\.xml", name)
        if not allowed:
            continue
        text = path.read_text(encoding="utf-8-sig", errors="replace")
        for value in secrets:
            text = text.replace(value, "[REDACTED]")
        output = destination / relative
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(text, encoding="utf-8")
        count += 1
    return count


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: reports.py SOURCE DESTINATION")
    print(json.dumps({"copied": collect(Path(sys.argv[1]), Path(sys.argv[2]))}))
