#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$project_dir"
exec "$project_dir/.local-stack/venv/bin/python" "$project_dir/scripts/local.py" "${1:-start}"
