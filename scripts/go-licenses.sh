#!/usr/bin/env bash
# Writes license notices for every Go module compiled into libbox (sing-box + Xray-core + dependencies).
# Usage: go-licenses.sh <prepared sing-box source> <output file>
# Called by build-libbox.sh after the sources are prepared (go mod tidy). Modules are collected with
# `go list -deps` for all four Android ABIs with the build tags used by cmd/internal/build_libbox,
# plus the gomobile bind runtime. Licenses are identified with github.com/google/licensecheck (pinned in
# scripts/go-licenses/go.mod). Unidentified licenses are kept with a note and reported as CI warnings.
set -euo pipefail
# Same Go release as CI, so the generated file (including the standard library entry) matches.
export GOTOOLCHAIN=go1.24.3
src="$(cd "$1" && pwd)"
out="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
tool_dir="$(cd "$(dirname "$0")/go-licenses" && pwd)"
tags=with_gvisor,with_quic,with_wireguard,with_ech,with_utls,with_clash_api
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
# Go standard library and runtime are linked in too.
printf 'std (Go standard library)\t%s\t%s\n' "$(go env GOVERSION)" "$(go env GOROOT)" >> "$tmp"
for arch in arm64 arm amd64 386; do
    (cd "$src" && GOOS=android GOARCH=$arch CGO_ENABLED=1 go list -deps -tags "$tags" \
        -f '{{if not .Standard}}{{with .Module}}{{.Path}}{{"\t"}}{{if .Main}}v1.11.15{{else}}{{.Version}}{{end}}{{"\t"}}{{if .Replace}}{{.Replace.Dir}}{{else}}{{.Dir}}{{end}}{{end}}{{end}}' \
        ./experimental/libbox github.com/sagernet/gomobile/bind/java github.com/sagernet/gomobile/bind/seq) >> "$tmp"
done
(cd "$tool_dir" && go run . "$out") < "$tmp"
