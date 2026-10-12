#!/usr/bin/env bash
# Installs the APK on the emulator, launches it, walks the main tabs and records screenshots + crashes.
set -u
OUT=smoke
mkdir -p "$OUT"
APK=$(ls out/*.apk | head -1)
PKG=net.kravos.player

adb install -r "$APK" > "$OUT/install.txt" 2>&1
adb logcat -c
adb shell am start -W -n $PKG/.ui.MainActivity > "$OUT/start.txt" 2>&1
sleep 12
# Notification permission dialog (API 33+): allow it if shown.
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/ui0.xml" >/dev/null 2>&1

tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  python3 - "$1" "$OUT/ui.xml" <<'EOF' > "$OUT/tap.txt"
import re, sys, xml.etree.ElementTree as ET
want, path = sys.argv[1], sys.argv[2]
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(0)
for n in root.iter('node'):
    if n.get('text') == want or n.get('content-desc') == want:
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
EOF
  if [ -s "$OUT/tap.txt" ]; then
    adb shell input tap $(cat "$OUT/tap.txt")
    echo "tapped $1" >> "$OUT/steps.txt"
  else
    echo "not found: $1" >> "$OUT/steps.txt"
  fi
  sleep 3
}

tap_text "Allow"
adb exec-out screencap -p > "$OUT/01-library.png"
tap_text "Settings"
adb exec-out screencap -p > "$OUT/02-settings.png"
tap_text "Playlists"
adb exec-out screencap -p > "$OUT/03-playlists.png"
tap_text "Search"
adb exec-out screencap -p > "$OUT/04-search.png"
tap_text "Library"
tap_text "Connect Google Drive"
adb exec-out screencap -p > "$OUT/05-connect.png"
tap_text "Connect"
sleep 4
adb exec-out screencap -p > "$OUT/06-auth.png"

# Start the playback service directly through a MediaController-less path: just check the process is alive.
PID=$(adb shell pidof $PKG | tr -d '\r')
echo "pid=$PID" > "$OUT/status.txt"
adb logcat -d > "$OUT/logcat.txt"
grep -n -A 40 "FATAL EXCEPTION" "$OUT/logcat.txt" > "$OUT/crash.txt" || true
grep -n -E "net.kravos|AndroidRuntime|Cast|MediaSession|Media3" "$OUT/logcat.txt" | tail -300 > "$OUT/app-log.txt" || true
if [ -s "$OUT/crash.txt" ]; then echo "CRASH" >> "$OUT/status.txt"; fi
cat "$OUT/status.txt" "$OUT/steps.txt"
exit 0
