#!/bin/bash
set -e
APP=${1:?usage: [BOARD=arduino@<ip>] ./deploy.sh <app-folder>}
APP=${APP%/}
LOCAL="$(cd "$(dirname "$0")" && pwd)/$APP"
REMOTE=/home/arduino/ArduinoApps/$APP
ADB=/mnt/c/Users/gleb.siroki/platform-tools-latest-windows/platform-tools/adb.exe

[ -d "$LOCAL" ] || { echo "No such folder: $LOCAL"; exit 1; }

# BOARD=arduino@<ip> deploys over SSH (Wi-Fi), for when the USB port holds the camera hub.
# Everything goes through one ssh session, so the password is asked once.
if [ -n "$BOARD" ]; then
  tar -C "$(dirname "$LOCAL")" -cf - "$APP" | ssh "$BOARD" "
    arduino-app-cli app stop $REMOTE
    rm -rf $REMOTE &&
    tar -C $(dirname "$REMOTE") -xf - &&
    arduino-app-cli app start $REMOTE &&
    arduino-app-cli app logs $REMOTE --all"
  exit
fi

"$ADB" shell "arduino-app-cli app stop $REMOTE" || true
"$ADB" shell "rm -rf $REMOTE"
"$ADB" push "$(wslpath -w "$LOCAL")" "$REMOTE"
"$ADB" shell "arduino-app-cli app start $REMOTE"
"$ADB" shell "arduino-app-cli app logs $REMOTE --all"
