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
    帮助与快捷键抽屉内容：快捷键速查（按组分类）+ 界面导览重看 + 进入界面设置。
    数据源统一为 shared/layout/shortcuts.js，与 AppShell 的按键分发共用一份表，避免「文档写了但没实现」。
  -->
  <div class="shp">
    <div class="shp-block">
      <div class="shp-block-title">快速上手</div>
      <div class="shp-actions">
        <el-button size="small" type="primary" plain @click="goTutorial">
          <el-icon><HIcon name="ic_public_albums" /></el-icon>
          <span>新手上手教程</span>
        </el-button>
        <el-button size="small" @click="restartTour">
          <el-icon><HIcon name="ic_public_highlight" /></el-icon>
          <span>重看界面导览</span>
        </el-button>
        <el-button size="small" @click="goAppearance">
          <el-icon><HIcon name="ic_public_settings" /></el-icon>
          <span>界面与布局设置</span>
        </el-button>
      </div>
      <p class="shp-tip">
        「新手上手教程」按「它是什么 → 怎么跑起来 → 六个域 → 怎么互动 → 怎么看它是否在跑」六章走一遍，
        每章都带直达入口；「界面导览」则是跟着高亮走的 6 步，解释界面为什么长这样。
      </p>
      <p class="shp-tip">
        侧栏负责「去哪一页」，顶栏图标负责「快速看一眼 / 快速切开关」。
        面板头部的钥匙图标可把面板固定为常驻——固定后不会被 Esc 或点击空白关闭。
      </p>
    </div>

    <div v-for="grp in groups" :key="grp.name" class="shp-block">
      <div class="shp-block-title">{{ grp.name }}</div>
      <div v-for="s in grp.items" :key="s.id" class="shp-row">
        <span class="shp-keys">
          <kbd v-for="(k, i) in s.keys" :key="i" class="shp-kbd">{{ k }}</kbd>
        </span>
        <span class="shp-text">
          <span class="shp-title">{{ s.title }}</span>
          <span v-if="s.desc" class="shp-desc">{{ s.desc }}</span>
        </span>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 帮助与快捷键面板内容。
 *
 * <p>职责：① 按组展示 {@link SHORTCUTS}（含生效条件说明，避免用户误以为「所有地方都能按」）；
 * ② 提供「重看界面导览」与「界面与布局设置」两个入口，解决「重构后老用户找不到设置」的问题。</p>
 */
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useLayoutStore } from '@/shared/store/layout'
import { SHORTCUTS } from './shortcuts'

const router = useRouter()
const route = useRoute()
const layout = useLayoutStore()

/** 按 group 聚类（保持 SHORTCUTS 声明顺序；全局优先） */
const groups = computed(() => {
  const map = new Map()
  for (const s of SHORTCUTS) {
    if (!map.has(s.group)) map.set(s.group, [])
    map.get(s.group).push(s)
  }
  return [...map.entries()].map(([name, items]) => ({ name, items }))
})

/** 重看导览：关闭抽屉并启动 */
function restartTour() {
  layout.closeDrawer('help')
  layout.startTour()
}

/** 打开新手上手教程（全局页；关闭抽屉避免遮挡） */
function goTutorial() {
  layout.closeDrawer('help')
  router.push('/tutorial')
}

/** 打开「界面与布局」设置（项目内优先项目级设置，否则全局设置） */
function goAppearance() {
  layout.closeDrawer('help')
  const m = route.path.match(/^\/project\/(\d+)/)
  router.push(m ? `/project/${m[1]}/settings/appearance` : '/settings/appearance')
}
</script>

<style scoped>
.shp { display: flex; flex-direction: column; gap: var(--space-4, 16px); }
.shp-block-title {
  font-size: 0.76rem; font-weight: 700; letter-spacing: .04em;
  color: var(--text-tertiary); text-transform: uppercase;
  margin-bottom: var(--space-2, 8px);
}
.shp-actions { display: flex; gap: var(--space-2, 8px); flex-wrap: wrap; }
.shp-tip {
  margin: var(--space-3, 12px) 0 0;
  font-size: 0.76rem; line-height: 1.7; color: var(--text-tertiary);
}

.shp-row {
  display: flex; align-items: flex-start; gap: var(--space-3, 12px);
  padding: 6px 0;
  border-bottom: 1px dashed var(--border-l1);
}
.shp-row:last-child { border-bottom: none; }
.shp-keys { flex-shrink: 0; width: 128px; display: flex; flex-wrap: wrap; gap: 3px; }
kbd.shp-kbd {
  font-family: var(--dsw-font-family-code);
  font-size: 0.7rem;
  padding: 1px 6px;
  border: 1px solid var(--border-l2);
  border-bottom-width: 2px;
  border-radius: 4px;
  color: var(--text-secondary);
  background: var(--bg-layer-2);
  white-space: nowrap;
}
.shp-text { display: flex; flex-direction: column; min-width: 0; }
.shp-title { font-size: var(--density-font-sm, 0.82rem); color: var(--text-primary); }
.shp-desc { font-size: 0.72rem; color: var(--text-tertiary); line-height: 1.5; }
</style>
