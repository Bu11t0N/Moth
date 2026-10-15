#!/usr/bin/env bash
# Installs a CA certificate as a *user* CA on the emulator, the same file Settings → Security → Install certificate writes
# (/data/misc/user/0/cacerts-added/<subject_hash_old>.0). The UI flow cannot be automated reliably, so this needs
# `adb root` (AOSP "default" system images; not possible on Google Play images or real devices).
set -euo pipefail
cert="$1"
hash="$(openssl x509 -in "$cert" -noout -subject_hash_old)"
adb root > /dev/null; sleep 2; adb wait-for-device
for i in $(seq 1 20); do [ "$(adb shell id -u | tr -d '\r')" = 0 ] && break; sleep 1; done
adb push "$cert" "/data/local/tmp/$hash.0" > /dev/null
adb shell "mkdir -p /data/misc/user/0/cacerts-added && cp /data/local/tmp/$hash.0 /data/misc/user/0/cacerts-added/$hash.0 && chown -R system:system /data/misc/user/0/cacerts-added && chmod 755 /data/misc/user/0/cacerts-added && chmod 644 /data/misc/user/0/cacerts-added/$hash.0 && restorecon -R /data/misc/user/0/cacerts-added; rm /data/local/tmp/$hash.0"
adb shell ls -l /data/misc/user/0/cacerts-added
