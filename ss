#!/usr/bin/env bash
# StudioSnap dev helper. Build, install and drive the app on the Googlebook over adb.
# Mirrors the other Book projects' helpers. Usage: ./ss <command>
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"

PKG=io.github.kuscher.studiosnap
SVC="$PKG/io.github.kuscher.studiosnap.service.SnapService"
APK=app/build/outputs/apk/debug/app-debug.apk
ENVF="$HOME/.config/vscodebook/android.env"
[ -f "$ENVF" ] && . "$ENVF"

serial() { cat "$HOME/.config/vscodebook/adb-serial" 2>/dev/null; }
connect() {
  local s; s="$(serial)"
  if [ -n "$s" ] && adb -s "$s" shell true >/dev/null 2>&1; then echo "$s"; return; fi
  vscodebook android connect >/dev/null 2>&1 || true
  serial
}
A() { adb -s "$(connect)" "$@"; }
dbg() { A shell "am broadcast --user 10 -n $PKG/.util.DebugReceiver -a io.github.kuscher.studiosnap.DEBUG --es c '$*'" >/dev/null; }

build() { ./gradlew :app:assembleDebug --console=plain "$@"; }

install() {
  local s; s="$(connect)"
  adb -s "$s" shell settings put global verifier_verify_adb_installs 0
  adb -s "$s" install -r -g "$APK" || { adb -s "$s" shell settings put global verifier_verify_adb_installs 1; exit 1; }
  adb -s "$s" shell settings put global verifier_verify_adb_installs 1
}

enable()  { A shell "settings --user 10 put secure enabled_accessibility_services $SVC && settings --user 10 put secure accessibility_enabled 1"; echo "service enabled"; }
disable() { A shell "settings --user 10 delete secure enabled_accessibility_services; settings --user 10 put secure accessibility_enabled 0"; echo "service disabled"; }
launch()  { A shell "am start --user 10 -n $PKG/.MainActivity" >/dev/null; }

case "${1:-}" in
  build)   shift; build "$@" ;;
  install) install ;;
  app)     build; install; enable; launch ;;                 # build, install, open the home screen
  run)     build; install; enable; dbg open; echo "bar opened" ;;  # build, install, open the bar
  enable)  enable ;;
  disable) disable ;;
  launch)  launch ;;
  debug)   shift; dbg "$@" ;;                                  # e.g. ./ss debug open window
  open)    shift; dbg "open ${1:-}" ;;
  close)   dbg close ;;
  shot)    # capture StudioSnap's own overlay only, pull to OUT (default /tmp), open path
    tag="${2:-overlay}"; out="${3:-/tmp/ss-$tag.png}"
    dbg "open ${4:-}"; sleep 0.6; dbg "shot $tag"; sleep 0.6
    A exec-out "run-as $PKG cat cache/shots/$tag.png" > "$out"; dbg close
    echo "$out ($(stat -c%s "$out") bytes)" ;;
  key)     # press the Screenshot key via a virtual keyboard (real hotkey path)
    j=/data/local/tmp/ss_key.json
    printf '%s\n' \
      '{"id":1,"command":"register","name":"SS Test Keyboard","vid":6353,"pid":45073,"bus":"usb","configuration":[{"type":100,"data":[1]},{"type":101,"data":[99,42,31,125,56,29]}]}' \
      '{"id":1,"command":"delay","duration":700}' \
      '{"id":1,"command":"inject","events":[1,99,1,0,0,0]}' \
      '{"id":1,"command":"delay","duration":60}' \
      '{"id":1,"command":"inject","events":[1,99,0,0,0,0]}' \
      '{"id":1,"command":"delay","duration":500}' | A shell "cat > $j"
    A shell "uinput - < $j; rm -f $j" ;;
  logs)    A shell "logcat -d -s StudioSnap:V" | sed -E 's/^[0-9-]+ //' | tail -"${2:-40}" ;;
  logcat)  A shell logcat -s StudioSnap:V ;;
  serial)  connect ;;
  *) echo "usage: ./ss {build|install|app|run|enable|disable|launch|open [src]|close|shot <tag> [out] [src]|key|debug <cmd>|logs [n]|logcat|serial}"; exit 1 ;;
esac
