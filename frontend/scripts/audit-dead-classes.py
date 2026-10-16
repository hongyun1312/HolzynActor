# -*- coding: utf-8 -*-
"""全仓「死类」审计：模板里写了类名、样式里却没有对应规则。

发现来源：批次四搬迁「记忆」面板时，注入预览对话框里 5 处 class="dim" 被证明在全仓库
**没有任何样式规则**（次要说明文字一直按正文色渲染）。这类缺陷没有编译错误、没有运行时告警，
只是"该弱的没弱、该藏的没藏"，属于纯观感不一致——只能靠集合差找出来。

做法
----
1. used：所有 frontend/src/**/*.vue 模板里出现的类名——
   · 静态 `class="a b c"`；· `:class="{'a': x, b: y}"` 的对象键；· `:class="['a', cond && 'b']"` 里的字符串字面量。
   （`:class="fn(m)"` 这种运行时算出来的名字无法静态判定，跳过，不产生误报。）
2. defined：所有 `.css` 文件 + 所有 `<style>` 块里选择器位出现的 `.name`。
3. 差集 = used - defined，并排除 Element Plus 的 `el-*` / `is-*` 等第三方与状态类。

输出分三档：
   · 【铁定死类】该文件用了，但**全仓任何地方**都没有定义；
   · 【疑似死类】只被**别的组件**的 scoped style 定义（scoped 样式默认不跨组件生效，通常是搬迁/改名残留）；
   · 【钩子类】被 JS/测试脚本当选择器用（如 `.fr-next` 被截图脚本点击）——不是死类，别删。
用法：python frontend/scripts/audit-dead-classes.py [--json 输出文件]
"""
import argparse
import json
import re
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'frontend' / 'src'

# Vue 模板里的 class 属性（静态）
CLASS_ATTR = re.compile(r'(?<!:)class="([^"]*)"')
# :class 的属性值
BIND_CLASS = re.compile(r':class="([^"]*)"')
# :class 对象里的键：'name': 或 name: （排除函数调用/比较）
OBJ_KEY = re.compile(r"['\"]([A-Za-z_][\w-]*)['\"]\s*:")
OBJ_KEY_BARE = re.compile(r'(?<![\w.\'\"-])([A-Za-z_][\w-]*)\s*:\s*(?:[A-Za-z_$][\w$.]*\s*[<>=!]|true|false|!!)')
# :class 数组/三元里的字符串字面量
STR_LIT = re.compile(r"['\"]([A-Za-z_][\w-]*)['\"]")
# 样式块与选择器里的类名
CLASS_SEL = re.compile(r'\.([A-Za-z_-][\w-]*)')

# 第三方/框架自带或运行时状态类，不算死类
EXCLUDE_PREFIX = ('el-', 'is-', 'hm-', 'v-')
EXCLUDE_EXACT = {'active', 'disabled', 'selected', 'current', 'open', 'closed', 'visible', 'hidden',
                 'loading', 'mono', 'dim', 'prose-measure', 'text-center'}


def template_of(vue_text):
    """取 <template> 段（不含 script/style）。"""
    m = re.search(r'<template>(.*)</template>\s*\n\s*<script', vue_text, re.S)
    return m.group(1) if m else vue_text


def used_classes(vue_text):
    tpl = template_of(vue_text)
    out = set()
    for raw in CLASS_ATTR.findall(tpl):
        for tok in raw.split():
            if tok and not tok.startswith('{'):
                out.add(tok.strip())
    for raw in BIND_CLASS.findall(tpl):
        # 先剔除"动态拼接"里的固定片段：':class="\'lv\' + n"' 里的 'lv' 不是类名（真类是 lv1/lv2/lv3）。
        # 不剔的话审计会把 'lv' 报成死类——第一版就误报了这一条。
        expr = re.sub(r"'[^']*'\s*\+", ' ', raw)
        expr = re.sub(r"\+\s*'[^']*'", ' ', expr)
        expr = re.sub(r'"[^"]*"\s*\+', ' ', expr)
        expr = re.sub(r'\+\s*"[^"]*"', ' ', expr)
        for m in OBJ_KEY.finditer(expr):
            out.add(m.group(1))
        for m in OBJ_KEY_BARE.finditer(expr):
            out.add(m.group(1))
        for m in STR_LIT.finditer(expr):
            out.add(m.group(1))
        # 裸标识符形式的 :class="someName" 不判（可能是变量）
    return out


def style_classes(text):
    out = set()
    for m in CLASS_SEL.finditer(text):
        name = m.group(1)
        if name and name[0].isalpha() or (name and name[0] in '_-'):
            out.add(name)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--json', default=None)
    args = ap.parse_args()

    vue_files = sorted(SRC.rglob('*.vue'))
    css_files = sorted(SRC.rglob('*.css'))
    js_files = sorted(list(SRC.rglob('*.js')) + list((ROOT / 'tests').rglob('*.mjs')) + list((ROOT / 'tests').rglob('*.js')))
    js_text = '\n'.join(f.read_text(encoding='utf-8', errors='ignore') for f in js_files)
    js_names = set(re.findall(r'[.#]([A-Za-z_][\w-]*)', js_text))

    global_defined = set()
    for f in css_files:
        global_defined |= style_classes(f.read_text(encoding='utf-8', errors='ignore'))

    per_file_defined = {}
    for f in vue_files:
        text = f.read_text(encoding='utf-8', errors='ignore')
        styles = '\n'.join(re.findall(r'<style[^>]*>(.*?)</style>', text, re.S))
        per_file_defined[f] = style_classes(styles)

    only_other_files = {}     # class -> 定义了它的其它组件
    for f, s in per_file_defined.items():
        for name in s:
            only_other_files.setdefault(name, set()).add(f)

    hard = []       # 铁定死类
    soft = []       # 疑似死类（只被别的组件 scoped 定义）
    hooks = []      # 被 JS/测试脚本当选择器用的"钩子类"（不是死类，别删）
    for f in vue_files:
        used = used_classes(f.read_text(encoding='utf-8', errors='ignore'))
        own = per_file_defined[f]
        for name in sorted(used):
            if name.startswith(EXCLUDE_PREFIX) or name in EXCLUDE_EXACT:
                continue
            if name in own or name in global_defined:
                continue
            rel = f.relative_to(ROOT).as_posix()
            owners = only_other_files.get(name)
            if name in js_names:
                hooks.append({'file': rel, 'class': name})
            elif owners:
                soft.append({'file': rel, 'class': name,
                             'definedIn': [o.relative_to(ROOT).as_posix() for o in sorted(owners)][:3]})
            else:
                hard.append({'file': rel, 'class': name})

    print('=' * 78)
    print('死类审计：%d 个 .vue / %d 个 .css / %d 个 .js/.mjs' % (len(vue_files), len(css_files), len(js_files)))
    print('=' * 78)
    print('\n【铁定死类】用了但全仓无定义：%d 处' % len(hard))
    for h in hard:
        print('  · %-58s .%s' % (h['file'], h['class']))
    print('\n【疑似死类】只被别的组件 scoped style 定义：%d 处' % len(soft))
    for s in soft[:60]:
        print('  · %-58s .%-22s ← %s' % (s['file'], s['class'], ', '.join(s['definedIn'])))
    if len(soft) > 60:
        print('  …（另有 %d 处，见 JSON）' % (len(soft) - 60))
    print('\n【钩子类】被 JS/测试脚本当选择器用（不算死类）：%d 处' % len(hooks))
    for h in hooks[:20]:
        print('  · %-58s .%s' % (h['file'], h['class']))

    if args.json:
        Path(args.json).write_text(json.dumps({'hard': hard, 'soft': soft, 'hooks': hooks}, ensure_ascii=False, indent=2), encoding='utf-8')
        print('\n已写出 %s' % args.json)
    return 0


if __name__ == '__main__':
    sys.exit(main())