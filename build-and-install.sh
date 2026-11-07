#!/usr/bin/env bash
# Builds Eclipse and installs it on an Android device plugged in over USB.
#
#   ./build-and-install.sh           build, then install on the device on the USB cable and open Eclipse
#   ./build-and-install.sh --build   build only (the APK ends up in app/build/outputs/apk/debug/app-debug.apk)
#
# Needs a JDK 17+ and the Android SDK. On Ubuntu / Debian both are set up the first time if they're missing (the SDK
# goes in ~/Android/Sdk, or wherever ANDROID_HOME points). Elsewhere, install them yourself and set ANDROID_HOME.
#
# On the device, once: Settings -> About -> tap "Build number" 7 times, then Developer options -> USB debugging (on).
# The first time you plug in, the device asks "Allow USB debugging?": tick "Always allow" and press OK.
set -uo pipefail

APP_ID="com.eclipse.launcher"
BUILD_ONLY=0
[ "${1:-}" = "--build" ] && BUILD_ONLY=1

say()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31m%s\033[0m\n' "$*"; exit 1; }

cd "$(dirname "$0")" || exit 1
[ -f gradlew ] || fail "Run this from the Eclipse project folder (where gradlew is)."

# ---------- 1. JDK 17+, unzip, curl, adb ----------
case "$(uname -s)" in Darwin) OS=mac ;; *) OS=linux ;; esac
need=()
command -v javac >/dev/null && javac -version 2>&1 | grep -qE 'javac (1[7-9]|[2-9][0-9])' || need+=(openjdk-17-jdk)
command -v unzip >/dev/null || need+=(unzip)
command -v curl  >/dev/null || need+=(curl)
if [ ${#need[@]} -gt 0 ]; then
  if command -v apt-get >/dev/null; then
    say "Installing ${need[*]} (asks for your password)"
    sudo apt-get update && sudo apt-get install -y "${need[@]}" || fail "apt-get install failed."
  else
    fail "Please install: ${need[*]} (a JDK 17 or newer, unzip and curl), then run this again."
  fi
fi
# prefer a JDK 17+ even when an older Java is the default
for d in /usr/lib/jvm/java-17-openjdk-* /usr/lib/jvm/java-21-openjdk-*; do
  [ -x "$d/bin/javac" ] && { export JAVA_HOME="$d"; export PATH="$d/bin:$PATH"; break; }
done

# ---------- 2. Android SDK ----------
if [ -n "${ANDROID_HOME:-}" ]; then SDK="$ANDROID_HOME"
elif [ -n "${ANDROID_SDK_ROOT:-}" ]; then SDK="$ANDROID_SDK_ROOT"
elif [ "$OS" = mac ]; then SDK="$HOME/Library/Android/sdk"
else SDK="$HOME/Android/Sdk"; fi
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ] && [ ! -d "$SDK/platforms/android-35" ]; then
  say "Installing the Android command-line tools into $SDK"
  zip="commandlinetools-$OS-11076708_latest.zip"
  tmp="$(mktemp -d)"
  curl -fL -o "$tmp/$zip" "https://dl.google.com/android/repository/$zip" || fail "Download failed."
  unzip -q "$tmp/$zip" -d "$tmp" && mkdir -p "$SDK/cmdline-tools" && rm -rf "$SDK/cmdline-tools/latest" \
    && mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest" || fail "Couldn't unpack the command-line tools."
  rm -rf "$tmp"
fi
export ANDROID_HOME="$SDK"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$PATH"
if [ ! -d "$SDK/platforms/android-35" ] || [ ! -d "$SDK/build-tools/35.0.0" ] || [ ! -x "$SDK/platform-tools/adb" ]; then
  say "Installing Android SDK packages (first time only)"
  yes | sdkmanager --licenses >/dev/null 2>&1 || true
  sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" || fail "sdkmanager failed."
fi
echo "sdk.dir=$SDK" > local.properties

# ---------- 3. build ----------
say "Building (the first build downloads Gradle and the libraries; later ones are quick)"
chmod +x gradlew
LOG="build.log"
./gradlew assembleDebug --console=plain 2>&1 | tee "$LOG"
status=${PIPESTATUS[0]}
# the system stopped the build for using too much memory: once more, in one leaner process
if [ "$status" -ne 0 ] && grep -q "daemon disappeared unexpectedly" "$LOG"; then
  say "The build ran out of memory. Trying again with less (slower)"
  ./gradlew --stop >/dev/null 2>&1
  ./gradlew assembleDebug --console=plain --no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx2g -XX:MaxMetaspaceSize=640m" 2>&1 | tee "$LOG"
  status=${PIPESTATUS[0]}
fi
if [ "$status" -ne 0 ]; then
  printf '\n\033[1;31mBuild failed.\033[0m The errors:\n\n'
  grep -E '^e: |error:|What went wrong' -A2 "$LOG" | head -60
  printf '\nThe whole log is in %s\n' "$LOG"
  exit 1
fi
APK="app/build/outputs/apk/debug/app-debug.apk"
say "Built $APK"
[ "$BUILD_ONLY" = 1 ] && exit 0

# ---------- 4. the device on the USB cable ----------
adb start-server >/dev/null 2>&1
usb_state()  { adb devices -l 2>/dev/null | awk 'NR>1 && $0 ~ /usb:/ {print $2; exit}'; }
usb_serial() { adb devices -l 2>/dev/null | awk 'NR>1 && $2=="device" && $0 ~ /usb:/ {print $1; exit}'; }
if [ "$(usb_state)" != "device" ]; then
  say "Waiting for the device on USB (plug it in, USB debugging on). Ctrl+C to stop."
  fixed=0; told_auth=0; told_off=0
  for _ in $(seq 1 90); do
    case "$(usb_state)" in
      device) break ;;
      unauthorized)
        [ "$told_auth" = 1 ] || { echo "  On the device: tap \"Allow\" on the USB debugging prompt (tick \"Always allow\")."; told_auth=1; } ;;
      "no permissions"|no)
        # Linux: let adb open Android devices (a udev rule, once)
        if [ "$fixed" = 0 ] && [ "$OS" = linux ]; then
          echo "  Linux won't let adb open the device. Adding a udev rule for it (asks for your password)..."
          echo 'SUBSYSTEM=="usb", ENV{DEVTYPE}=="usb_device", ENV{ID_USB_INTERFACES}=="*:ff4201:*", MODE="0666", TAG+="uaccess"' \
            | sudo tee /etc/udev/rules.d/51-android.rules >/dev/null
          sudo udevadm control --reload-rules && sudo udevadm trigger
          adb kill-server >/dev/null 2>&1; adb start-server >/dev/null 2>&1
          echo "  Done. If it still says no permissions, unplug the cable and plug it back in."
          fixed=1
        fi ;;
      offline) [ "$told_off" = 1 ] || { echo "  The device shows as offline: unplug and replug the cable."; told_off=1; } ;;
    esac
    sleep 2
  done
fi
SERIAL="$(usb_serial)"
[ -n "$SERIAL" ] || fail "No device on USB. Check the cable and USB debugging, then run: ./build-and-install.sh"

# ---------- 5. install and open ----------
say "Installing on $(adb -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
if ! adb -s "$SERIAL" install -r "$APK"; then
  echo "Install failed. If a copy signed with another key is installed, remove it first (its settings go with it):"
  echo "  adb -s $SERIAL uninstall $APP_ID && ./build-and-install.sh"
  exit 1
fi
adb -s "$SERIAL" shell am start -n "$APP_ID/.MainActivity" >/dev/null 2>&1
say "Installed. Eclipse is open on the device."
