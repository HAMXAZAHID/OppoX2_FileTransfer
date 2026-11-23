#!/usr/bin/env bash
# End-to-end check on a plain Android 11 emulator with no Google services (like ColorOS Watch):
# install the release APK, import a server over adb, browse the Samba share on the host,
# download a file to the "watch", then upload it back.
set -euo pipefail
PKG=dev.hamza.watchfiles
APK=out/HomeFiles-release.apk
SHOTS=shots
mkdir -p "$SHOTS"
step=0

shot() { step=$((step+1)); adb exec-out screencap -p > "$SHOTS/$(printf %02d $step)-$1.png" || true; }

dump() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/ui.xml 2>/dev/null > ui.xml || true
}

# Prints "x y" of the first node whose text contains $1.
find_text() {
  python3 - "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
try:
    root = ET.parse("ui.xml").getroot()
except Exception:
    sys.exit(0)
for n in root.iter("node"):
    if sys.argv[1] in (n.get("text") or "") or sys.argv[1] in (n.get("content-desc") or ""):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
}

wait_text() {
  for _ in $(seq 1 ${2:-30}); do
    dump
    if [ -n "$(find_text "$1")" ]; then return 0; fi
    sleep 1
  done
  echo "Timed out waiting for text: $1" >&2
  shot "missing-$(echo "$1" | tr -c 'a-zA-Z0-9' '_')"
  cat ui.xml >&2 || true
  return 1
}

tap_text() {
  wait_text "$1" "${2:-30}"
  adb shell input tap $(find_text "$1")
}

echo "== Device"
adb shell getprop ro.build.version.release
adb shell pm list packages | grep -i -E "gms|wearable" || echo "No Google services or Wear OS on this device (good)"

echo "== Test data on the share"
echo "hello from the home network" | sudo tee /srv/smbtest/hello.txt >/dev/null
sudo mkdir -p /srv/smbtest/Music && sudo chown -R smbuser /srv/smbtest

echo "== Install"
adb install -r "$APK"

echo "== Add a server over adb (10.0.2.2 is the host running Samba)"
adb shell am start -W -n $PKG/.MainActivity
wait_text "Add server"
shot first-launch
adb shell am start -W -n $PKG/.ImportActivity \
  --es host 10.0.2.2 --ei port 4455 --es share test --es user smbuser --es pass testpass --es name "CI\ Samba"
wait_text "CI Samba"
shot home
tap_text "CI Samba"

echo "== Browse"
wait_text "hello.txt"
wait_text "Music"
shot browse

echo "== Download"
tap_text "hello.txt"
tap_text "Download to watch"
wait_text "Saved to watch" 60
shot downloaded
got=$(adb shell cat /sdcard/Android/data/$PKG/files/Transfers/hello.txt | tr -d '\r')
[ "$got" = "hello from the home network" ] || { echo "Downloaded content wrong: '$got'" >&2; exit 1; }
tap_text "Done"

echo "== Upload into Music"
tap_text "Music"
wait_text "Upload here"
tap_text "Upload here"
tap_text "hello.txt"
wait_text "Uploaded" 60
shot uploaded
sudo cmp /srv/smbtest/hello.txt /srv/smbtest/Music/hello.txt
echo "Upload matches."

echo "== App still running"
adb shell pidof $PKG
echo "Smoke test passed."
