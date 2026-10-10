#!/usr/bin/env bash
# Official source build for Linux/CI. Requires Go, Android SDK+NDK, JDK 17.
# For a newer release, update the adapter and configuration before changing the tag.
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
source_path="$project_root/.core-build/sing-box"
mkdir -p "$project_root/.core-build"
if [ ! -d "$source_path/.git" ]; then
    git clone --branch v1.11.15 --depth 1 https://github.com/SagerNet/sing-box.git "$source_path"
fi
cd "$source_path"
test "$(git describe --tags --exact-match)" = v1.11.15
test "$(git rev-parse HEAD)" = bc35aca01704497c179da1a03e45ad8e32f1a51b
go install github.com/sagernet/gomobile/cmd/gomobile
go install github.com/sagernet/gomobile/cmd/gobind
go run ./cmd/internal/build_libbox -target android
cp libbox.aar "$project_root/libbox.aar"
sha256sum "$project_root/libbox.aar"
