#!/usr/bin/env python3
"""从 xcresulttool 导出的附件目录生成有来源记录的静态截图图库。"""
import argparse
import hashlib
import html
import json
import os
from pathlib import Path
import re

parser = argparse.ArgumentParser()
parser.add_argument('attachments', nargs='+', type=Path)
parser.add_argument('--output', type=Path, default=Path('Review'))
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
images = args.output / 'images'
images.mkdir(exist_ok=True)
records = {}
for folder in args.attachments:
    for test in json.loads((folder / 'manifest.json').read_text()):
        for attachment in test['attachments']:
            filename = attachment['exportedFileName']
            if not filename.endswith('.png') or attachment.get('isAssociatedWithFailure'):
                continue
            name = re.sub(r'_\d+_[A-Fa-f0-9-]+\.png$', '', attachment['suggestedHumanReadableName'])
            records[(attachment['deviceName'], name)] = dict(
                name=name, device=attachment['deviceName'], test=test['testIdentifier'],
                captured=attachment['timestamp'], origin=folder.name,
                source=folder/filename,
            )
manifest = []
for index, record in enumerate(sorted(records.values(), key=lambda r: (r['device'], r['name'])), 1):
    filename = f'{index:03}.png'
    data = record.pop('source').read_bytes()
    (images/filename).write_bytes(data)
    manifest.append(dict(number=index, file='images/'+filename, sha256=hashlib.sha256(data).hexdigest(), **record))
(args.output/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n')
cards = []
for record in manifest:
    name = html.escape(record['name'])
    cards.append(f'<figure data-search="{name} {html.escape(record["device"])}"><a href="{record["file"]}"><img loading="lazy" src="{record["file"]}" alt="{name}"></a><figcaption><b>#{record["number"]:03} {name}</b><br>{html.escape(record["device"])}<br><small>{html.escape(record["test"])}</small></figcaption></figure>')
page = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>山海 iOS 页面核对</title><style>body{font:15px system-ui;margin:24px;background:#f3f4f6;color:#19202b}h1{font-size:24px}input{padding:12px;font:inherit;border:1px solid #bbb;border-radius:10px;width:min(90%,500px)}main{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:20px;margin-top:20px}figure{margin:0;background:white;border-radius:12px;padding:12px;overflow:hidden}img{width:100%;height:520px;object-fit:contain;background:#eee}figcaption{padding-top:10px;line-height:1.5;overflow-wrap:anywhere}small{color:#687080}figure[hidden]{display:none}</style><h1>山海 iOS · 页面、操作与状态</h1><p>点击图片查看原尺寸，按编号反馈细节。账号、评论、歌曲与插件均为隔离演示数据；静音演示及“不支持视频”截图不代表真实媒体播放验收。</p><input id="search" placeholder="筛选：播放器、设置、插件、iPad…"><p id="count"></p><main>'''+''.join(cards)+'''</main><script>const input=document.querySelector('input'),cards=[...document.querySelectorAll('figure')];input.oninput=()=>{cards.forEach(c=>c.hidden=!c.dataset.search.toLowerCase().includes(input.value.toLowerCase()));document.querySelector('#count').textContent=cards.filter(c=>!c.hidden).length+' 张截图'};input.oninput();</script></html>'''
(args.output/'index.html').write_text(page)
parity = os.path.relpath(Path(__file__).resolve().parents[1] / 'PARITY.md', args.output.resolve())
lines = ['# 页面截图索引', '', f'可打开 `index.html` 筛选和查看原图。所有账号/互动/歌曲/插件内容为隔离演示数据；联网探针与真机待验收项见 [PARITY.md]({parity})。', '', '| 编号 | 页面/状态 | 设备 |', '| --- | --- | --- |']
lines += [f'| {r["number"]:03} | [{r["name"]}]({r["file"]}) | {r["device"]} |' for r in manifest]
(args.output/'SCREENSHOTS.md').write_text('\n'.join(lines)+'\n')
print(f'导出 {len(manifest)} 张截图至 {args.output}')
