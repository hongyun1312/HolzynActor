#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
HolzynActor 字体分片生成器（界面布局与 IA 人性化重构 V2.0 · 批次一）

背景
----
改造前：`frontend/src/shared/styles/variables.css` 用 6 个 **未压缩、无 unicode-range** 的
HarmonyOS Sans SC TTF 声明 @font-face（构建输出实测合计 49,518 kB），首屏按需下载即可能拉走
数 MB。本项目的正文是 **AI 运行时生成**的，无法"按用到的字"静态子集化——任何汉字都可能出现，
所以必须走 **unicode-range 分片**路线（Google Fonts 对 CJK 的通行做法）。

做法
----
1. 取全部字重字体的 cmap 并集，按 **使用频率** 排序切成若干片：
   - 第 0 片：ASCII / 拉丁 / 常用标点 / 全角符号（小且必用）；
   - 第 1..k 片：汉字按**语料频率**降序，前若干片覆盖绝大多数界面与叙事文本；
   - 其余片：按码位顺序切块（生僻字兜底，正常首屏不会下载）。
2. 每片用 pyftsubset 生成 woff2（brotli 压缩），并输出 `fonts.css`（每片一条 @font-face，
   带精确 unicode-range）。
3. 生成后：`@@FONT_MANIFEST@@` 供人工核对；原始 TTF 保留在 `frontend/assets/fonts-src/`
   （**不再进入打包产物**），保证本脚本可反复重跑。

依赖：pip install fonttools brotli
用法：python frontend/scripts/build-font-subsets.py
"""

import os
import re
import sys
import json
import shutil
from pathlib import Path
from collections import Counter

from fontTools.ttLib import TTFont
from fontTools.subset import main as subset_main

# ---------------------------------------------------------------- 路径与配置
ROOT = Path(__file__).resolve().parents[2]          # 仓库根
SRC_FONTS = ROOT / 'frontend' / 'assets' / 'fonts-src'      # 原始 TTF（不打包）
OUT_DIR = ROOT / 'frontend' / 'src' / 'assets' / 'fonts'    # 生成的 woff2（打包）
CSS_OUT = ROOT / 'frontend' / 'src' / 'shared' / 'styles' / 'fonts.css'
MANIFEST = ROOT / 'frontend' / 'scripts' / 'font-subset-manifest.json'

# 字重 → 原始文件名（实测页面只用到 400/500/700/800；600/800 由 CSS 就近匹配到 500/700/900，
# 因此保留 400/500/700/900 四个真实字重即可完全复刻现有渲染；100/300 全仓无处使用，删除以减体积）
#
# V2.0 批次二修正：**只保留 400 / 700 两个字重**。实测依据 —— 全仓 `font-weight` 取值只有
# 400（正文）/ 500（markdown 表头，1 处）/ 700（标题）/ 800（品牌 logotype 3 处）。
# CSS 字重匹配规则会让 500 落到 400、800 落到 700（就近向下），因此：
#   · 删掉 500/900 两个字重的全量分片（少下载一个完整字重）；
#   · 同时把那 4 处 CSS 显式改成 400/700，避免"声明 800 实际渲染 700"的隐性不一致。
# 每一次字重都是同一批汉字的第二份字节（首屏 500 字 × 2 ≈ 122 KB），字重数是首屏体积的最大乘数。
WEIGHTS = {
    400: 'HarmonyOS_Sans_SC_Regular.ttf',
    700: 'HarmonyOS_Sans_SC_Bold.ttf',
}

# 语料（用于频率排序）：仓库内中文文本量最大的几类文件（exts 为**真实后缀**，不是通配符）
CORPUS_GLOBS = [
    ('docs', ('.md',)),
    ('output_docs', ('.md', '.csv', '.txt')),
    ('backend/src/main/java', ('.java',)),
    ('backend/src/main/resources', ('.yml', '.sql', '.txt')),
    ('frontend/src', ('.vue', '.js', '.json')),
    ('README.md', None),
    ('CHANGELOG.md', None),
    ('HolzynActor-Research-Report.md', None),
]

# 切片大小：前 HEAD_CHUNKS 片较小（覆盖高频字），其后变大（生僻字兜底，正常不下载）
#
# V2.0 批次二：新增「UI 全量片」（见 plan_slices 注释），并把尾部块从 3000 降到 1000。
# 尾部块是"AI 生成文本里出现一个生僻字"时的代价：3000 字块实测约 350 KB，
# 也就是说一个冷僻字可能拖下 350 KB。降到 1000 后最坏代价约 120 KB，请求数上限约 15 片/字重。
HEAD_CHUNK = 500
HEAD_CHUNKS = 6
TAIL_CHUNK = 1000

# 第 0 片覆盖的码位段（拉丁 + 常用标点 + 全角）
LATIN_RANGES = [
    (0x0020, 0x007E), (0x00A0, 0x00FF), (0x0100, 0x024F),
    (0x2000, 0x206F), (0x20A0, 0x20BF), (0x2100, 0x214F),
    (0x2190, 0x21FF), (0x2200, 0x22FF), (0x2460, 0x24FF),
    (0x25A0, 0x25FF), (0x2600, 0x26FF), (0x2700, 0x27BF),
    (0x3000, 0x303F), (0xFE30, 0xFE4F), (0xFF00, 0xFFEF),
]

CJK_RANGES = [
    (0x3400, 0x4DBF),    # 扩展 A
    (0x4E00, 0x9FFF),    # 基本区
    (0xF900, 0xFAFF),    # 兼容汉字
    (0x20000, 0x2A6DF),  # 扩展 B（多为生僻字）
]


def in_ranges(cp, ranges):
    return any(lo <= cp <= hi for lo, hi in ranges)


# ---------------------------------------------------------------- 语料频率
# UI 源码目录：这些文件里的汉字 = 「界面自身一定会渲染的字」，单独成片（UI 全量片）。
UI_SRC = 'frontend/src'


def build_frequency():
    """统计中文语料字符频率，并单独收集「界面源码用字」。

    :returns: (counter, ui_chars) —— counter 为全语料频率；ui_chars 为 frontend/src 里出现过的汉字集合
    """
    counter = Counter()
    ui_chars = set()
    files = 0
    for rel, exts in CORPUS_GLOBS:
        p = ROOT / rel
        if not p.exists():
            continue
        if p.is_file():
            candidates = [p]
        else:
            candidates = [f for f in p.rglob('*') if f.is_file() and (exts is None or f.suffix in exts)]
        is_ui = (rel == UI_SRC)
        for f in candidates:
            try:
                text = f.read_text(encoding='utf-8', errors='ignore')
            except Exception:
                continue
            files += 1
            for ch in text:
                if '\u4e00' <= ch <= '\u9fff' or '\u3400' <= ch <= '\u4dbf':
                    counter[ch] += 1
                    if is_ui:
                        ui_chars.add(ch)
    print(f'[corpus] 扫描 {files} 个文件，统计到 {len(counter)} 个不同汉字；其中界面源码用字 {len(ui_chars)} 个')
    return counter, ui_chars


def freq_rank(ch, counter):
    """频率降序键：高频在前；未出现过的字按码位排在最后。"""
    return (-counter.get(ch, 0), ord(ch))


# ---------------------------------------------------------------- 切片规划
def plan_slices(cmap_cps, counter, ui_chars):
    """把 cmap 里的码位切成若干片（返回 [[cp, ...], ...]）。

    顺序：拉丁/标点 1 片 → **UI 全量片** → 汉字高频片（每片 HEAD_CHUNK）→ 汉字其余（每片 TAIL_CHUNK）
    → 非汉字符号兜底（每片 TAIL_CHUNK）。**切片并集恒等于字体 cmap**，保证永不缺字。

    V2.0 批次二为什么加「UI 全量片」（实测依据）：
      沙盘页实测**只渲染 500 个不同汉字**，却按频率排名的前 5 片（= 2500 个字）整片下载，
      浪费 5 倍字节。根因是「按频率连续切块」：用到的 500 个字散布在排名 1..2500 里，
      每个块都被命中 → 下载的是**排名区间的并集**，而不是**用字集合**。
      加大切片粒度并不能解决（用字散布时，细切同样命中全部块，总字节不变）。
      解法是把「界面自身一定会用到的字」单独切一片：纯界面页只命中这一片，零浪费；
      AI 运行时的叙事文本用字落在后面的高频片/兜底片上，按频率排名就近命中。
    """
    cps = set(cmap_cps)
    latin_set = {cp for cp in cps if in_ranges(cp, LATIN_RANGES)}
    cjk_set = {cp for cp in cps if in_ranges(cp, CJK_RANGES)}

    latin = sorted(latin_set)
    # UI 全量片：按码位排序（不做频率排名——它的价值就是"整片覆盖、一次命中"）
    ui_slice = sorted(cp for cp in cjk_set if chr(cp) in ui_chars)
    ui_set = set(ui_slice)
    # 其余汉字：按语料频率降序（AI 叙事用字大多落在这一段的头部）
    cjk = sorted((cp for cp in cjk_set if cp not in ui_set), key=lambda cp: freq_rank(chr(cp), counter))
    rest = sorted(cps - latin_set - cjk_set)

    slices = []
    if latin:
        slices.append(latin)
    if ui_slice:
        slices.append(ui_slice)

    head = cjk[:HEAD_CHUNK * HEAD_CHUNKS]
    for i in range(0, len(head), HEAD_CHUNK):
        slices.append(head[i:i + HEAD_CHUNK])

    cjk_tail = cjk[HEAD_CHUNK * HEAD_CHUNKS:]
    for i in range(0, len(cjk_tail), TAIL_CHUNK):
        slices.append(cjk_tail[i:i + TAIL_CHUNK])

    for i in range(0, len(rest), TAIL_CHUNK):
        slices.append(rest[i:i + TAIL_CHUNK])

    return slices


def to_unicode_range(cps):
    """码位列表 → CSS unicode-range（连续段合并为 U+xx-yy）。"""
    parts = []
    start = prev = None
    for cp in cps:
        if start is None:
            start = prev = cp
            continue
        if cp == prev + 1:
            prev = cp
            continue
        parts.append(f'U+{start:X}' if start == prev else f'U+{start:X}-{prev:X}')
        start = prev = cp
    if start is not None:
        parts.append(f'U+{start:X}' if start == prev else f'U+{start:X}-{prev:X}')
    return ', '.join(parts)


# ---------------------------------------------------------------- 主流程
def main():
    # 1) 原始字体：优先用 fonts-src/，其次用仍在 src/assets/fonts/ 的 TTF（首次迁移）
    legacy = ROOT / 'frontend' / 'src' / 'assets' / 'fonts'
    SRC_FONTS.mkdir(parents=True, exist_ok=True)
    for w, name in WEIGHTS.items():
        dst = SRC_FONTS / name
        if dst.exists():
            continue
        src = legacy / name
        if src.exists():
            shutil.move(str(src), str(dst))
            print(f'[move] {name} → frontend/assets/fonts-src/（移出打包目录）')
        else:
            print(f'[error] 找不到原始字体 {name}', file=sys.stderr)
            return 1

    counter = build_frequency()

    # 2) 用 400 字重的 cmap 作为全集（各字重字形集合一致）
    ref = TTFont(str(SRC_FONTS / WEIGHTS[400]), lazy=True)
    cmap_cps = sorted({cp for table in ref['cmap'].tables for cp in table.cmap.keys()})
    ref.close()
    print(f'[cmap] 参考字重含 {len(cmap_cps)} 个码位')

    counter, ui_chars = build_frequency()

    # 2) 用 400 字重的 cmap 作为全集（各字重字形集合一致）
    ref = TTFont(str(SRC_FONTS / WEIGHTS[400]), lazy=True)
    cmap_cps = sorted({cp for table in ref['cmap'].tables for cp in table.cmap.keys()})
    ref.close()
    print(f'[cmap] 参考字重含 {len(cmap_cps)} 个码位')

    slices = plan_slices(cmap_cps, counter, ui_chars)
    print(f'[plan] 切分为 {len(slices)} 片 | 拉丁片 {len(slices[0])} 码位 | '
          f'UI 全量片 {len(slices[1])} 码位 | 其余高频片各 {HEAD_CHUNK}、兜底片各 {TAIL_CHUNK}')
    covered = sum(len(s) for s in slices)
    assert covered == len(cmap_cps), f'切片未覆盖全部码位：{covered} != {len(cmap_cps)}'

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    # 清掉历史产物，保证可重复执行
    for old in OUT_DIR.glob('harmonyos-sc-*.woff2'):
        old.unlink()

    css_blocks = ['''/*
 * HarmonyOS Sans SC 分片字体（由 frontend/scripts/build-font-subsets.py 生成，请勿手改）
 *
 * 为什么分片：本项目正文为 AI 运行时生成，无法静态子集化，必须按 unicode-range 分片。
 * 片序：① 拉丁/标点；② **UI 全量片**（frontend/src 里出现的全部汉字，纯界面页只命中这一片，零浪费）；
 *      ③..⑧ 其余汉字按语料频率降序（AI 叙事用字大多命中这一段的头部）；⑨+ 按码位切块的兜底片。
 * 字重：400 / 700（实测 CSS 只用到 400/500/700/800，就近匹配后等价于两个字重）。
 *
 * 重新生成：python frontend/scripts/build-font-subsets.py
 */''']

    manifest = {'weights': {}, 'sliceCount': len(slices), 'totalBytes': 0}
    total_bytes = 0

    for weight, ttf_name in sorted(WEIGHTS.items()):
        per_weight = []
        for idx, cps in enumerate(slices):
            out = OUT_DIR / f'harmonyos-sc-{weight}-{idx:02d}.woff2'
            subset_main([
                str(SRC_FONTS / ttf_name),
                f'--unicodes={",".join("U+%X" % c for c in cps)}',
                '--flavor=woff2',
                '--layout-features=*',
                '--no-hinting',
                '--desubroutinize',
                f'--output-file={out}',
            ])
            size = out.stat().st_size
            total_bytes += size
            per_weight.append(size)
            css_blocks.append(
                '@font-face {\n'
                "  font-family: 'HarmonyOS Sans SC';\n"
                f"  src: url('../../assets/fonts/{out.name}') format('woff2');\n"
                f'  font-weight: {weight};\n'
                '  font-style: normal;\n'
                '  font-display: swap;\n'
                f'  unicode-range: {to_unicode_range(cps)};\n'
                '}'
            )
        manifest['weights'][str(weight)] = {
            'files': len(per_weight),
            'bytes': sum(per_weight),
            'firstSliceBytes': per_weight[0] if per_weight else 0,
        }
        print(f'[subset] weight {weight}: {len(per_weight)} 片，合计 {sum(per_weight)/1024:.0f} KB，'
              f'首片 {per_weight[0]/1024:.0f} KB')

    manifest['totalBytes'] = total_bytes
    CSS_OUT.write_text('\n\n'.join(css_blocks) + '\n', encoding='utf-8')
    MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')

    print(f'\n[done] 分片合计 {total_bytes/1024/1024:.2f} MB（改造前 6 个 TTF = 49.5 MB）')
    print(f'[done] CSS → {CSS_OUT.relative_to(ROOT)}')
    print(f'[done] 清单 → {MANIFEST.relative_to(ROOT)}')
    return 0


if __name__ == '__main__':
    sys.exit(main())