#!/bin/sh
# E2E diagnostics: "enable" turns on the app's own (redacted) DEBUG log in the debug build; "pull" copies it to e2e-app-log.txt.
pkg=org.mothvpn.android
case "$1" in
  enable)
    adb shell "run-as $pkg sh -c 'mkdir -p shared_prefs && echo \"<?xml version=\\\"1.0\\\" encoding=\\\"utf-8\\\"?><map><string name=\\\"level\\\">DEBUG</string></map>\" > shared_prefs/logging.xml'"
    adb shell "run-as $pkg cat shared_prefs/logging.xml" ;;
  pull)
    { adb shell "run-as $pkg cat files/moth-log.1.txt" 2>/dev/null; adb shell "run-as $pkg cat files/moth-log.txt"; } > e2e-app-log.txt 2>&1 || true ;;
esac
