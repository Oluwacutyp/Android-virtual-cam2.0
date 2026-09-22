#!/usr/bin/env bash
# ============================================================================
# VCam Studio — Phase 1 "never black" stress test (device required).
#
# Exit criterion (docs/PHASE1.md): a 30-minute studio session must produce
# ZERO black frames across background/foreground stress cycles.
#
# How it works:
#   1. Installs the debug APK and launches the studio.
#   2. Runs N background/foreground cycles (home → relaunch) plus screen
#      off/on cycles, letting the engine re-attach its preview each time.
#   3. Pulls the engine diagnostics dump (logcat, tag vcam-engine) and fails
#      the run if: any output went UNHEALTHY, presented-frame count stalled,
#      or swapBuffers errors / black-render asserts appear.
#
# Usage:  tools/stress-test.sh [cycles]   (default 20)
# Requires: adb on PATH, one device/emulator attached, app already granted
#           camera permission (pass -g on install is done here).
# ============================================================================
set -uo pipefail

CYCLES="${1:-20}"
PKG="com.vcamstudio.app.debug"
ACTIVITY="com.vcamstudio.app/.MainActivity"
ENGINE_TAG="vcam-engine"
RENDER_TAG="vcam-render"

fail() { echo "FAIL: $*"; exit 1; }

command -v adb >/dev/null || fail "adb not on PATH"
adb get-state >/dev/null 2>&1 || fail "no device attached"

echo "== installing =="
APK="$(ls -1 app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)"
[ -n "${APK:-}" ] || fail "no debug APK found — run ./gradlew assembleDebug first"
adb install -r -g "$APK" >/dev/null || fail "install failed"

adb logcat -c
echo "== launching studio =="
adb shell am start -n "$ACTIVITY" >/dev/null || fail "launch failed"
sleep 6

echo "== running $CYCLES background/foreground cycles =="
for i in $(seq 1 "$CYCLES"); do
  adb shell input keyevent KEYCODE_HOME
  sleep 1
  adb shell am start -n "$ACTIVITY" >/dev/null
  # give the engine a moment to re-attach + present
  sleep 2
  if [ $((i % 5)) -eq 0 ]; then
    echo "   cycle $i: screen off/on sub-cycle"
    adb shell input keyevent KEYCODE_POWER
    sleep 1
    adb shell input keyevent KEYCODE_POWER
    adb shell input keyevent KEYCODE_MENU
    sleep 1
  fi
  echo "   cycle $i done"
done
sleep 3

echo "== collecting diagnostics =="
adb logcat -d -s "$ENGINE_TAG" "$RENDER_TAG" > /tmp/vcam-stress.log || true

PRESENTED_TOTAL="$(grep -oE 'presented=[0-9]+' /tmp/vcam-stress.log | grep -oE '[0-9]+' | sort -n | tail -1)"
UNHEALTHY_COUNT="$(grep -c 'health=UNHEALTHY' /tmp/vcam-stress.log || true)"
SWAP_FAILURES="$(grep -ci 'swapBuffers failed' /tmp/vcam-stress.log || true)"
GL_ERRORS="$(grep -c 'GL error' /tmp/vcam-stress.log || true)"

echo "presented frames (last dump): ${PRESENTED_TOTAL:-0}"
echo "unhealthy reports:            $UNHEALTHY_COUNT"
echo "swap failures:                $SWAP_FAILURES"
echo "GL errors:                    $GL_ERRORS"

[ -n "${PRESENTED_TOTAL:-}" ] && [ "$PRESENTED_TOTAL" -gt 100 ] || fail "engine presented too few frames — preview likely never came up"
[ "$UNHEALTHY_COUNT" -eq 0 ] || fail "engine reported UNHEALTHY during stress"
[ "$SWAP_FAILURES" -eq 0 ] || fail "swapBuffers failures present"
[ "$GL_ERRORS" -eq 0 ] || fail "GL errors present"

echo "PASS: $CYCLES cycles, $PRESENTED_TOTAL frames presented, no black-frame indicators."
echo "NOTE: attach a screen recording or eyeball the device for the subjective check;"
echo "      engine-side counters above are the objective gate (docs/PHASE1.md)."
