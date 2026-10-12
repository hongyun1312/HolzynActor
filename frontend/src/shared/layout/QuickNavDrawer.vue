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
    导航型抽屉内容：把某个「域」或「置顶组」的入口以卡片形式铺开，点击直达。
    用途：顶栏左侧那几个图标（角色 / 记忆与知识 / 世界设定）点击后展开——
    对齐酒馆「点图标看一眼、点一下就进」的肌肉记忆，同时不必记住侧栏位置。
  -->
  <div class="qnd">
    <div class="qnd-head">
      <el-icon :size="15"><HIcon :name="meta.icon" /></el-icon>
      <span class="qnd-title">{{ meta.name }}</span>
      <span class="qnd-desc">{{ meta.desc }}</span>
    </div>

    <button
      v-for="item in items"
      :key="item.key"
      class="qnd-item"
      :class="{ active: item.key === activeKey }"
      @click="go(item)"
    >
      <el-icon class="qnd-icon" :size="17"><HIcon :name="item.icon" /></el-icon>
      <span class="qnd-text">
        <span class="qnd-name">{{ item.name }}</span>
        <span class="qnd-sub">{{ item.desc }}</span>
      </span>
      <el-icon v-if="item.key === activeKey" class="qnd-current" :size="13"><HIcon name="Check" /></el-icon>
    </button>

    <div class="qnd-foot">
      <span class="qnd-tip">提示：等长的面板都可用面板头部的「钥匙图标」固定为常驻，固定后不会被 Esc 或点击空白关闭。</span>
    </div>
  </div>
</template>

<script setup>
/**
 * 导航型抽屉内容组件。
 *
 * <p>职责：按 domainKey 从 navModel 取该域（或置顶组）的导航项，渲染为可点击卡片；
 * 点击后跳转并关闭抽屉。当前所在页会高亮并显示对勾。</p>
 *
 * <p>为什么不做成「侧栏已经有的东西再来一遍」：顶栏图标的价值在于**不必先看侧栏**——
 * 尤其在窄屏侧栏被收起、或用户在对话页深处时，点一个图标就能直接跳到目标页。</p>
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useLayoutStore } from '@/shared/store/layout'
import { DOMAINS, PINNED_ITEMS, NAV_ITEMS, itemPath, findItemByPath } from './navModel'

const props = defineProps({
  /** 域 key（world/cast/mind/observe/settings），或 'pinned' 表示置顶组 */
  domainKey: { type: String, required: true },
  /** 项目 ID（拼绝对路径） */
  projectId: { type: Number, default: null }
})

const route = useRoute()
const router = useRouter()
const layout = useLayoutStore()

const meta = computed(() => {
  if (props.domainKey === 'pinned') {
    return { name: '主工作区', icon: 'ic_gallery_map_all', desc: '世界地图与对话' }
  }
  const d = DOMAINS.find(x => x.key === props.domainKey)
  return d ? { name: d.name, icon: d.icon, desc: d.desc } : { name: '快捷入口', icon: 'ic_public_file', desc: '' }
})

const items = computed(() => props.domainKey === 'pinned'
  ? PINNED_ITEMS.map(i => ({ ...i }))
  : NAV_ITEMS.filter(i => i.domain === props.domainKey).map(i => ({ ...i })))

const activeKey = computed(() => findItemByPath(route.path, props.projectId)?.key || '')

function go(item) {
  const target = itemPath(props.projectId, item.path)
  if (target && target !== '#') router.push(target)
  if (props.domainKey === 'pinned') layout.closeDrawer('pinned')
  else layout.closeDrawer(props.domainKey)
}
</script>

<style scoped>
.qnd { display: flex; flex-direction: column; gap: 4px; }
.qnd-head { display: flex; align-items: baseline; gap: 8px; margin-bottom: 8px; color: var(--text-secondary); }
.qnd-title { font-weight: 600; color: var(--text-primary); }
.qnd-desc { font-size: 0.76rem; color: var(--text-tertiary); }

.qnd-item {
  display: flex; align-items: center; gap: 10px;
  width: 100%;
  padding: 9px 10px;
  background: var(--bg-layer-2);
  border: 1px solid transparent;
  border-radius: var(--radius-md, 12px);
  text-align: left;
  cursor: pointer;
  transition: all .15s var(--dsw-ease);
}
.qnd-item:hover { border-color: var(--accent-border); background: var(--bg-layer-1); transform: translateX(2px); }
.qnd-item.active { border-color: var(--accent-border); background: var(--accent-soft); }
.qnd-icon { color: var(--accent-text); flex-shrink: 0; }
.qnd-text { display: flex; flex-direction: column; min-width: 0; flex: 1; }
.qnd-name { font-size: var(--density-font, 0.9rem); color: var(--text-primary); font-weight: 600; }
.qnd-sub { font-size: 0.74rem; color: var(--text-tertiary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.qnd-current { color: var(--accent-text); }

.qnd-foot { margin-top: 10px; padding-top: 10px; border-top: 1px dashed var(--border-l1); }
.qnd-tip { font-size: 0.74rem; color: var(--text-tertiary); line-height: 1.6; }
</style>
