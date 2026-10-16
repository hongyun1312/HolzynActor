# -*- coding: utf-8 -*-
"""A/B：同样一片汉字，不同 pyftsubset 参数下的 woff2 体积。

目的：确认「减体积」还有多少空间，避免凭感觉调参。
用法：python frontend/scripts/ab-font-options.py
"""
import sys
import tempfile
from pathlib import Path

from fontTools.ttLib import TTFont
from fontTools.subset import main as subset_main

sys.stdout.reconfigure(encoding='utf-8')
ROOT = Path(__file__).resolve().parents[2]
TTF = ROOT / 'frontend' / 'assets' / 'fonts-src' / 'HarmonyOS_Sans_SC_Regular.ttf'
CSS = ROOT / 'frontend' / 'src' / 'shared' / 'styles' / 'fonts.css'

# 取生成出来的 UI 全量片（第 2 片，index=01）的 unicode-range 作为测试集
text = CSS.read_text(encoding='utf-8')
blocks = [b for b in text.split('@font-face') if 'font-weight: 400' in b]
ui_block = blocks[2] if len(blocks) > 2 else blocks[-1]
rng = ui_block.split('unicode-range:')[1].split(';')[0].strip()
cps = []
for part in rng.split(','):
    part = part.strip().replace('U+', '')
    if '-' in part:
        a, b = part.split('-')
        cps.extend(range(int(a, 16), int(b, 16) + 1))
    else:
        cps.append(int(part, 16))
print(f'[ab] 测试集 {len(cps)} 个码位')

VARIANTS = {
    '当前（layout-features=* + desubroutinize）': ['--layout-features=*', '--desubroutinize'],
    '仅保留渲染必需特性': ["--layout-features=kern,liga,vert,vrt2,locl,ccmp", '--no-hinting'],
    '不含任何 layout 特性': ['--layout-features=', '--no-hinting'],
}
with tempfile.TemporaryDirectory() as td:
    for name, extra in VARIANTS.items():
        out = Path(td) / 'x.woff2'
        subset_main([
            str(TTF), f'--unicodes={",".join("U+%X" % c for c in cps)}',
            '--flavor=woff2', '--no-hinting', f'--output-file={out}', *extra
        ])
        print('  {:<44} {:>8.1f} KB'.format(name, out.stat().st_size / 1024))