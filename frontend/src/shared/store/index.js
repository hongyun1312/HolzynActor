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
import { fetchAccountMe, fetchAccount, saveAccount, markAccountOnboarded, markLayoutTourSeen as markLayoutTourSeenApi } from '@/shared/api/account'

/**
 * 本地账户 store（替代原认证/登录 store）。
 * <p>职责：管理本地个人账户信息（昵称/头像/签名/结构化档案/个人档案）与首次设置向导状态：
 * 首次启动加载 /api/local-account/me，若未完成向导则引导进入首次设置页；账户信息供顶栏/侧边栏展示。</p>
 * <p>本地单用户模式：userId 恒为 1（与后端 LocalAccountService.LOCAL_USER_ID 一致）。</p>
 */
export const useAccountStore = defineStore('account', {
  state: () => ({
    loaded: false,     // 是否已加载账户
    account: null,     // 本地账户 LocalAccountVO
    onboarded: false   // 是否已完成首次设置向导
  }),
  getters: {
    // 当前用户 ID（本地单用户恒 1）
    userId: () => 1,
    // 展示昵称（缺省「本地用户」）
    nickname: (state) => state.account?.nickname || '本地用户',
    // 头像 URL
    avatarUrl: (state) => state.account?.avatarUrl || '',
    // 是否完成首次设置（未加载时按未完成处理，首次启动引导向导）
    isOnboarded: (state) => state.loaded && state.onboarded,
    // 上次所在项目 ID（世界沙盘 S1：应用启动直达上次项目；null=无）
    lastProjectId: (state) => state.account?.lastProjectId ?? null
  },
  actions: {
    // 加载本地账户（幂等：仅首次请求；force 强制刷新）
    async loadAccount(force = false) {
      if (this.loaded && !force) return this
      try {
        const me = await fetchAccountMe()
        this.account = me.account || null
        this.onboarded = !!me.onboarded
      } catch (_) {
        // 后端未就绪时保持空账户（页面仍可进入，向导兜底）
        this.account = null
        this.onboarded = false
      } finally {
        this.loaded = true
      }
      return this
    },
    // 保存/更新本地账户（保存后同步本地状态；onboarded=1 视为完成向导）
    async save(payload) {
      const vo = await saveAccount(payload)
      this.account = vo
      this.onboarded = !!vo.onboarded
      return vo
    },
    // 标记首次设置向导完成
    async completeOnboarding() {
      const vo = await markAccountOnboarded()
      this.account = vo
      this.onboarded = true
      return vo
    },
    // 刷新账户（设置页编辑后调用）
    async refresh() {
      const vo = await fetchAccount()
      this.account = vo
      this.onboarded = !!vo.onboarded
      return vo
    },
    /**
     * 标记「界面导览」已看过（后端持久化，避免桌面端 origin 变化导致 localStorage 标记丢失后每次启动重播）。
     * <p>幂等：账户已是已看过态时不再请求；请求失败静默（本机 localStorage 仍记录）。</p>
     */
    async markLayoutTourSeen() {
      if (Number(this.account?.layoutTourSeen) === 1) return
      try {
        const vo = await markLayoutTourSeenApi()
        if (vo && this.account) this.account = { ...this.account, layoutTourSeen: vo.layoutTourSeen }
      } catch (_) { /* 后端不可用：本机标记仍生效，不阻塞 */ }
    }
  }
})
