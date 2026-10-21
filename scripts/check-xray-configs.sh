#!/usr/bin/env bash
# Every Xray config Moth generates (core unit-test fixtures, core/build/config-fixtures/xray-*.json) must pass `xray run -test`
# with the same Xray release that build-libbox.sh compiles into libbox. Run after :core:test.
set -euo pipefail
version=v26.10.17
sha256=ecdd54eccd24c4c21a8f00968e7dbf7f5996da92383d2321170b560dc4a95158
# The commit of that release (same as in build-libbox.sh), for the source fallback.
commit=92ccc939aaf4f8965f369cf0b142e005aeaa11d5
project_root="$(cd "$(dirname "$0")/.." && pwd)"
work="$project_root/.core-build/xray-$version"
if [ ! -x "$work/xray" ]; then
    mkdir -p "$work"
    if curl -fsSL -o "$work/xray.zip" "https://github.com/XTLS/Xray-core/releases/download/$version/Xray-linux-64.zip"; then
        echo "$sha256  $work/xray.zip" | sha256sum -c -
        unzip -oq "$work/xray.zip" xray -d "$work"
    else
        # The release binary is gone (upstream removed the release): build the same pinned commit build-libbox.sh uses.
        echo "Xray $version release download failed; building commit $commit from source" >&2
        GOBIN="$work" CGO_ENABLED=0 go install -trimpath "github.com/xtls/xray-core/main@$commit"
        mv "$work/main" "$work/xray"
    fi
fi
shopt -s nullglob
files=("$project_root"/core/build/config-fixtures/xray-*.json)
[ ${#files[@]} -gt 0 ] || { echo "no Xray fixtures; run :core:test first" >&2; exit 1; }
for f in "${files[@]}"; do
    if ! out="$("$work/xray" run -test -c "$f" 2>&1)"; then echo "$out" >&2; echo "::error::$(basename "$f") fails xray run -test" >&2; exit 1; fi
    echo "$(basename "$f"): OK"
done
