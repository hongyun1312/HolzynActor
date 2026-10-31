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
  <!--
    应用菜单（顶栏最右侧）：替代原「本地账户」下拉。
    本应用是本地单机应用（无账号/登录体系），右上角不再展示用户身份，
    改为「应用」入口（对齐 IDEA / VS Code 右上角的应用级菜单）：
      身份区 = 应用本身（Logo + 产品名 + 版本 + 运行环境），而非用户；
      应用区 = 关于 / 更新与版本 / 偏好设置；
      工作区 = 项目总览 / 新建项目。
  -->
  <el-popover
    v-model="menuOpen"
    placement="bottom-end"
    :width="316"
    trigger="click"
    popper-class="app-menu-popper"
  >
    <template #reference>
      <button class="app-trigger" title="应用菜单：关于 · 设置 · 更新">
        <span class="app-mark" aria-hidden="true">H</span>
        <el-icon :size="12" class="app-chevron"><HIcon name="ArrowDown" /></el-icon>
      </button>
    </template>

    <div class="app-panel">
      <!-- 应用身份区：本地单机应用 → 这里展示的是「应用本身」，不是用户账号 -->
      <div class="panel-app">
        <span class="app-logo" aria-hidden="true">H</span>
        <div class="panel-app-info">
          <div class="app-name">HolzynActor</div>
          <div class="app-subtitle">全境众生 · 浮生</div>
          <div class="app-meta">
            <el-tag size="small" type="primary" effect="light" class="app-version-tag">v{{ version }}</el-tag>
            <span class="app-env">{{ envLabel }}</span>
          </div>
        </div>
      </div>

      <!-- 应用区 -->
      <div class="panel-group">
        <div class="group-label">应用</div>
        <div
          v-for="item in appItems"
          :key="item.key"
          class="menu-item"
          :class="{ 'is-danger': item.danger }"
          @click="onAppItem(item)"
        >
          <el-icon :size="16" class="menu-icon"><HIcon :name="item.icon" /></el-icon>
          <span class="menu-label">{{ item.label }}</span>
          <span v-if="item.hint" class="menu-hint">{{ item.hint }}</span>
        </div>
      </div>

      <!-- 工作区 -->
      <div class="panel-group">
        <div class="group-label">工作区</div>
        <div v-for="item in navItems" :key="item.path" class="menu-item" @click="go(item.path)">
          <el-icon :size="16" class="menu-icon"><HIcon :name="item.icon" /></el-icon>
          <span class="menu-label">{{ item.label }}</span>
        </div>
      </div>

      <!-- 页脚：强调本地应用属性 -->
      <div class="panel-footer">
        <span class="footer-note">本地应用 · 数据存于本机</span>
        <span class="footer-copy">© 2026 HONGYUN</span>
      </div>
    </div>
  </el-popover>

  <!-- 关于弹窗（大厂 About 风格） -->
  <el-dialog
    v-model="aboutOpen"
    :width="420"
    :show-close="true"
    append-to-body
    class="about-dialog"
    :close-on-click-modal="true"
  >
    <div class="about-body">
      <span class="about-logo" aria-hidden="true">H</span>
      <div class="about-name">HolzynActor</div>
      <div class="about-slogan">让世界像真人一样生活</div>
      <div class="about-meta">
        <el-tag size="small" type="primary" effect="light">v{{ version }}</el-tag>
        <span class="about-env">{{ envLabel }} · 单机本地版</span>
      </div>
      <div class="about-desc">
        AI 世界模拟引擎 · 全境众生 · 浮生<br />
        <span class="about-stack">Electron · Spring Boot · Vue 3 · WebGL 2</span>
      </div>
      <div class="about-copy">Copyright © 2026 HONGYUN · Apache-2.0</div>
    </div>
    <template #footer>
      <el-button type="primary" @click="aboutOpen = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 应用菜单（顶栏最右侧）。
 *
 * <p>背景：HolzynActor 是本地单机应用（无账号/登录），顶栏右侧原先的「本地账户」
 * 下拉（头像/昵称/账户 ID/本地账户设置）已无意义。本组件把该位置重构为大厂桌面应用
 * 右上角的「应用级菜单」——身份区展示应用本身（Logo/产品名/版本/环境），
 * 应用区提供「关于 / 更新与版本 / 偏好设置」，工作区保留「项目总览 / 新建项目」。</p>
 *
 * <p>版本号来源：直接读取 <code>frontend/package.json</code>（与全仓版本口径一致，
 * 保证「关于」里的版本永远跟上发版）。</p>
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import pkg from '../../../../package.json'

const router = useRouter()
/** 应用菜单弹层是否展开（点菜单项后收起） */
const menuOpen = ref(false)
/** 关于弹窗 */
const aboutOpen = ref(false)

/** 版本号（与 frontend/package.json 同步，即全仓版本口径 v1.1.2-beta.1） */
const version = pkg.version || '1.1.2-beta.1'
/** 是否运行在桌面壳（Electron）；否则为纯 Web */
const isDesktop = computed(() => typeof window !== 'undefined' && !!window.holzynDesktop)
/** 运行环境标签 */
const envLabel = computed(() => (isDesktop.value ? '桌面版' : 'Web 版'))

/** 应用区菜单 */
const appItems = [
  { key: 'about', label: '关于 HolzynActor', hint: '版本与版权信息', icon: 'InfoFilled' },
  { key: 'update', label: '更新与版本', hint: '检查更新 · 数据备份', icon: 'Download' },
  { key: 'settings', label: '偏好设置', hint: '主题 · 云间列车 · 引擎', icon: 'Setting' }
]

/** 工作区快捷入口 */
const navItems = [
  { label: '项目总览', path: '/', icon: 'FolderOpened' },
  { label: '新建项目', path: '/world/new', icon: 'Plus' }
]

/** 应用区点击：关于=弹窗；更新/设置=跳对应设置子页（用全路径，避免依赖父级相对重定向） */
function onAppItem(item) {
  menuOpen.value = false
  if (item.key === 'about') { aboutOpen.value = true; return }
  if (item.key === 'update') { router.push('/settings/update'); return }
  if (item.key === 'settings') { router.push('/settings/general'); return }
}

/** 工作区跳转 */
function go(path) { menuOpen.value = false; router.push(path) }
</script>

<style scoped>
/* ===== 触发器（顶栏最右：品牌 H 标 + 小箭头，替代原用户头像） ===== */
.app-trigger {
  display: inline-flex;
  align-items: center;
  gap: 1px;
  height: 32px;
  padding: 0 4px;
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--radius-sm, 8px);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all .15s var(--dsw-ease);
  flex-shrink: 0;
}
.app-trigger:hover { background: var(--bg-hover); color: var(--text-primary); }
.app-mark {
  width: 26px; height: 26px;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--brand-gradient);
  border-radius: 8px;
  color: #fff;
  font-size: .82rem; font-weight: 700; letter-spacing: .02em;
  user-select: none;
}
.app-chevron { opacity: .7; transition: transform .2s var(--dsw-ease); }
.app-trigger:hover .app-chevron { opacity: 1; }

/* ===== 弹层面板 ===== */
.app-panel { padding: 4px 0; }

/* 身份区：应用本身（非用户） */
.panel-app {
  display: flex; gap: 12px; align-items: center;
  padding: 10px 14px 14px;
  border-bottom: 1px solid var(--border-l1);
}
.app-logo {
  width: 42px; height: 42px;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--brand-gradient);
  border-radius: 12px;
  color: #fff;
  font-size: 1.25rem; font-weight: 700;
  flex-shrink: 0;
  user-select: none;
}
.panel-app-info { flex: 1; min-width: 0; }
.app-name { font-size: .95rem; font-weight: 650; color: var(--text-primary); }
.app-subtitle { font-size: .78rem; color: var(--text-secondary); margin-top: 1px; }
.app-meta { display: flex; align-items: center; gap: 8px; margin-top: 7px; }
.app-version-tag { font-family: var(--dsw-font-family-code); }
.app-env { font-size: .72rem; color: var(--text-tertiary); }

/* 分组 */
.panel-group { padding: 5px 0; border-bottom: 1px solid var(--border-l1); }
.group-label {
  font-size: .7rem; color: var(--text-tertiary);
  padding: 4px 16px 3px;
  letter-spacing: .05em;
}
.menu-item {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 16px;
  cursor: pointer;
  color: var(--text-regular);
  transition: background .15s var(--dsw-ease);
}
.menu-item:hover { background: var(--bg-hover); color: var(--accent-text); }
.menu-icon { color: var(--text-secondary); flex-shrink: 0; }
.menu-item:hover .menu-icon { color: var(--accent-text); }
.menu-label { flex: 1; font-size: .85rem; }
.menu-hint { font-size: .72rem; color: var(--text-tertiary); white-space: nowrap; }

/* 页脚 */
.panel-footer {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 16px 4px;
  font-size: .7rem;
  color: var(--text-tertiary);
}

/* ===== 关于弹窗（大厂 About 风格：居中 Logo + 名称 + 版本 + 版权） ===== */
.about-body { display: flex; flex-direction: column; align-items: center; text-align: center; padding: 6px 8px 0; }
.about-logo {
  width: 56px; height: 56px;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--brand-gradient);
  border-radius: 16px;
  color: #fff;
  font-size: 1.7rem; font-weight: 700;
  margin-bottom: 14px;
  user-select: none;
  box-shadow: var(--shadow-lv1);
}
.about-name { font-size: 1.15rem; font-weight: 700; color: var(--text-primary); }
.about-slogan { font-size: .85rem; color: var(--text-secondary); margin-top: 4px; }
.about-meta { display: flex; align-items: center; gap: 8px; margin-top: 10px; }
.about-env { font-size: .75rem; color: var(--text-tertiary); }
.about-desc { font-size: .78rem; color: var(--text-secondary); line-height: 1.8; margin-top: 14px; }
.about-stack { color: var(--text-tertiary); font-family: var(--dsw-font-family-code); font-size: .72rem; }
.about-copy { font-size: .72rem; color: var(--text-tertiary); margin-top: 14px; }
</style>

<style>
/* el-popover 外层（teleport 到 body）：收紧默认内边距，与面板留白一致 */
.app-menu-popper.el-popover { padding: 4px 0; }
</style>
