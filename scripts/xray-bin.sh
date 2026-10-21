#!/usr/bin/env bash
# Prints the path of a Linux Xray binary of the release build-libbox.sh compiles into libbox (for config tests and the e2e server).
# Downloads the release binary (checked by sha256); if upstream removed that release, builds the same pinned commit with Go.
set -euo pipefail
version=v26.10.17
sha256=ecdd54eccd24c4c21a8f00968e7dbf7f5996da92383d2321170b560dc4a95158
# The commit of that release (same as in build-libbox.sh), for the source fallback.
commit=92ccc939aaf4f8965f369cf0b142e005aeaa11d5
project_root="$(cd "$(dirname "$0")/.." && pwd)"
work="$project_root/.core-build/xray-$version"
if [ ! -x "$work/xray" ]; then
    mkdir -p "$work"
    if curl -fsSL -o "$work/xray.zip" "https://github.com/XTLS/Xray-core/releases/download/$version/Xray-linux-64.zip" >&2; then
        echo "$sha256  $work/xray.zip" | sha256sum -c - >&2
        unzip -oq "$work/xray.zip" xray -d "$work"
    else
        echo "Xray $version release download failed; building commit $commit from source" >&2
        GOBIN="$work" CGO_ENABLED=0 go install -trimpath "github.com/xtls/xray-core/main@$commit" >&2
        mv "$work/main" "$work/xray"
    fi
fi
echo "$work/xray"
