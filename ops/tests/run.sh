#!/bin/sh
set -eu
exec python3 "$(dirname "$0")/run.py" "${1:-unit}"