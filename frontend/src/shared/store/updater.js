/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { defineStore } from 'pinia'

/**
 * 桌面端更新器 store（U1 更新地基）。
 * <p>职责：桥接 window.holzynDesktop.updater（Electron 主进程 electron-updater）：
 * 订阅状态推送、手动检查、确认安装（停后端→备份→静默安装→重启）、自动检查开关、
 * 拉取 GitHub Release「世界更真实」说明。</p>
 * <p>浏览器（非桌面壳）环境：window.holzynDesktop 不存在 → 各项动作 no-op，页面仍可正常渲染。</p>
 */
export const useUpdaterStore = defineStore('updater', {
  state: () => ({
    ready: false,          // 是否已获取首次状态
    enabled: false,        // 更新模块是否可用
    portable: false,       // 便携版（不支持自动安装，引导下载）
    isDev: false,          // 是否开发模式（免打包）
    mock: false,           // 是否冒烟模式（模拟更新）
    currentVersion: '',    // 当前应用版本
    status: 'idle',        // idle|checking|available|downloading|downloaded|not-available|error|installing
    info: null,            // UpdateInfo（version / releaseName / releaseNotes…）
    progress: null,        // 下载进度 { percent, transferred, total, bytesPerSecond }
    error: null,           // 错误信息
    autoCheck: true,       // 启动自动检查
    channel: 'latest',     // latest(stable) | beta
    releaseNotes: null,    // GitHub 最新 Release 正文摘要 { tagName, name, body, publishedAt }
    _unsubscribe: null,
  }),

  getters: {
    /** 是否正处于「有新版本」的可操作态（含下载中/已下载） */
    hasUpdate: (s) => s.status === 'available' || s.status === 'downloading' || s.status === 'downloaded',
    /** 是否已下载完成、可确认安装 */
    downloaded: (s) => s.status === 'downloaded',
    /** 是否正在检查 */
    checking: (s) => s.status === 'checking',
    /** 是否正在下载 */
    downloading: (s) => s.status === 'downloading',
    /** 下载百分比（0-100 整数） */
    progressPercent: (s) => (s.progress ? Math.round(s.progress.percent) : 0),
    /** 新版本号（可用时） */
    newVersion: (s) => (s.info && s.info.version) || '',
  },

  actions: {
    _desktop() {
      return typeof window !== 'undefined' ? window.holzynDesktop : null
    },

    /**
     * 初始化：拉取首次状态 + 订阅推送（应用级单例订阅，重复调用幂等；
     * 订阅跟随应用生命周期常驻，不随组件卸载销毁）。
     */
    async init() {
      const d = this._desktop()
      if (!d) return
      if (!this.ready) {
        this.ready = true
        const s = await d.updater.getState().catch(() => null)
        if (s) this.applyState(s)
        this._unsubscribe = d.updater.onState((s) => this.applyState(s))
      }
      return this
    },

    applyState(s) {
      if (!s) return
      this.enabled = !!s.enabled
      this.portable = !!s.portable
      this.isDev = !!s.isDev
      this.mock = !!s.mock
      this.currentVersion = s.currentVersion || this.currentVersion
      this.status = s.status || 'idle'
      this.info = s.info || null
      this.progress = s.progress || null
      this.error = s.error || null
      this.autoCheck = s.autoCheck !== false
      this.channel = s.channel || 'latest'
    },

    /** 手动检查更新 */
    async check() {
      const d = this._desktop()
      if (!d) return
      this.releaseNotes = null
      await d.updater.check().catch(() => {})
    },

    /** 玩家确认：执行「停后端→备份→静默安装→重启」（返回主进程结果） */
    async confirmInstall() {
      const d = this._desktop()
      if (!d) return { ok: false, reason: '桌面壳不可用' }
      return d.updater.confirmInstall().catch(() => ({ ok: false, reason: '调用失败' }))
    },

    /** 设置启动自动检查 */
    async setAutoCheck(value) {
      const d = this._desktop()
      if (!d) return
      const s = await d.updater.setAutoCheck(value).catch(() => null)
      if (s) this.applyState(s)
    },

    /**
     * 切换更新通道（U3：latest=stable 正式 / beta 预发布）。
     * <p>切换后主进程会：持久化 → 设置 autoUpdater.channel → 触发一次检查；</p>
     * <p>「切回 stable」对 beta 玩家属降级，需 allowDowngrade 支持（主进程已启用）。</p>
     */
    async setChannel(value) {
      const d = this._desktop()
      if (!d) return
      if (value !== 'latest' && value !== 'beta') return
      const s = await d.updater.setChannel(value).catch(() => null)
      if (s) this.applyState(s)
    },

    /** 拉取 GitHub 最新 Release 说明（「世界更真实」清单摘要） */
    async loadReleaseNotes() {
      const d = this._desktop()
      if (!d) return
      this.releaseNotes = await d.updater.fetchReleaseNotes().catch(() => null)
    },

    /** 打开发布页 / 数据目录（浏览器环境用 location 兜底） */
    openReleasePage() {
      const d = this._desktop()
      if (d) d.updater.openReleasePage()
      else window.open('https://github.com/hongyun1312/HolzynActor/releases', '_blank')
    },
    openDataDir() {
      const d = this._desktop()
      if (d) d.updater.openDataDir()
    },

    /** 会话内清理（避免热更新残留订阅） */
    dispose() {
      if (this._unsubscribe) {
        this._unsubscribe()
        this._unsubscribe = null
      }
      this.ready = false
    },
  },
})
