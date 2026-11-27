#!/bin/bash
# 使用 Xcode 中已登录的个人团队签名，校验设备授权，导出 IPA 后安装到指定真机。
set -euo pipefail
if [[ $# -ne 2 ]]; then
    printf '用法：bash scripts/install-device.sh TEAM_ID DEVICE_UDID\n' >&2
    exit 2
fi
task_team="$1"
task_device="$2"
task_root="$(cd "$(dirname "$0")/.." && pwd)"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
cd "$task_root"
mkdir -p .build/logs .build/artifacts
if ! xcrun xcodebuild -project MBiOS.xcodeproj -scheme MBiOS -configuration Release \
    -destination "platform=iOS,id=$task_device" -destination-timeout 30 \
    -derivedDataPath .build/DeviceData -allowProvisioningUpdates \
    -allowProvisioningDeviceRegistration DEVELOPMENT_TEAM="$task_team" \
    CODE_SIGN_STYLE=Automatic CODE_SIGN_IDENTITY='Apple Development' \
    build > .build/logs/device-build.log 2>&1; then
    printf '签名构建失败，请查看 %s/.build/logs/device-build.log\n' "$task_root" >&2
    exit 1
fi
task_app="$task_root/.build/DeviceData/Build/Products/Release-iphoneos/MB.app"
/usr/bin/codesign --verify --deep --strict "$task_app"
python3 - "$task_app" "$task_team" "$task_device" "$task_root/.build/artifacts" <<'PY'
import datetime, hashlib, pathlib, plistlib, subprocess, sys, zipfile
from zoneinfo import ZoneInfo
app, team, device, output = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3], pathlib.Path(sys.argv[4])
profile = plistlib.loads(subprocess.check_output([
    '/usr/bin/security', 'cms', '-D', '-i', str(app/'embedded.mobileprovision')
], stderr=subprocess.DEVNULL))
info = plistlib.loads((app/'Info.plist').read_bytes())
signed = plistlib.loads(subprocess.check_output([
    '/usr/bin/codesign', '-d', '--entitlements', ':-', str(app)
], stderr=subprocess.DEVNULL))
if team not in profile.get('TeamIdentifier', []):
    raise SystemExit('签名团队与请求不一致，停止交付')
if device not in profile.get('ProvisionedDevices', []):
    raise SystemExit('描述文件未授权该手机，停止交付')
if signed.get('com.apple.developer.team-identifier') != team:
    raise SystemExit('应用签名中的团队不一致，停止交付')
if signed.get('application-identifier') != profile['Entitlements'].get('application-identifier'):
    raise SystemExit('应用标识与描述文件不一致，停止交付')
expires = profile['ExpirationDate'].replace(tzinfo=datetime.timezone.utc)
if expires <= datetime.datetime.now(datetime.timezone.utc):
    raise SystemExit('描述文件已过期，停止交付')
if info['CFBundleSupportedPlatforms'] != ['iPhoneOS']:
    raise SystemExit('构建不是 iOS 真机应用，停止交付')
ipa = output/f"MB-iOS-{info['CFBundleShortVersionString']}-{info['CFBundleVersion']}-development.ipa"
with zipfile.ZipFile(ipa, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path in sorted([app, *app.rglob('*')]):
        relative = path.relative_to(app.parent)
        if any(part.startswith('.') or part == '__MACOSX' for part in relative.parts):
            continue
        if path.is_symlink():
            raise SystemExit(f'不支持包内符号链接：{relative}')
        archive.write(path, 'Payload/'+relative.as_posix())
with zipfile.ZipFile(ipa) as archive:
    if archive.testzip() is not None:
        raise SystemExit('IPA 压缩校验失败')
    if archive.read('Payload/MB.app/embedded.mobileprovision') != (app/'embedded.mobileprovision').read_bytes():
        raise SystemExit('IPA 中的描述文件不一致')
digest = hashlib.sha256(ipa.read_bytes()).hexdigest()
ipa.with_suffix('.ipa.sha256').write_text(f'{digest}  {ipa.name}\n')
print('已签名 IPA：', ipa)
print('签名到期时间：', expires.astimezone(ZoneInfo('Asia/Shanghai')).isoformat(timespec='seconds'))
print('仅可安装到描述文件已授权的设备；免费团队需在到期后重新签名。')
PY
xcrun devicectl device install app --device "$task_device" --timeout 60 \
    --json-output .build/logs/device-install.json "$task_app"
printf '已完成签名、导出和真机安装。请在手机上打开山海验证。\n'
