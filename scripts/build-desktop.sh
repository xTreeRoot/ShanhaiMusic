#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
for arg in "$@"; do
    case "$arg" in
        -o|--o|-o=*|--o=*) printf '输出目录由 MB 构建入口管理。\n' >&2; exit 2 ;;
    esac
done
exec python3 scripts/artifacts.py run desktop -- bash -seu -o pipefail -- "$@" <<'WORKER'
go tool mygo build -o "$MB_TASK_TMP/desktop" "$@"
mv "$MB_TASK_TMP/desktop" "$MB_EVIDENCE/output"
WORKER
