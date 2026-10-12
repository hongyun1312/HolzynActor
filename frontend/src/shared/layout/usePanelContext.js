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

import { inject, provide, reactive, computed, unref } from 'vue'

/**
 * 面板上下文（可并置多面板工作区的隔离单元）。
 *
 * <p>解决的问题：当**同一类内容被并置两份**（例如「地点列表 + 地点详情」旁边再开一栏
 * 「角色详情」），如果它们共用页面级的 `ref`（当前选中 id、筛选条件），一次点击就会把两份
 * 面板的选中态一起改掉——这正是「可并置多面板」必须先把**面板上下文**抽出来的原因。</p>
 *
 * <p>设计：</p>
 * <ul>
 *   <li>{@link providePanelContext} 在面板根组件调用一次，向下 provide 一个**隔离的响应式状态桶**；</li>
 *   <li>{@link usePanelContext} 在面板内任意子组件调用，拿到本面板的状态桶（`state`/`get`/`set`）；</li>
 *   <li>找不到 provider 时返回一个**独立的临时桶**（不抛异常）——面板组件因此既能嵌在
 *       `WorkspaceSplit` 里用，也能单独用，行为上退化与页面级状态一致。</li>
 * </ul>
 *
 * <p>约定：`state` 只放**瞬时的界面状态**（选中项、筛选、折叠），不放业务数据缓存——
 * 业务数据仍由页面统一持有并 props 传入，避免同一份数据在各面板各拉一次。</p>
 */

/** 注入键（Symbol 保证不会被同名 prop/属性污染） */
const PANEL_CTX_KEY = Symbol('holzyn.panelContext')

/**
 * 创建一个面板上下文对象（**不 provide**，可用于「宿主自己也要读写」的场景）。
 *
 * <p>什么时候用它而不是 {@link providePanelContext}：当页面需要**同时**驱动这个面板
 * （例如列表栏的选中项要触发页面级数据加载），页面与面板必须共享同一个状态桶——
 * 页面先 `createPanelContext` 建桶，再通过 `WorkspaceSplit` 的 `pane.context` 交给面板。</p>
 *
 * @param {string} paneKey 面板键
 * @param {object} [initial] 初始状态
 * @returns {object} 面板上下文对象
 */
export function createPanelContext(paneKey, initial = {}) {
  const state = reactive({ ...initial })
  return {
    /** 面板键 */
    paneKey,
    /** 本面板的响应式状态桶（互不干扰） */
    state,
    /** 读一个键 */
    get: (k, fallback = undefined) => (k in state ? state[k] : fallback),
    /** 写一个键 */
    set: (k, v) => { state[k] = v },
    /** 合并写入多个键（批量更新时避免多次触发） */
    patch: (obj) => { Object.assign(state, obj) },
    /** 复位本面板状态 */
    reset: () => {
      for (const k of Object.keys(state)) delete state[k]
      Object.assign(state, initial)
    },
    /** 本面板当前选中的对象 id（约定键名 activeId；无则为 null） */
    activeId: computed(() => state.activeId ?? null),
    /** 是否选中了某个对象 */
    hasActive: computed(() => state.activeId != null)
  }
}

/**
 * 把一个**已存在**的上下文对象 provide 给子树（配合 `createPanelContext` 使用）。
 *
 * @param {object} ctx 上下文对象
 * @returns {object} 同一个上下文对象
 */
export function provideExistingPanelContext(ctx) {
  provide(PANEL_CTX_KEY, ctx)
  return ctx
}

/**
 * 创建并 provide 一个面板上下文。
 *
 * @param {string} paneKey 面板键（用于调试标识与持久化区分）
 * @param {object} [initial] 初始状态
 * @returns {object} 面板上下文对象
 */
export function providePanelContext(paneKey, initial = {}) {
  return provideExistingPanelContext(createPanelContext(paneKey, initial))
}

/**
 * 读取当前面板上下文。
 *
 * @param {object} [options] 退化配置
 * @param {string} [options.paneKey] 无 provider 时的面板键
 * @param {object} [options.initial] 无 provider 时的初始状态
 * @returns {object} 面板上下文（有 provider 时是它本身；否则是独立临时桶）
 */
export function usePanelContext(options = {}) {
  const injected = inject(PANEL_CTX_KEY, null)
  if (injected) return injected
  // 无 provider：给一个**独立**的临时桶（不共享、不抛错）——保证组件可独立复用
  return providePanelContext(options.paneKey || 'standalone', options.initial || {})
}

/**
 * 便捷读取：只取本面板的状态桶。
 *
 * @param {object} [options] 同 {@link usePanelContext}
 * @returns {object} reactive 状态桶
 */
export function usePanelState(options = {}) {
  return usePanelContext(options).state
}

/**
 * 读取某面板上下文里的值（用于跨面板只读观察，例如右栏想知道左栏选中了谁）。
 *
 * <p>注意：跨面板**只读**是对的（列表→详情是数据流），跨面板**写**是错的
 * （会重新引入「一次点击改两份面板」的问题）。</p>
 *
 * @param {*} value 可能来自 ref 的值
 * @returns {*} 解包后的值
 */
export function readPanelValue(value) {
  return unref(value)
}
