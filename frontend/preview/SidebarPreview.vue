<!--
  Copyright (c) 2026 HONGYUN
  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at
  http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
  SPDX-License-Identifier: Apache-2.0
-->
<template>
  <!-- 与 AppShell 同构的最小外壳：52px 顶栏 + 侧栏（真实 DomainSidebar）+ 内容区 -->
  <div class="pv-shell">
    <header class="pv-topbar">
      <span class="pv-brand">HolzynActor</span>
      <span class="pv-crumb">世界 · 一个用于校验侧栏观感的假项目</span>
    </header>

    <div class="pv-body">
      <div class="pv-sidebar-slot">
        <DomainSidebar
          :project-id="1"
          :project="project"
          :interface-key="iface"
          :is-icon-only="iconOnly"
        />
      </div>
      <main class="pv-content">
        <h1 class="pv-h1">内容区参照块</h1>
        <p class="pv-p">
          这段文字只用来给侧栏一个视觉参照：侧栏的留白、行高、圆角与这里的内容区对齐后才算「同一套壳」。
          真正的内容区宽度 = 视口宽 − 左留白 − 侧栏宽 − 右留白。
        </p>
        <div v-for="n in 6" :key="n" class="pv-card">
          <div class="pv-card-title">参照卡片 {{ n }}</div>
          <div class="pv-card-body">16px 圆角、12px 内边距的普通卡片，用于比较侧栏同级面板的圆角是否协调。</div>
        </div>
      </main>
    </div>

    <pre v-if="metrics" class="pv-metrics">{{ metrics }}</pre>
  </div>
</template>

<script setup>
/**
 * 侧栏预览壳（仅开发用）。
 *
 * <p>为什么不复用 AppShell：AppShell 会拉起 AI 状态轮询、全局浮层与项目 API，
 * 预览只需要「顶栏 52px + 侧栏 + 内容区」这三个几何量。本组件刻意只做壳，
 * 侧栏本身仍是真实组件（DomainSidebar → SideNav），所以量出来的是真实值。</p>
 *
 * <p>`?debug=1` 时在右下角输出实测几何（行高、图文间距、图标字号、内外留白），
 * 供截图核对与人工验收留证。</p>
 */
import { computed, onMounted, ref } from 'vue'
import DomainSidebar from '@/shared/layout/DomainSidebar.vue'
import { useShellLayout } from '@/shared/layout/useShellLayout'

const params = new URLSearchParams(location.search)
const forceIcon = params.get('icon') === '1'
const iface = params.get('iface') === 'dialogue' ? 'dialogue' : 'fusheng'
const project = { id: 1, name: '预览项目', status: 2, characterCount: 12 }

// 与 AppShell 同源：图标栏形态由外壳按视口断点判定（<1120 自动收），
// 不能只看 ?icon=1，否则窄窗口下会渲染成「56px 宽 + 有文字」的错位形态。
const { sidebarIsIconOnly } = useShellLayout()
const iconOnly = computed(() => forceIcon || sidebarIsIconOnly.value)

const metrics = ref('')
onMounted(() => {
  // 可选的交互态：?collapse=1 收起第一个分组，?hover=1 无（hover 由系统决定），
  // 用来核对「折叠后组间只剩组头」时的间距是否仍然成立。
  if (params.get('collapse') === '1') {
    document.querySelector('.side-head')?.click()
  }
  if (params.get('debug') !== '1') return
  requestAnimationFrame(() => {
    const q = (sel) => document.querySelector(sel)
    const px = (v) => `${Math.round(parseFloat(v) * 10) / 10}px`
    const cs = (el) => (el ? getComputedStyle(el) : null)
    const aside = q('.domain-sidebar')
    const nav = q('.side-nav')
    const item = q('.side-item')
    const head = q('.side-head')
    const icon = q('.side-item .side-icon')
    const headbar = q('.side-headbar')
    const rows = document.querySelectorAll('.side-item, .side-head')
    const active = q('.side-item.active')
    const box = aside?.getBoundingClientRect()
    const off = (el) => (el && box ? Math.round((el.getBoundingClientRect().left - box.left) * 10) / 10 : '-')
    const title = q('.side-headbar-title')
    const foot = q('.side-foot')
    const icon1 = q('.side-item .side-icon svg')
    const text1 = q('.side-item .side-name')

    /** 纵向节奏：组内（→ 子项）与组间（→ 组头）的平均行首间距，用来验证「组间比组内更松」 */
    const rhythm = () => {
      const list = [...document.querySelectorAll('.side-item, .side-head')]
      const tops = list.map(e => Math.round(e.getBoundingClientRect().top))
      const inner = []
      const across = []
      for (let i = 1; i < list.length; i++) {
        const d = tops[i] - tops[i - 1]
        if (list[i].classList.contains('side-head')) across.push(d)
        else inner.push(d)
      }
      const avg = (a) => (a.length ? Math.round(a.reduce((x, y) => x + y, 0) / a.length) : 0)
      return `组内=${avg(inner)} 组间=${avg(across)}`
    }
    const lines = [
      `aside  w=${aside ? px(cs(aside).width) : '-'}  margin=${aside ? cs(aside).margin : '-'}  radius=${aside ? cs(aside).borderRadius : '-'}`,
      `nav    padding=${nav ? cs(nav).padding : '-'}  gap=${nav ? cs(nav).gap : '-'}`,
      `row    h=${item ? px(cs(item).height) : '-'}  font=${item ? cs(item).fontSize : '-'}  pad=${item ? cs(item).padding : '-'}  gap=${item ? cs(item).gap : '-'}  radius=${item ? cs(item).borderRadius : '-'}`,
      `head   h=${head ? px(cs(head).height) : '-'}  font=${head ? cs(head).fontSize : '-'}  radius=${head ? cs(head).borderRadius : '-'}`,
      `icon   font=${icon ? cs(icon).fontSize : '-'}  svg=${icon ? px(cs(icon.querySelector('svg')).width) : '-'}`,
      `topbar h=${headbar ? px(cs(headbar).height) : '-'}  pad=${headbar ? cs(headbar).padding : '-'}`,
      `rows   n=${rows.length}`,
      `font   item=${item ? cs(item).fontFamily.split(',')[0] : '-'} / head=${head ? cs(head).fontFamily.split(',')[0] : '-'}`,
      `align  title=${off(title)} icon=${off(icon1)} text=${off(text1)} foot=${off(foot)}`,
      `rhythm ${rhythm()}`,
      `scroll client=${q('.side-scroll')?.clientWidth}/${q('.side-scroll')?.offsetWidth} row=${item ? Math.round(item.getBoundingClientRect().width) : '-'}`,
      `route  ${location.hash}`,
      `active ${active ? active.textContent.trim().slice(0, 8) + ' / ' + cs(active).backgroundColor : 'none'}`
    ]

    metrics.value = lines.join('\n')
  })
})
</script>

<style scoped>
.pv-shell { height: 100vh; display: flex; flex-direction: column; background: var(--bg-page); overflow: hidden; }
.pv-topbar {
  height: var(--shell-topbar-h, 52px); flex: none;
  display: flex; align-items: center; gap: 12px;
  padding: 0 var(--shell-side-margin, 12px);
  border-bottom: 1px solid var(--border-l1);
  background: var(--bg-layer-1);
}
.pv-brand { font-size: 0.9rem; font-weight: 700; color: var(--text-primary); }
.pv-crumb { font-size: 0.82rem; color: var(--text-tertiary); }
.pv-body { flex: 1; min-height: 0; display: flex; overflow: hidden; }
.pv-sidebar-slot { display: flex; flex-shrink: 0; min-width: 0; }
.pv-content { flex: 1; min-width: 0; overflow-y: auto; padding: var(--content-pad-y, 20px) var(--content-pad-x, 24px); }
.pv-h1 { margin: 0 0 8px; font-size: 1.15rem; color: var(--text-primary); }
.pv-p { margin: 0 0 16px; max-width: 40em; font-size: 0.9rem; line-height: 1.7; color: var(--text-secondary); }
.pv-card { border: 1px solid var(--border-l1); border-radius: var(--radius-lg, 16px); background: var(--bg-layer-1); padding: var(--density-card-pad, 16px); margin-bottom: 12px; }
.pv-card-title { font-size: 0.9rem; font-weight: 600; color: var(--text-primary); }
.pv-card-body { margin-top: 4px; font-size: 0.82rem; color: var(--text-tertiary); }
.pv-metrics {
  position: fixed; right: 16px; bottom: 16px; margin: 0;
  padding: 10px 12px; border-radius: 10px;
  background: rgba(0, 0, 0, .78); color: #e8eefc;
  font-size: 11px; line-height: 1.6; white-space: pre; z-index: 999;
}
</style>
