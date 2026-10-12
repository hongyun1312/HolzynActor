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

import { ref, shallowRef } from 'vue'

/**
 * 模块级单例：右侧「上下文抽屉」的状态。
 *
 * <p>为什么用模块级而不是 Pinia store：抽屉内容是一个**组件实例 + props**（不可序列化、
 * 也不该持久化），放进 Pinia 会污染持久化与 devtools 序列化；而它又必须全局唯一
 * （同一时刻只展示一个对象的详情），所以用模块级 shallowRef 最合适。</p>
 */
const contextOpen = ref(false)
const contextMeta = ref({ title: '', subtitle: '', icon: 'ic_public_file' })
const contextView = shallowRef(null)
const contextProps = ref({})

/**
 * 上下文抽屉（右侧详情面板）。
 *
 * <p>用途：页面在「列表 + 详情」场景下，不必再各自实现右侧面板——统一调用
 * `openWith(组件, props, { title, subtitle, icon })` 即可把详情推到右侧抽屉，
 * 关闭时自动清理（避免持有已销毁组件）。</p>
 *
 * <p>与「域侧栏」的区别：侧栏是**去哪一页**（导航），上下文抽屉是**看这一条的详情**
 * （当前选中对象），两者互不干扰、可同时存在。</p>
 *
 * <p>用法（批次② 各页接入）：</p>
 * <pre>
 * const ctx = useContextDrawer()
 * function showDetail(row) {
 *   ctx.openWith(CharacterDetailPanel, { characterId: row.id }, { title: row.name, icon: 'ic_public_contacts' })
 * }
 * </pre>
 */
export function useContextDrawer() {
  /** 打开抽屉并挂载指定组件 */
  function openWith(component, props = {}, meta = {}) {
    contextView.value = component || null
    contextProps.value = props || {}
    contextMeta.value = {
      title: meta.title || '',
      subtitle: meta.subtitle || '',
      icon: meta.icon || 'ic_public_file'
    }
    contextOpen.value = true
  }

  /** 更新标题等元信息（内容不变，例如选中项改名后同步标题） */
  function updateMeta(meta = {}) {
    contextMeta.value = { ...contextMeta.value, ...meta }
  }

  /** 关闭并释放组件引用（关键：否则会持有已卸载组件的响应式依赖） */
  function close() {
    contextOpen.value = false
    contextView.value = null
    contextProps.value = {}
  }

  return { contextOpen, contextMeta, contextView, contextProps, openWith, updateMeta, close }
}
