#!/bin/bash
# 编译未签名的 iPhone Release 包，供 AltStore 使用用户自己的证书重新签名。
set -euo pipefail
task_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$task_root"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
task_artifacts="$task_root/.build/artifacts"
mkdir -p "$task_artifacts" "$task_root/.build/logs"
xcrun xcodebuild -project MBiOS.xcodeproj -scheme MBiOS -configuration Release \
    -destination 'generic/platform=iOS' -derivedDataPath .build/DerivedData \
    CODE_SIGNING_ALLOWED=NO build > .build/logs/ios-release-build.log 2>&1
task_app="$task_root/.build/DerivedData/Build/Products/Release-iphoneos/MB.app"
task_version="$(/usr/libexec/PlistBuddy -c 'Print CFBundleShortVersionString' "$task_app/Info.plist")"
task_build="$(/usr/libexec/PlistBuddy -c 'Print CFBundleVersion' "$task_app/Info.plist")"
task_ipa="$task_artifacts/MB-iOS-$task_version-$task_build-unsigned.ipa"
python3 - "$task_app" "$task_ipa" <<'PY'
import pathlib, plistlib, stat, sys, zipfile
app = pathlib.Path(sys.argv[1])
with zipfile.ZipFile(sys.argv[2], 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path in sorted([app, *app.rglob('*')]):
        relative = path.relative_to(app.parent)
        if any(part.startswith('.') or part == '__MACOSX' for part in relative.parts):
            continue
        if path.is_symlink():
            raise SystemExit(f'不支持应用包内符号链接：{path}')
        archive.write(path, 'Payload/'+relative.as_posix())
with zipfile.ZipFile(sys.argv[2]) as archive:
    names = archive.namelist()
    # AltSign 按 .app 后缀选择 Payload 顶层条目，._MB.app 也会被当作候选；禁止打入这些元数据。
    assert not any(part.startswith('.') or part == '__MACOSX' for name in names for part in pathlib.PurePosixPath(name).parts)
    candidates = {name.rstrip('/') for name in names if len(pathlib.PurePosixPath(name).parts) == 2 and pathlib.PurePosixPath(name.rstrip('/')).suffix == '.app'}
    assert candidates == {'Payload/MB.app'}, candidates
    info = plistlib.loads(archive.read('Payload/MB.app/Info.plist'))
    executable = 'Payload/MB.app/'+info['CFBundleExecutable']
    assert archive.getinfo(executable).external_attr >> 16 & stat.S_IXUSR
    assert info['CFBundlePackageType'] == 'APPL'
    assert info['CFBundleIdentifier'] and (info.get('CFBundleDisplayName') or info.get('CFBundleName'))
    assert info['CFBundleSupportedPlatforms'] == ['iPhoneOS']
    assert archive.testzip() is None
print(f'IPA 结构校验通过：{pathlib.Path(sys.argv[2]).stat().st_size:,} 字节，仅含一个有效应用目录')
PY
/usr/bin/unzip -tq "$task_ipa"
python3 - "$task_root" "$task_artifacts/MB-iOS-$task_version-$task_build-sources.sha256" <<'PY'
import hashlib, pathlib, sys
root = pathlib.Path(sys.argv[1])
files = [root/'Package.swift', root/'Package.resolved']
for folder in ['Sources', 'MBiOS', 'MBiOS.xcodeproj', 'scripts']:
    files.extend(p for p in (root/folder).rglob('*') if p.is_file()
                 and not {'xcuserdata', '__pycache__'}.intersection(p.parts)
                 and not p.name.startswith('.') and p.suffix != '.pyc')
with open(sys.argv[2], 'w') as output:
    for file in sorted(files):
        output.write(hashlib.sha256(file.read_bytes()).hexdigest()+'  '+str(file.relative_to(root))+'\n')
PY
printf '未签名 IPA：%s\n' "$task_ipa"
