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
    开发者控制台外壳。

    结构刻意与全站一致：AppShell（同一顶栏 / 同一内容区 / 同一滚动容器）
      → PageShell(width="wide")（同一限宽出口，1440 档，各域页面宽度恒定，切页不抖动）
      → 警示条 + 路由出口。

    为什么 PageShell 放在这里而不是每个域页各写一份：9 个域 + 30 多个子面板，
    只要有一个页面忘了写或写错档位，切页时内容左右边界就会跳 240px。
    把「限宽」钉在外壳层，页面只负责内容，从结构上消除这类抖动。
  -->
  <AppShell
    mode="global"
    :project-id="devContext.worldId"
    brand-label="开发者模式"
    :breadcrumb="breadcrumb"
    :show-sidebar="false"
    back-title="退出开发者模式"
    :back-custom="onExitDev"
  >
    <template #sidebar><DevSidebar /></template>

    <PageShell width="wide">
      <!-- 显眼警示条：进入即见，不可关闭（关闭就等于「忘记自己在改真实数据」） -->
      <div class="dev-banner">
        <el-icon class="dev-banner-icon"><HIcon name="Warning" /></el-icon>
        <div class="dev-banner-text">
          <b>开发者模式 · 高风险</b>
          <span>
            本页直连引擎与数据库：修改参数会立即改变世界运行，部分操作会产生真实的 AI 费用。
            非开发调试请立即退出（左侧「隐藏开发者入口」）。
          </span>
        </div>
        <div class="dev-banner-actions">
          <el-tooltip content="每 2 秒自动刷新当前页数据（离开页面自动关闭）" placement="bottom">
            <label class="dev-banner-switch">
              <span>实时跟随</span>
              <el-switch
                :model-value="devContext.realtime"
                size="small"
                @update:model-value="onRealtime"
              />
            </label>
          </el-tooltip>
          <el-button size="small" @click="onRefresh">
            <el-icon><HIcon name="Refresh" /></el-icon>&nbsp;刷新
          </el-button>
        </div>
      </div>

      <!-- 页面根节点容器：统一纵向节奏 + 给 Tab 面板内容补间距（见 devStyles.css）
           —— Tab 面板是 Element 自己渲染的 div，不在 ps-body 的直接子级链上，
              若不给它 gap，两个卡片会紧贴，切到别的域又恢复 16px，观感上像「页面抖了」。 -->
      <div class="dev-view-root"><router-view /></div>
    </PageShell>
  </AppShell>
</template>

<script setup>
/**
 * 开发者控制台外壳（/dev/**）。
 *
 * <p>职责：① 提供与主应用同一套外壳；② 钉住页面宽度档位；③ 承载全局警示条、
 * 实时跟随开关与手动刷新信号；④ 在路由切换时停掉实时跟随。</p>
 *
 * <p>为什么 warning 条放在内容列里而不是顶栏：顶栏高度只有 56px，塞进两行长文案会把
 * 主应用的顶栏顶变形（那是全站共用的组件）。放在内容列内既能写清风险，又不动公共组件。</p>
 */
import { computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppShell from '@/shared/layout/AppShell.vue'
import PageShell from '@/shared/layout/PageShell.vue'
import HIcon from '@/shared/components/HIcon.vue'
import DevSidebar from './components/DevSidebar.vue'
import { findDevDomainByPath } from './devNav'
import { devContext, markStandaloneDev, requestRefresh, resolveDevWorld, setRealtime, stopRealtime } from './devContext'
import { exitDevMode } from './devExit'
import './devStyles.css'

const route = useRoute()
const router = useRouter()

/**
 * 退出开发者模式（顶栏返回箭头）。
 *
 * <p>把「返回」就定义为「退出开发者模式」：桌面端关掉独立窗口、浏览器独立标签退回画廊、
 * 当前标签页内跳转则原路返回。绝不能落在「进入当前世界」上——那会在开发者独立窗口里
 * 再画一份与世界主窗口完全相同的界面（历史缺陷）。</p>
 */
function onExitDev() {
  exitDevMode(router)
}

/** 顶栏面包屑 = 当前功能域名称 */
const breadcrumb = computed(() => {
  const hit = findDevDomainByPath(route.path)
  return hit ? `开发者 · ${hit.name}` : '开发者'
})

/** 实时跟随开关（关闭时立即生效；开启后由各页面自建定时器） */
function onRealtime(value) {
  setRealtime(!!value)
}

/** 手动刷新（自增 tick，页面自行 watch 后重拉数据，不整页重载） */
function onRefresh() {
  resolveDevWorld()
  requestRefresh()
}

// 切换功能域即停掉实时跟随：否则在 A 页打开的轮询会跟着到 B 页继续跑
watch(() => route.path, () => { stopRealtime() })

// 判定本窗口是不是「独立开发者窗口/标签页」：以 /dev 为唯一历史项打开的窗口退出去要回画廊，
// 否则会和主窗口/原标签页各显示一份相同的世界界面。
onMounted(() => { markStandaloneDev(window.history.length <= 1) })
</script>

<style scoped>
/* 警示条：语义色仅用令牌（深浅主题自适应），不写死红/黄。
   底色必须是「实底 + 一层语义雾」：只铺 --state-danger-bg（12% 透明红）时，
   桌面底图会从字后面透上来，实测正文几乎读不出来。 */
.dev-banner {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3, 12px);
  padding: 10px 16px;
  border: 1px solid color-mix(in srgb, var(--state-warn) 58%, var(--border-l2));
  border-left: 3px solid var(--state-warn);
  border-radius: var(--radius-md, 10px);
  background:
    linear-gradient(var(--state-danger-bg), var(--state-danger-bg)),
    linear-gradient(var(--glass-overlay-bg, var(--bg-layer-1)), var(--glass-overlay-bg, var(--bg-layer-1))),
    var(--bg-layer-1);
  -webkit-backdrop-filter: blur(var(--glass-blur, 16px)) saturate(1.3);
  backdrop-filter: blur(var(--glass-blur, 16px)) saturate(1.3);
  box-shadow: var(--shadow-lv1);
  min-width: 0;
}
.dev-banner-icon {
  font-size: 18px;
  color: var(--state-warn);
  margin-top: 2px;
  flex-shrink: 0;
}
.dev-banner-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
  font-size: var(--density-font-sm, 0.82rem);
  line-height: 1.6;
  /* 正文用主文字色：次要色在半透明底上仍然偏灰，长句读着累 */
  color: var(--text-primary);
}
/* 标题不用纯 --state-warn（浅色主题下白底上只有 2.2:1 对比），
   向主文字色混一档：浅色变深琥珀、深色变浅琥珀，两边都够读。
   52% 是实测取的值：白底 ≈5.7:1、浅粉警示底 ≈4.4:1，都过 WCAG AA 4.5:1 附近，
   再往高混（66%）在浅粉底上只有 ≈3.5:1，肉眼就是「发灰的橙」。 */
.dev-banner-text b {
  color: color-mix(in srgb, var(--state-warn) 52%, var(--text-primary));
  font-weight: 700;
}
.dev-banner-actions {
  display: flex;
  align-items: center;
  gap: var(--space-3, 12px);
  flex-shrink: 0;
}
.dev-banner-switch {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 0.78rem;
  color: var(--text-tertiary);
  cursor: pointer;
  user-select: none;
}

/* 窄屏：动作区换行到下一行，避免把警示文案挤成一条竖线 */
@media (max-width: 900px) {
  .dev-banner { flex-wrap: wrap; }
  .dev-banner-actions { width: 100%; justify-content: flex-end; }
}
</style>
