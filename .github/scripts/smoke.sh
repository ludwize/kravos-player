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
tap_text "Lists"
adb exec-out screencap -p > "$OUT/03-playlists.png"
tap_text "Search"
adb exec-out screencap -p > "$OUT/04-search.png"
tap_text "Library"
tap_text "Connect Google Drive"
adb exec-out screencap -p > "$OUT/05-connect.png"
tap_text "Connect"
sleep 4
adb exec-out screencap -p > "$OUT/06-auth.png"

# Radio: directory loads, a Serbian station plays, Now Playing shows LIVE + ICY title.
tap_text "Radio"
sleep 10
adb exec-out screencap -p > "$OUT/07-radio.png"
tap_text "Play station"
sleep 20
adb exec-out screencap -p > "$OUT/08-radio-playing.png"
tap_text "Open player"
sleep 3
adb exec-out screencap -p > "$OUT/09-now-playing.png"
adb shell input keyevent KEYCODE_BACK
sleep 2
tap_text "World"
sleep 8
adb exec-out screencap -p > "$OUT/10-world.png"
adb shell dumpsys media_session > "$OUT/media_session.txt" 2>&1

# Folders tab + Drive browser entry
tap_text "Library"
sleep 2
SIZE=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}
for i in 1 2 3 4; do
  adb shell input swipe $((W * 9 / 10)) $((H / 2)) $((W / 10)) $((H / 2)) 250
  sleep 1
done
sleep 1
adb exec-out screencap -p > "$OUT/11-folders.png"
tap_text "Browse Google Drive"
sleep 2
adb exec-out screencap -p > "$OUT/12-drive-browser.png"

# Podcasts: charts load, a show opens, an episode plays, and leaving + returning keeps the place.
tap_text "Podcasts"
sleep 3
adb exec-out screencap -p > "$OUT/13-podcasts-home.png"
tap_text "Find podcasts"
sleep 10
adb exec-out screencap -p > "$OUT/14-podcast-charts.png"
tap_text "Open show"
sleep 12
adb exec-out screencap -p > "$OUT/15-podcast-show.png"
tap_text "Play episode"
sleep 20
adb exec-out screencap -p > "$OUT/16-podcast-playing.png"
tap_text "Open player"
sleep 3
adb exec-out screencap -p > "$OUT/17-podcast-nowplaying.png"
adb shell input keyevent KEYCODE_BACK
sleep 2
tap_text "Radio"
sleep 2
tap_text "Podcasts"
sleep 3
adb exec-out screencap -p > "$OUT/18-podcast-remembered.png"
adb shell dumpsys media_session > "$OUT/media_session_podcast.txt" 2>&1

# Start the playback service directly through a MediaController-less path: just check the process is alive.
PID=$(adb shell pidof $PKG | tr -d '\r')
echo "pid=$PID" > "$OUT/status.txt"
adb logcat -d > "$OUT/logcat.txt"
grep -n -A 40 "FATAL EXCEPTION" "$OUT/logcat.txt" > "$OUT/crash.txt" || true
grep -n -E "net.kravos|AndroidRuntime|Cast|MediaSession|Media3" "$OUT/logcat.txt" | tail -300 > "$OUT/app-log.txt" || true
if [ -s "$OUT/crash.txt" ]; then echo "CRASH" >> "$OUT/status.txt"; fi
cat "$OUT/status.txt" "$OUT/steps.txt"
exit 0
