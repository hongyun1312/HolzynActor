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

/**
 * 云间列车液态玻璃场景：页面挂载时给 <body> 挂 `holzyn-glass`，
 * 让 Teleport 到 body 的浮层（el-dialog / message-box / message / popper / 引导气泡）
 * 也吃到深色玻璃令牌（见 global.css `body.holzyn-glass`）。
 *
 * <p>用法：云间列车背景页（首次设置向导 / 项目画廊）的 setup 中调用
 * `useGlassScene()`；页面卸载自动移除。</p>
 */
import { onBeforeUnmount, onMounted } from 'vue'

const BODY_CLASS = 'holzyn-glass'

export function useGlassScene() {
  onMounted(() => {
    try { document.body.classList.add(BODY_CLASS) } catch (_) { /* 忽略 */ }
  })
  onBeforeUnmount(() => {
    try { document.body.classList.remove(BODY_CLASS) } catch (_) { /* 忽略 */ }
  })
}
