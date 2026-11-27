#!/bin/bash
# 复用固定模拟器构建目录；仅在已启动的模拟器后台运行，不打开 Simulator.app。
set -euo pipefail
if [[ $# -ne 2 ]]; then
    echo '用法：bash scripts/verify-playlist-focus.sh IPHONE_SIMULATOR_UDID IPAD_SIMULATOR_UDID' >&2
    exit 2
fi
cd "$(dirname "$0")/.."
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
evidence=".build/logs/playlist-focus"
mkdir -p "$evidence"
for device in phone tablet; do
    if [ "$device" = phone ]; then
        destination="$1"
    else
        destination="$2"
    fi
    if [ -e "$evidence/$device.xcresult" ]; then
        echo "验收结果已存在，先按保留规则归档：$evidence/$device.xcresult" >&2
        exit 1
    fi
    xcrun xcodebuild -project MBiOS.xcodeproj -scheme MBiOS -configuration Debug \
        -destination "platform=iOS Simulator,id=$destination" -derivedDataPath .build/SimulatorData \
        -parallel-testing-enabled NO -resultBundlePath "$evidence/$device.xcresult" \
        -only-testing:MBiOSUITests/MBiOSUITests/testLocalPlaylistRevealsPausedCurrentTrack \
        -only-testing:MBiOSUITests/MBiOSUITests/testPluginPlaylistRevealsPausedCurrentTrack \
        CODE_SIGNING_ALLOWED=NO test > "$evidence/$device.log" 2>&1
done
