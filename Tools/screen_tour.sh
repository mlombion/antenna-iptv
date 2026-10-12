#!/bin/zsh
# Dev only: runs the debug screen tour against the local mock server and saves real window screenshots.
# Needs Screen Recording permission for the app running this script, and Tools/mock_xtream.py running.
set -e
ROOT=${0:A:h:h}
OUT=${1:-$ROOT/.build/tour}
WINID=${WINID_TOOL:?set WINID_TOOL to a helper that prints the window id for an app name}
rm -rf "$OUT"; mkdir -p "$OUT"
pkill -x "Antenna IPTV" || true; sleep 1
open -n "$ROOT/.build/dd/Build/Products/Debug/Antenna IPTV.app" --args \
  -AntennaDevServer http://127.0.0.1:8765 -AntennaDevUser antenna-test -AntennaDevPass test1234 -AntennaDevTour "$OUT"
for i in $(seq 1 600); do
  for m in "$OUT"/*.pending(N); do
    screencapture -x -o -l"$($WINID "Antenna IPTV")" "${m%.pending}" && rm -f "$m"
  done
  [ -f "$OUT/done" ] && break
  sleep 0.2
done
ls "$OUT"
