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

# XHTTP uses Xray v25.3.6 (2cba2c4d59e48a04aa5d09b8e4c2b2d26f8608a4).
cp "$project_root/scripts/native/moth_xray.go" experimental/libbox/moth_xray.go
go get github.com/xtls/xray-core@2cba2c4d59e48a04aa5d09b8e4c2b2d26f8608a4
xray_path="$(go list -m -f '{{.Dir}}' github.com/xtls/xray-core)"
mkdir -p .moth-xray
cp -R "$xray_path/." .moth-xray/
chmod -R u+w .moth-xray
# Upstream logs and ignores controller failures. Android protection must fail closed.
python3 - <<'PY'
from pathlib import Path
p = Path('.moth-xray/transport/internet/system_dialer.go')
s = p.read_text()
needle = 'errors.LogInfoInner(ctx, err, "failed to apply external controller")'
assert needle in s
p.write_text(s.replace(needle, 'return err'))
PY
go mod edit -replace github.com/xtls/xray-core=./.moth-xray
go mod tidy
go install github.com/sagernet/gomobile/cmd/gomobile
go install github.com/sagernet/gomobile/cmd/gobind
go run ./cmd/internal/build_libbox -target android
cp libbox.aar "$project_root/libbox.aar"
sha256sum "$project_root/libbox.aar"
