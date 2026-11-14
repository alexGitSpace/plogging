#!/bin/bash
set -e
APP=${1:?usage: ./deploy.sh <app-folder>}
APP=${APP%/}
LOCAL="$(cd "$(dirname "$0")" && pwd)/$APP"
REMOTE=/home/arduino/ArduinoApps/$APP
ADB=/mnt/c/Users/gleb.siroki/platform-tools-latest-windows/platform-tools/adb.exe

[ -d "$LOCAL" ] || { echo "No such folder: $LOCAL"; exit 1; }

"$ADB" shell "arduino-app-cli app stop $REMOTE" || true
"$ADB" shell "rm -rf $REMOTE"
"$ADB" push "$(wslpath -w "$LOCAL")" "$REMOTE"
"$ADB" shell "arduino-app-cli app start $REMOTE"
"$ADB" shell "arduino-app-cli app logs $REMOTE --all"
