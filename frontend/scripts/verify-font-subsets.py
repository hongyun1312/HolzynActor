#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
HolzynActor 字体分片「发货产物」独立校验（界面布局与 IA 人性化重构 V2.0 · §13.2.2 验收口径）

为什么要独立校验
----------------
`build-font-subsets.py` 自己有一条断言（切片并集 == cmap），但那是**生成时**对内存里的计划
做的检查，管不到「真正发出去的那份产物」：
  · fonts.css 是脚本写出来的文本，可能被手改/被旧产物覆盖；
  · woff2 文件可能与 CSS 里声明的 unicode-range 对不上（声明覆盖了、文件里没有 → 浏览器**不会**
    回退到别的字体，会直接渲染空白/豆腐块，属于"界面看起来正常但字没了"）；
  · 切片体积上限（首屏/最坏单字代价）没有任何地方把住。
本脚本直接读**仓库里那份** fonts.css 与 woff2 逐片核对，把验收口径变成可执行断言。

断言（全部必须通过，否则退出码 1）
---------------------------------
A. 结构与声明：每个字重的 @font-face 数量、src 文件存在、字重只允许 400/700。
B. 声明不重叠：同一字重内 unicode-range 两两不相交（重叠意味着某个码位由多片声明，行为未定义）。
C. 声明与文件一致（cmap 恒等）：每片 woff2 的 cmap 码位集合 == 该片声明的 unicode-range 码位集合。
D. 无缺字：全部声明范围的并集 == 原始 TTF（Regular/Bold）的 cmap 集合（既不缺字，也不虚报）。
E. 体积口径：单片 woff2 ≤ --max-slice-kb（默认 220 KB）；合计首屏口径由 first-paint.mjs 实测（≤600 KB）。
F. 发货同步：若 frontend/dist 与后端 static 存在，则两侧同名 woff2 与源片大小一致（产物没落半截）。

用法
----
python frontend/scripts/verify-font-subsets.py
python frontend/scripts/verify-font-subsets.py --max-slice-kb 220 --json tmp/font-verify.json
"""

import argparse
import json
import re
import sys
from pathlib import Path

from fontTools.ttLib import TTFont

# Windows 控制台默认 GBK，打印 ✓/❌ 或中文会直接 UnicodeEncodeError 中断校验（实测踩到）。
# 校验脚本的输出必须比它的检查项更可靠，所以这里强制把 stdout 切成 UTF-8。
try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:      # pragma: no cover - 老 Python / 重定向场景
    pass

ROOT = Path(__file__).resolve().parents[2]
CSS = ROOT / 'frontend' / 'src' / 'shared' / 'styles' / 'fonts.css'
SRC_DIR = ROOT / 'frontend' / 'src' / 'assets' / 'fonts'
TTF_DIR = ROOT / 'frontend' / 'assets' / 'fonts-src'
WEIGHT_TTF = {400: 'HarmonyOS_Sans_SC_Regular.ttf', 700: 'HarmonyOS_Sans_SC_Bold.ttf'}
SYNC_DIRS = [
    ROOT / 'frontend' / 'dist' / 'assets',
    ROOT / 'backend' / 'src' / 'main' / 'resources' / 'static' / 'assets',
    ROOT / 'backend' / 'target' / 'classes' / 'static' / 'assets',
]

# @font-face 块解析：只认本脚本生成的那套字段顺序（family / src / weight / style / display / range）
BLOCK_RE = re.compile(r'@font-face\s*\{(.*?)\}', re.S)
SRC_RE = re.compile(r"url\('([^']+)'\)")
WEIGHT_RE = re.compile(r'font-weight:\s*(\d+)')
RANGE_RE = re.compile(r'unicode-range:\s*([^;]+);')


def parse_range(text):
    """'U+20-7E, U+18F' → set(codepoints)"""
    out = set()
    for part in text.split(','):
        tok = part.strip()
        if not tok:
            continue
        m = re.fullmatch(r'U\+([0-9A-Fa-f]+)(?:-([0-9A-Fa-f]+))?', tok)
        if not m:
            raise ValueError('无法解析 unicode-range 片段: %r' % tok)
        lo = int(m.group(1), 16)
        hi = int(m.group(2), 16) if m.group(2) else lo
        if hi < lo:
            raise ValueError('unicode-range 区间颠倒: %r' % tok)
        out.update(range(lo, hi + 1))
    return out


def load_css():
    """返回 [{'file','weight','codepoints','rangeText'}, ...]"""
    text = CSS.read_text(encoding='utf-8')
    faces = []
    for body in BLOCK_RE.findall(text):
        m_src = SRC_RE.search(body)
        m_w = WEIGHT_RE.search(body)
        m_r = RANGE_RE.search(body)
        if not (m_src and m_w and m_r):
            raise ValueError('@font-face 缺字段: %r' % body[:120])
        faces.append({
            'file': m_src.group(1).rsplit('/', 1)[-1],
            'weight': int(m_w.group(1)),
            'rangeText': m_r.group(1).strip(),
            'codepoints': parse_range(m_r.group(1)),
        })
    return faces


def font_cmap(path):
    f = TTFont(str(path), lazy=True)
    try:
        return {cp for table in f['cmap'].tables for cp in table.cmap.keys()}
    finally:
        f.close()


def woff2_cmap(path):
    f = TTFont(str(path), lazy=True)
    try:
        return {cp for table in f['cmap'].tables for cp in table.cmap.keys()}
    finally:
        f.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--max-slice-kb', type=float, default=220.0,
                    help='单片 woff2 体积上限（KB）。实测最坏单字代价 = 最大片体积')
    ap.add_argument('--json', default=None, help='把结论写成 JSON')
    args = ap.parse_args()

    checks = []

    def rec(name, ok, detail=''):
        checks.append({'name': name, 'pass': bool(ok), 'detail': detail})
        print('%s %s%s' % ('✓' if ok else '❌', name, (' — ' + detail) if detail else ''))

    faces = load_css()
    by_weight = {}
    for f in faces:
        by_weight.setdefault(f['weight'], []).append(f)

    rec('A1 · 只声明 400/700 两个字重', sorted(by_weight) == [400, 700], '字重 = %s' % sorted(by_weight))
    rec('A2 · 两个字重片数一致', len(by_weight.get(400, [])) == len(by_weight.get(700, [])),
        '400: %d 片 / 700: %d 片' % (len(by_weight.get(400, [])), len(by_weight.get(700, []))))

    missing = [f['file'] for f in faces if not (SRC_DIR / f['file']).exists()]
    rec('A3 · 每个 @font-face 的 woff2 都存在', not missing, '缺失: %s' % missing[:3])

    ref_cmaps = {w: font_cmap(TTF_DIR / n) for w, n in WEIGHT_TTF.items() if (TTF_DIR / n).exists()}
    rec('A4 · 原始 TTF 可用于对照', sorted(ref_cmaps) == [400, 700],
        'fonts-src: %s' % {w: len(c) for w, c in ref_cmaps.items()})

    max_bytes = args.max_slice_kb * 1024
    slices = []
    for weight in sorted(by_weight):
        seen = {}
        overlap = []
        for f in by_weight[weight]:
            dup = set(seen) & f['codepoints']
            if dup:
                overlap.append('%s 与 %s 重叠 %d 码位' % (f['file'], seen[next(iter(dup))], len(dup)))
            for cp in f['codepoints']:
                seen[cp] = f['file']
        rec('B · 字重 %d 声明范围两两不相交' % weight, not overlap, '; '.join(overlap[:2]))
        union = set(seen)
        ref = ref_cmaps.get(weight)
        if ref is not None:
            miss = ref - union
            extra = union - ref
            rec('D · 字重 %d 声明并集 == 原始 cmap（不缺字/不虚报）' % weight, not miss and not extra,
                '缺少 %d 个 / 虚报 %d 个%s' % (len(miss), len(extra),
                                            ('（例: 缺 %s 多 %s）' % (sorted(miss)[:3], sorted(extra)[:3])) if (miss or extra) else ''))
        for f in by_weight[weight]:
            path = SRC_DIR / f['file']
            if not path.exists():
                continue
            size = path.stat().st_size
            actual = woff2_cmap(path)
            declares = f['codepoints']
            same = actual == declares
            rec('C · %s 文件 cmap == 声明范围' % f['file'], same,
                ('缺 %d 虚报 %d' % (len(declares - actual), len(actual - declares))) if not same else
                '%d 码位 / %.1f KB' % (len(actual), size / 1024))
            rec('E · %s 单片 ≤ %.0f KB' % (f['file'], args.max_slice_kb), size <= max_bytes, '%.1f KB' % (size / 1024))
            slices.append({'file': f['file'], 'weight': weight, 'bytes': size, 'codepoints': len(actual)})

    # F. 发货同步：dist 与后端 static 的同名 woff2 与源片字节数一致（漏拷=用户拿到旧字/缺字）
    # 注意：构建产物带内容哈希（harmonyos-sc-400-00-DcuG4pzt.woff2），所以按「片名 + 可选哈希后缀」匹配，
    # 不能按源文件名精确匹配（第一版就是这么写错的：33 片全报"缺"，其实是文件名不同）。
    for d in SYNC_DIRS:
        if not d.exists():
            rec('F · 发货同步 %s' % d.relative_to(ROOT), True, '目录不存在（未构建/未同步），跳过')
            continue
        bad = []
        for s in slices:
            stem = s['file'][:-len('.woff2')]
            cands = [p for p in d.glob(stem + '*.woff2')]
            if not cands:
                bad.append('缺 %s' % s['file'])
                continue
            for p in cands:
                if p.stat().st_size != s['bytes']:
                    bad.append('%s 大小不一致 %d≠%d' % (p.name, p.stat().st_size, s['bytes']))
        # 反向：发货目录里不该有源目录里不存在的分片（旧构建残留）
        src_stems = {s['file'][:-len('.woff2')] for s in slices}
        orphans = [p.name for p in d.glob('harmonyos-sc-*.woff2')
                   if not any(p.name.startswith(st) for st in src_stems)]
        if orphans:
            bad.append('残留旧分片 %d 个（例 %s）' % (len(orphans), orphans[0]))
        rec('F · 发货同步 %s' % d.relative_to(ROOT), not bad, '; '.join(bad[:3]))

    total = sum(s['bytes'] for s in slices) // 2      # 两个字重各一份，取单字重合计
    biggest = max((s['bytes'] for s in slices), default=0)
    print('\n=== 字体分片校验：%d/%d 通过 ===' % (sum(1 for c in checks if c['pass']), len(checks)))
    print('每字重合计 %.0f KB / %d 片；最大单片 %.1f KB（= 一个生僻字的最坏新增代价）'
          % (total / 1024, len(slices) // 2, biggest / 1024))
    result = {
        'checks': checks,
        'sliceCountPerWeight': len(slices) // 2,
        'bytesPerWeight': total,
        'maxSliceBytes': biggest,
        'maxSliceKbLimit': args.max_slice_kb,
    }
    if args.json:
        Path(args.json).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    return 0 if all(c['pass'] for c in checks) else 1


if __name__ == '__main__':
    sys.exit(main())