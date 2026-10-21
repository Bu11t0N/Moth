#!/usr/bin/env bash
# Prints the path of a Linux Xray binary of the release build-libbox.sh compiles into libbox (for config tests and the e2e server).
# Downloads the release binary (checked by sha256); if upstream removed that release, builds the same pinned commit with Go.
set -euo pipefail
version=v26.10.21
sha256=0eec3ed4163c273d8c8bf821f7d219e257921cf584aa3fb970715e458e631978
# The commit of that release (same as in build-libbox.sh), for the source fallback.
commit=1b1647cd9ddf0abc34b981d4611fc03bcac1ca74
project_root="$(cd "$(dirname "$0")/.." && pwd)"
work="$project_root/.core-build/xray-$version"
if [ ! -x "$work/xray" ]; then
    mkdir -p "$work"
    if curl -fsSL -o "$work/xray.zip" "https://github.com/XTLS/Xray-core/releases/download/$version/Xray-linux-64.zip" >&2; then
        echo "$sha256  $work/xray.zip" | sha256sum -c - >&2
        unzip -oq "$work/xray.zip" xray -d "$work"
    else
        echo "Xray $version release download failed; building commit $commit from source" >&2
        GOTOOLCHAIN=go1.27.3 GOBIN="$work" CGO_ENABLED=0 go install -trimpath "github.com/xtls/xray-core/main@$commit" >&2
        mv "$work/main" "$work/xray"
    fi
fi
echo "$work/xray"
