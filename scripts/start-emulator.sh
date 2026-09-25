#!/bin/sh
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
case "${1:-host}" in
  host) TEST_AVD=codex-screenshare-host; TEST_PORT=5580 ;;
  viewer) TEST_AVD=codex-screenshare-viewer; TEST_PORT=5582 ;;
  *) echo "用法: $0 [host|viewer]" >&2; exit 2 ;;
esac
SDK_DIR="$PROJECT_DIR/.tools/android-sdk"
if [ ! -x "$SDK_DIR/emulator/emulator" ]; then
  echo "本机项目目录中尚未安装测试用 Android Emulator。请使用 Android Studio 的设备管理器，或先安装官方 SDK。" >&2
  exit 1
fi
export ANDROID_SDK_ROOT="$SDK_DIR"
export ANDROID_HOME="$SDK_DIR"
export ANDROID_AVD_HOME="$PROJECT_DIR/.tools/avds"
exec "$SDK_DIR/emulator/emulator" -avd "$TEST_AVD" -port "$TEST_PORT" -gpu swiftshader -no-snapshot
