#!/usr/bin/env bash
# Official source build for Linux/CI. Requires Go, Android SDK+NDK, JDK 17.
# For a newer release, update the adapter and configuration before changing the tag.
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
source_path="$project_root/.core-build/sing-box"
mkdir -p "$project_root/.core-build"
if [ ! -d "$source_path/.git" ]; then
    git clone --branch v1.12.25 --depth 1 https://github.com/SagerNet/sing-box.git "$source_path"
fi
cd "$source_path"
test "$(git describe --tags --exact-match)" = v1.12.25
test "$(git rev-parse HEAD)" = 73bfb99ebce7923c485435e4faf8571b412065a9

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
p = Path('.moth-xray/transport/internet/system_listener.go')
s = p.read_text()
old = '''\t\treturn c.Control(func(fd uintptr) {
\t\t\tfor _, controller := range controllers {
\t\t\t\tif err := controller(network, address, c); err != nil {
\t\t\t\t\terrors.LogInfoInner(ctx, err, "failed to apply external controller")
\t\t\t\t}
\t\t\t}
'''
new = '''\t\tfor _, controller := range controllers {
\t\t\tif err := controller(network, address, c); err != nil { return err }
\t\t}
\t\treturn c.Control(func(fd uintptr) {
'''
assert old in s
p.write_text(s.replace(old, new))
PY
go mod edit -replace github.com/xtls/xray-core=./.moth-xray
go mod tidy
# Go dependency license notices (licenses/go-modules.txt); see scripts/go-licenses.sh.
bash "$project_root/scripts/go-licenses.sh" "$source_path" "$project_root/licenses/go-modules.txt"
if [ "${MOTH_PREPARE_ONLY:-}" = 1 ]; then exit 0; fi
go install github.com/sagernet/gomobile/cmd/gomobile
go install github.com/sagernet/gomobile/cmd/gobind
go run ./cmd/internal/build_libbox -target android
cp libbox.aar "$project_root/libbox.aar"
sha256sum "$project_root/libbox.aar"
