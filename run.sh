#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
if [[ ! -x .venv/bin/ussync ]]; then
  echo 'Primero ejecuta ./setup.sh en la carpeta del proyecto.'
  exit 1
fi
exec .venv/bin/ussync ui "$@"
