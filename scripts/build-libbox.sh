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

# XHTTP uses Xray v26.10.17 (92ccc939aaf4f8965f369cf0b142e005aeaa11d5).
cp "$project_root/scripts/native/moth_xray.go" experimental/libbox/moth_xray.go
# Module downloads are checked against sum.golang.org; retry transient network errors (checksums are never skipped).
retry() { for i in 1 2 3 4 5; do "$@" && return 0; echo "retry $i: $*" >&2; sleep $((i * 15)); done; return 1; }
retry go get github.com/xtls/xray-core@92ccc939aaf4f8965f369cf0b142e005aeaa11d5
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
# Since Xray v26.9 the dialer already fails closed upstream; only older sources need the patch.
if needle in s: p.write_text(s.replace(needle, 'return err'))
else: assert 'return errors.New("failed to apply external controller").Base(err)' in s and needle not in s
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
# Xray 26 needs github.com/quic-go/qpack v0.6 (apernet/quic-go http3); sing-box 1.12's sagernet/quic-go http3 was written
# for the v0.5 API. Give sagernet/quic-go its own copy of qpack v0.5.1 under a private module path (sources unchanged).
retry go mod download github.com/quic-go/qpack@v0.5.1 github.com/sagernet/quic-go@v0.52.0-sing-box-mod.3
rm -rf .moth-qpack .moth-sagernet-quic
cp -R "$(go mod download -json github.com/quic-go/qpack@v0.5.1 | python3 -c 'import json,sys; print(json.load(sys.stdin)["Dir"])')/." .moth-qpack/
cp -R "$(go mod download -json github.com/sagernet/quic-go@v0.52.0-sing-box-mod.3 | python3 -c 'import json,sys; print(json.load(sys.stdin)["Dir"])')/." .moth-sagernet-quic/
chmod -R u+w .moth-qpack .moth-sagernet-quic
python3 - <<'PYQ'
from pathlib import Path
old, new = 'github.com/quic-go/qpack', 'github.com/mothvpn/qpack-v051'
m = Path('.moth-qpack/go.mod'); m.write_text(m.read_text().replace('module ' + old, 'module ' + new, 1))
for f in list(Path('.moth-qpack').rglob('*.go')) + list(Path('.moth-sagernet-quic').rglob('*.go')):
    s = f.read_text()
    if '"' + old in s: f.write_text(s.replace('"' + old + '"', '"' + new + '"').replace('"' + old + '/', '"' + new + '/'))
q = Path('.moth-sagernet-quic/go.mod'); s = q.read_text()
assert old + ' v0.5.1' in s
q.write_text(s.replace(old + ' v0.5.1', new + ' v0.5.1') + '\nreplace ' + new + ' => ../.moth-qpack\n')
PYQ
go mod edit -replace github.com/sagernet/quic-go=./.moth-sagernet-quic -replace github.com/mothvpn/qpack-v051=./.moth-qpack -require github.com/mothvpn/qpack-v051@v0.5.1
# sing-tun: LinkEndpointFilter.Attach(nil) wrapped the nil dispatcher, so gVisor never stopped its tun readers on close.
# They stayed blocked in ppoll on the closed tun file and kept the VPN interface alive until a packet arrived
# (Android kept the VPN up for up to a minute after Disconnect). Pass nil through so Close stops and waits for them.
singtun_version="$(go list -m -f '{{.Version}}' github.com/sagernet/sing-tun)"
test "$singtun_version" = v0.7.13
retry go mod download github.com/sagernet/sing-tun@"$singtun_version"
rm -rf .moth-sing-tun
cp -R "$(go list -m -f '{{.Dir}}' github.com/sagernet/sing-tun)/." .moth-sing-tun/
chmod -R u+w .moth-sing-tun
python3 - <<'PYT'
from pathlib import Path
p = Path('.moth-sing-tun/stack_gvisor_filter.go')
s = p.read_text()
old = 'func (w *LinkEndpointFilter) Attach(dispatcher stack.NetworkDispatcher) {\n'
assert old in s
p.write_text(s.replace(old, old + '\tif dispatcher == nil {\n\t\tw.LinkEndpoint.Attach(nil)\n\t\treturn\n\t}\n', 1))
PYT
go mod edit -replace github.com/sagernet/sing-tun=./.moth-sing-tun
# Moth build tags: drop Tailscale (unused). http2legacy: with Go 1.27, golang.org/x/net/http2 otherwise wraps net/http's own
# HTTP/2 and drops the internals sing-box's v2rayhttp reset reaches with go:linkname (x86 links failed; arm would crash at runtime). Keep gVisor, QUIC, WireGuard, uTLS, conntrack and the Clash API
# (libbox always starts the Clash server for its platform log writer, so the VPN cannot start without it).
python3 - <<'PY2'
from pathlib import Path
p = Path('cmd/internal/build_libbox/main.go')
s = p.read_text()
old = 'memcTags = append(memcTags, "with_tailscale")'
assert old in s
s = s.replace(old, 'memcTags = append(memcTags, "http2legacy")')
assert 'with_tailscale' not in s.split('func init()')[1].split('\n}\n')[0]
p.write_text(s)
PY2
retry go mod tidy
# Go dependency license notices (licenses/go-modules.txt); see scripts/go-licenses.sh.
bash "$project_root/scripts/go-licenses.sh" "$source_path" "$project_root/licenses/go-modules.txt"
if [ "${MOTH_PREPARE_ONLY:-}" = 1 ]; then exit 0; fi
go install github.com/sagernet/gomobile/cmd/gomobile
go install github.com/sagernet/gomobile/cmd/gobind
go run ./cmd/internal/build_libbox -target android
cp libbox.aar "$project_root/libbox.aar"
sha256sum "$project_root/libbox.aar"
