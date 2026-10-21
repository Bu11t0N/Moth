#!/usr/bin/env bash
# Every Xray config Moth generates (core unit-test fixtures, core/build/config-fixtures/xray-*.json) must pass `xray run -test`
# with the same Xray release that build-libbox.sh compiles into libbox. Run after :core:test.
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
xray="$(bash "$project_root/scripts/xray-bin.sh")"
shopt -s nullglob
files=("$project_root"/core/build/config-fixtures/xray-*.json)
[ ${#files[@]} -gt 0 ] || { echo "no Xray fixtures; run :core:test first" >&2; exit 1; }
for f in "${files[@]}"; do
    if ! out="$("$xray" run -test -c "$f" 2>&1)"; then echo "$out" >&2; echo "::error::$(basename "$f") fails xray run -test" >&2; exit 1; fi
    echo "$(basename "$f"): OK"
done
