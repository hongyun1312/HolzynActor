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
    面板上下文边界：只做一件事——给本面板的插槽内容 provide 一份隔离的上下文，
    让并置的多个面板互不污染各自的选中项/筛选（详见 usePanelContext.js 的说明）。
    自身不渲染任何 DOM（Fragment 根），因此不引入额外盒子、不影响面板内布局。
  -->
  <slot></slot>
</template>

<script setup>
/**
 * 面板上下文提供者（`WorkspaceSplit` 的每个面板各包一层）。
 *
 * <p>为什么需要这层：Vue 的插槽内容虽然写在页面里，但**组件实例的父子链挂在渲染它的
 * 容器子树中**，因此这里 provide 的上下文能被面板插槽内的子组件 inject 到；
 * 每个面板一个实例，隔离天然成立。</p>
 *
 * <p>两种建桶方式：① 页面传 `context`（页面与面板共享同一个桶，适合「列表选中驱动页面加载」）；
 * ② 不传则按 `paneKey` 自建（适合纯自包含面板）。</p>
 */
import { provideExistingPanelContext, providePanelContext } from './usePanelContext'

const props = defineProps({
  /** 面板键（上下文标识） */
  paneKey: { type: String, required: true },
  /** 页面创建的上下文桶（可选；给了就共享，不给就自建） */
  context: { type: Object, default: null },
  /** 自建桶时的初始界面状态（选中项/筛选等） */
  initial: { type: Object, default: () => ({}) }
})

if (props.context) provideExistingPanelContext(props.context)
else providePanelContext(props.paneKey, props.initial)
</script>
