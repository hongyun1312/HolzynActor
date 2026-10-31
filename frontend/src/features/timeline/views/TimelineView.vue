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
  <div class="timeline-view">
    <!-- 统一页头：标题 + 说明 + 页面级操作（新增事件 / AI 识别） -->
    <PageHeader
      title="时间线"
      desc="世界事件、角色行动与记忆里程碑的统一时间轴（可按精度与类型筛选）。"
    >
      <template #actions>
        <el-button size="small" type="warning" plain :loading="aiGenerating" @click="doAiGenerate">
          <el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;AI 识别事件
        </el-button>
        <el-button size="small" type="primary" @click="openAddEvent">
          <el-icon><HIcon name="Plus" /></el-icon>&nbsp;新增事件
        </el-button>
      </template>
    </PageHeader>

    <!-- 工具栏：精度切换 + 类型筛选 + 当前游戏时间 -->
    <div class="tl-toolbar">
      <div class="toolbar-left">
        <el-segmented v-model="precision" :options="precisionOptions" size="small" />
        <el-checkbox-group v-model="typeFilter" class="type-filter" @change="reload">
          <el-checkbox-button value="event">世界事件</el-checkbox-button>
          <el-checkbox-button value="action">角色行动</el-checkbox-button>
          <el-checkbox-button value="memory">记忆里程碑</el-checkbox-button>
        </el-checkbox-group>
      </div>
      <div class="toolbar-right">
        <el-tag v-if="clock" size="small" type="danger" effect="light" class="now-tag">
          ▬ 当前：{{ clock.gameTimeText || (`第 ${clock.day} 日 · ${clock.periodText}`) }}
        </el-tag>
      </div>
    </div>

    <!-- 主体 · 竖轴时间线（新→旧，滚到底自动续加载下一批） -->
    <div ref="bodyRef" class="tl-body" v-loading="loading">
      <div v-if="!loading && nodes.length === 0" class="tl-empty">
        <EmptyState
          size="page"
          icon="ic_public_clock"
          title="时间线上还没有节点"
          desc="世界事件、角色行动与记忆里程碑都会汇聚到这里，形成这个世界的编年史。"
          :points="['世界推进时自动新增', '可手动补录事件', '也可让 AI 从记忆里识别事件']"
        />
      </div>
      <div v-else class="tl-axis-wrap">
        <!-- 竖轴 -->
        <div class="tl-axis">
          <!-- 横红线：标记当前项目所处时间段（来自世界时钟）。放在轴顶部 = 「最新的在最近处」 -->
          <div class="tl-now-line">
            <span class="tl-now-label">现在 · {{ clock?.gameTimeText || `第 ${clock?.day || 1} 日 ${clock?.periodText || ''}` }}</span>
          </div>
          <!-- 分组刻度标签（按精度切换：年/月/日）+ 节点卡片（新→旧） -->
          <div v-for="(grp, gi) in groups" :key="gi" class="tl-group">
            <div class="tl-group-label">{{ grp.label }}</div>
            <div v-for="n in grp.items" :key="n.key" class="tl-node" :class="`kind-${n.kind}`" @click="selectNode(n)">
              <div class="tl-card">
                <div class="tl-card-head">
                  <el-tag size="small" :type="nodeTagType(n.kind)" effect="plain">{{ nodeKindLabel(n.kind) }}</el-tag>
                  <el-tag v-if="n.source" size="small" type="info" effect="plain" class="src-tag">{{ sourceLabel(n.source) }}</el-tag>
                  <span class="tl-card-time">{{ n.gameTime || fmtTime(n.time) }}</span>
                </div>
                <div v-if="n.gameTime" class="tl-card-real">现实：{{ fmtTime(n.time) }}</div>
                <div class="tl-card-title">{{ n.title }}</div>
                <div class="tl-card-text">{{ n.text }}</div>
              </div>
            </div>
          </div>
        </div>
        <!-- 续加载进度 / 到底提示（同时是 IntersectionObserver 的观测哨兵） -->
        <div ref="sentinelRef" class="tl-foot">
          <span class="tl-count">已显示 {{ nodes.length }} / 共 {{ total }} 条</span>
          <el-button v-if="hasMore" size="small" plain :loading="loadingMore" @click="loadMore">
            {{ loadingMore ? '加载中…' : '加载更多' }}
          </el-button>
          <span v-else-if="nodes.length > 0" class="tl-end">已到最早一条</span>
        </div>
      </div>
    </div>

    <!-- 事件详情抽屉 -->
    <el-drawer v-model="detailDrawer" :title="selected?.title || '事件详情'" size="420px">
      <template v-if="selected">
        <div class="dt-grid">
          <div class="dt-item"><span class="k">类型</span><span class="v"><el-tag size="small" :type="nodeTagType(selected.kind)" effect="plain">{{ nodeKindLabel(selected.kind) }}</el-tag></span></div>
          <div class="dt-item"><span class="k">世界时间</span><span class="v">{{ selected.gameTime || '—' }}</span></div>
          <div class="dt-item"><span class="k">现实时间</span><span class="v">{{ fmtTime(selected.time) }}</span></div>
          <div class="dt-item"><span class="k">所属角色</span><span class="v">{{ selected.characterName || '世界' }}</span></div>
          <div class="dt-item"><span class="k">触发来源</span><span class="v">{{ selected.source ? sourceLabel(selected.source) : '—' }}</span></div>
        </div>
        <div class="dt-content">{{ selected.text }}</div>
      </template>
    </el-drawer>

    <!-- 事件对话框（手动持久化；AI 识别走上方按钮） -->
    <el-dialog v-model="addDialog.visible" title="新增事件（手动）" width="520px">
      <el-form label-width="80px">
        <el-form-item label="事件标题">
          <el-input v-model="addDialog.title" placeholder="如：帝国军队进驻边境" maxlength="100" />
        </el-form-item>
        <el-form-item label="事件内容" required>
          <el-input v-model="addDialog.content" type="textarea" :rows="4" placeholder="描述事件经过（含时间地点影响），将持久化到项目时间线" maxlength="2000" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="addDialog.loading" @click="doAddEvent">添加</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 时间线页。
 * <p>职责：以游戏时间为竖轴的全局事件视图（项目「编年史」）——
 * 头部工具栏（精度切换 + 类型筛选 + 手动新增事件[持久化] + AI 从世界观识别生成事件）；
 * 主体竖轴时间线（分组刻度 + 节点卡片 + 横红线标记当前时间段）；
 * 点击卡片打开详情抽屉。数据来自统一聚合接口 /api/worlds/{id}/timeline
 * （actor_event 多类节点[event/manual/evolution/system/action/crowd/world] + 行动 plan/log +
 * 世界事件消息 + 记忆摘要）。
 * <b>B1</b>：接入 /api/worlds/{id}/world-events/stream SSE——收到 world-event 自动重新加载，
 * 行动/居民活动/世界事件/演化归档实时上轴；crowd（居民活动）/world（世界事件）/evolution（演化归档）/
 * manual（手动）/system（系统）节点标签与配色。</p>
 */
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchProjectTimeline, createProjectEvent, aiGenerateProjectEvent, fetchWorldClock, worldEventsStreamUrl } from '@/shared/api'
import { createSubscription, SSE_EVENTS } from '@/shared/sse/sseClient'
import PageHeader from '@/shared/layout/PageHeader.vue'
// V2.0 批次二：空态统一走 EmptyState
import EmptyState from '@/shared/components/EmptyState.vue'

const route = useRoute()
const projectId = Number(route.params.id)

// ===== 工具栏 =====
const precisionOptions = [
  { label: '年', value: 'year' },
  { label: '月', value: 'month' },
  { label: '日', value: 'day' }
]
const precision = ref('month')
const typeFilter = ref(['event', 'action', 'memory'])

// ===== 数据 =====
/**
 * 每批条数（首屏与续加载同一批大小）。
 *
 * <p>100 而不是 25：25 条在 1440×900 下只够填一屏多一点，用户「一直向下滑才加载」的体验就是这么来的；
 * 100 条约等于 30 屏，覆盖近期历史不需要滚动，且首屏渲染仍在百毫秒量级。与后端
 * `TimelineService.PAGE_DEFAULT_LIMIT`（100）显式对齐——两边不一致会出现「要 100 只给 25」。</p>
 */
const PAGE_SIZE = 100

/** 单次请求条数上限（后端 clampLimit 的上限 500；续加载按批取，不用一次要全量） */
const MAX_LIMIT = 500

/** 已加载的节点（累积；不是「本批」） */
const nodes = ref([])
/** 过滤后的总条数（后端返回，不是已加载条数） */
const total = ref(0)
/** 是否还有更早的节点可续加载 */
const hasMore = ref(false)
/** 首屏/重新加载中 */
const loading = ref(false)
/** 续加载中（防止滚动抖动触发并发请求） */
const loadingMore = ref(false)
const clock = ref(null)
const aiGenerating = ref(false)
const selected = ref(null)
const detailDrawer = ref(false)
/** 滚动容器（IntersectionObserver 的 root） */
const bodyRef = ref(null)
/** 续加载哨兵（滚到它附近就自动拉下一批） */
const sentinelRef = ref(null)

// ===== 新增事件 =====
const addDialog = reactive({ visible: false, loading: false, title: '', content: '' })

/**
 * 类型文案（B1：多类节点——event/manual/evolution/system + action/crowd/world； 增 roleplay；
 * /：world_frame=世界帧事件[每日帧宏观事件/扩散到达/演化归档统一类别]；
 * crowd/common 同义（新写入 common，历史 crowd 兼容），都按「居民活动」渲染）
 */
function nodeKindLabel(kind) {
  return {
    event: '世界事件', manual: '手动事件', evolution: '演化归档', system: '系统',
    action: '角色行动', crowd: '居民活动', common: '居民活动', world: '世界事件', memory: '记忆里程碑',
    roleplay: 'NPC 自发对话', world_frame: '世界帧'
  }[kind] || kind
}
function nodeTagType(kind) {
  return {
    event: 'warning', manual: 'info', evolution: 'danger', system: 'info',
    action: 'primary', crowd: 'success', common: 'success', world: 'warning', memory: 'success',
    roleplay: 'primary', world_frame: 'warning'
  }[kind] || 'info'
}

/** 来源文案 */
function sourceLabel(s) {
  return { manual: '手动', ai: 'AI 识别', simulation: '世界模拟', evolution: '演化归档',
    dialog: '对话', action: '行动', crowd: '居民活动', common: '居民活动', roleplay: 'NPC 自发' }[s] || s
}

/** 时间格式化 */
function fmtTime(t) {
  if (!t) return ''
  return String(t).slice(0, 16).replace('T', ' ')
}

/**
 * 节点排序比较器（新→旧），与后端聚合排序完全同源。
 *
 * <p>后端优先 game_hour（世界时钟刻度），没有才用真实时间兜底；前端续加载时把每批拼起来后
 * 仍要按同一口径排，否则「第 2 批」会插到第 1 批中间（旧实现按 time 升序重排就踩过这个坑）。</p>
 */
function compareNodeDesc(a, b) {
  const ga = a?.gameHour
  const gb = b?.gameHour
  if (ga != null && gb != null && Number(ga) !== Number(gb)) return Number(gb) - Number(ga)
  return String(b?.time || '').localeCompare(String(a?.time || ''))
}

/** 按精度对节点分组（年/月/日），保持新→旧顺序 */
const groups = computed(() => {
  const ordered = [...nodes.value].sort(compareNodeDesc)
  const groups = []
  let cur = null
  for (const n of ordered) {
    const label = groupLabel(n.time)
    if (!cur || cur.label !== label) {
      cur = { label, items: [] }
      groups.push(cur)
    }
    cur.items.push(n)
  }
  return groups
})

/** 按精度计算分组标签 */
function groupLabel(t) {
  const s = String(t || '')
  if (precision.value === 'year') return s.slice(0, 4) || '未知'
  if (precision.value === 'day') return s.slice(0, 10) || '未知'
  return s.slice(0, 7) || '未知'
}

/** 组装请求参数（类型筛选） */
function buildParams(offset, limit) {
  const params = { limit, offset }
  if (typeFilter.value.length > 0) params.types = typeFilter.value.join(',')
  return params
}

/**
 * 取一批节点（统一解包「分页信封」与旧版裸数组）。
 *
 * @param {number} offset 起始偏移
 * @param {number} limit 条数
 * @returns {Promise<{nodes: Array, total: number, hasMore: boolean}>}
 */
async function fetchPage(offset, limit) {
  const resp = await fetchProjectTimeline(projectId, buildParams(offset, limit))
  // 兼容两种响应：新版 {nodes,total,limit,offset,hasMore} 与旧版裸数组
  if (Array.isArray(resp)) {
    return { nodes: resp, total: offset + resp.length, hasMore: false }
  }
  const list = Array.isArray(resp?.nodes) ? resp.nodes : []
  return {
    nodes: list,
    total: Number.isFinite(resp?.total) ? resp.total : offset + list.length,
    hasMore: resp?.hasMore === true
  }
}

/**
 * 首屏/重新加载（重置为最新一批）。
 *
 * @param {{ append?: boolean }} opts append=true 表示续加载（不重置）
 */
async function load(opts = {}) {
  const append = opts.append === true
  if (append && (loadingMore.value || loading.value || !hasMore.value)) return
  if (append) loadingMore.value = true
  else loading.value = true
  try {
    const offset = append ? nodes.value.length : 0
    const page = await fetchPage(offset, PAGE_SIZE)
    if (append) {
      // 世界事件可能在两次请求之间插入顶部，导致 offset 边界重复——按 key 去重（幂等）
      const seen = new Set(nodes.value.map((n) => n.key))
      nodes.value = nodes.value.concat(page.nodes.filter((n) => !seen.has(n.key)))
    } else {
      nodes.value = page.nodes
    }
    total.value = page.total
    hasMore.value = page.hasMore
  } catch (e) {
    if (!append) ElMessage.error(e.message || '时间线加载失败')
  } finally {
    if (append) loadingMore.value = false
    else loading.value = false
    await nextTick()
    ensureObserver()
  }
}

/** 重新加载首屏（类型筛选变化时） */
function reload() {
  return load()
}

/** 续加载下一批（滚到底自动触发，也可点「加载更多」） */
function loadMore() {
  return load({ append: true })
}

/**
 * 世界有新事件时同步顶部节点，但不把用户已经翻出来的历史丢掉。
 *
 * <p>旧实现收到 SSE 直接 `load()` 重置成一批：用户翻到第 5 批时来一条世界事件，
 * 页面瞬间被弹回最新 100 条。这里按「已加载条数」重取顶部窗口（后端单次上限 500），
 * 与已有节点按 key 合并去重后再本地排序，位置不乱、新事件也能立刻出现。</p>
 */
async function refreshLoaded() {
  if (loading.value || loadingMore.value) return
  const want = Math.min(Math.max(nodes.value.length, PAGE_SIZE), MAX_LIMIT)
  try {
    const page = await fetchPage(0, want)
    const fresh = new Map(page.nodes.map((n) => [n.key, n]))
    const merged = page.nodes.concat(nodes.value.filter((n) => !fresh.has(n.key)))
    nodes.value = merged.sort(compareNodeDesc).slice(0, Math.max(page.nodes.length, nodes.value.length))
    total.value = page.total
    hasMore.value = page.hasMore || merged.length < page.total
    await nextTick()
    ensureObserver()
  } catch (_) { /* 静默：SSE 刷新失败不影响已有内容，用户手动加载即可 */ }
}

// ===== 滚动续加载（IntersectionObserver） =====

/** 观察器（root = 时间线滚动容器 .tl-body，不是 window） */
let observer = null
/** 当前被观察的哨兵元素（分支切换后 DOM 会换新节点，需重新 observe） */
let observedEl = null

/**
 * 确保哨兵已挂上观察器。
 *
 * <p>为什么不用 Element Plus 的 `v-infinite-scroll`：element-plus 2.14.4 里该指令已标记弃用
 * （源码 `useDeprecated({ scope:'ElInfiniteScroll', version:'3.0.0', replacement:'the el-scrollbar
 * infinite scroll' })`），且 2.14.4 的 el-scrollbar 尚无 end-reached 事件；本页滚动容器是普通
 * div（.tl-body, overflow-y:auto），用原生 IntersectionObserver + root 指定容器最稳且零依赖。</p>
 */
function ensureObserver() {
  const root = bodyRef.value
  const target = sentinelRef.value
  if (!root || !target || typeof IntersectionObserver === 'undefined') return
  if (observedEl === target) return
  if (!observer) {
    observer = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting)) loadMore()
    }, { root, rootMargin: '300px 0px' })
  } else {
    observer.disconnect()
  }
  observer.observe(target)
  observedEl = target
}

/** 卸掉观察器（离开页面/容器被销毁） */
function teardownObserver() {
  if (observer) {
    observer.disconnect()
    observer = null
  }
  observedEl = null
}

/** 选中节点打开详情 */
function selectNode(n) {
  selected.value = n
  detailDrawer.value = true
}

/** 打开新增事件对话框 */
function openAddEvent() {
  addDialog.title = ''
  addDialog.content = ''
  addDialog.visible = true
}

/** 手动新增事件（持久化到 actor_event，source=manual） */
async function doAddEvent() {
  if (!addDialog.content.trim()) return ElMessage.warning('请填写事件内容')
  addDialog.loading = true
  try {
    await createProjectEvent(projectId, {
      title: addDialog.title.trim() || '项目事件',
      content: addDialog.content.trim()
    })
    ElMessage.success('事件已添加到时间线')
    addDialog.visible = false
    await refreshLoaded()
  } catch (e) { ElMessage.error(e.message || '添加失败') }
  finally { addDialog.loading = false }
}

/** AI 从世界观识别生成事件 */
async function doAiGenerate() {
  aiGenerating.value = true
  try {
    const event = await aiGenerateProjectEvent(projectId)
    ElMessage.success(`AI 已生成事件：${event.title || '世界事件'}`)
    await refreshLoaded()
  } catch (e) { ElMessage.error(e.message || 'AI 识别失败') }
  finally { aiGenerating.value = false }
}

onMounted(async () => {
  try { clock.value = await fetchWorldClock(projectId) } catch (_) { clock.value = null }
  await load()
  connectWorldEvents()
})

/**
 * 世界事件流 SSE：收到 world-event 自动重新加载时间线（B1，行动/居民/事件/演化实时上轴；
 * 统一订阅型客户端——断线自动重连[指数退避]，心跳监听，页面离开关闭）
 */
let worldSub = null
function connectWorldEvents() {
  try {
    worldSub = createSubscription({
      url: worldEventsStreamUrl(projectId),
      events: [SSE_EVENTS.WORLD_EVENT, SSE_EVENTS.ERROR],
      heartbeatTimeoutMs: 45000, // 15s 心跳 ×3：超时视为死连自动重连
      onEvent: (name) => { if (name === SSE_EVENTS.WORLD_EVENT) refreshLoaded() }
    })
  } catch (_) { /* 连接失败静默（页面仍可手动刷新） */ }
}

onBeforeUnmount(() => {
  if (worldSub) { worldSub.close(); worldSub = null }
  teardownObserver()
})
</script>

<style scoped>
/* 限宽走统一令牌；高度取外壳内容区 100%（替代 calc(100vh - 140px) 魔法值） */
.timeline-view { width: 100%; max-width: var(--content-max-w, 1440px); margin: 0 auto; display: flex; flex-direction: column; gap: 14px; height: 100%; min-height: 0; }

/* 工具栏 */
.tl-toolbar { background: var(--bg-layer-1); border-radius: var(--radius-lg); border: 1px solid var(--border-light); padding: 10px 16px; display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.toolbar-left { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.type-filter { display: inline-flex; }
.toolbar-right { display: flex; align-items: center; gap: 10px; }
.now-tag { font-weight: 600; }

/* 主体 */
.tl-body { flex: 1; background: var(--bg-layer-1); border-radius: var(--radius-lg); border: 1px solid var(--border-light); padding: 20px 28px; overflow-y: auto; min-height: 0; }
.tl-empty { display: flex; align-items: center; justify-content: center; height: 100%; }
.tl-axis-wrap { position: relative; }
.tl-axis { border-left: 2px solid var(--border-l2); margin-left: 12px; padding-left: 24px; display: flex; flex-direction: column; }
.tl-group { display: flex; flex-direction: column; }
.tl-group-label { font-size: 0.8rem; font-weight: 700; color: var(--text-secondary); margin: 14px 0 8px; padding: 2px 8px; background: var(--bg-light); border-radius: 4px; display: inline-block; width: fit-content; }
.tl-node { position: relative; margin-bottom: 12px; cursor: pointer; }
/* 节点横线指向时间轴 */
.tl-node::before { content: ''; position: absolute; left: -24px; top: 18px; width: 24px; height: 2px; background: var(--border-l2); }
.tl-node::after { content: ''; position: absolute; left: -28px; top: 14px; width: 12px; height: 12px; border-radius: 50%; border: 3px solid var(--bg-layer-1); box-shadow: 0 0 0 2px var(--text-placeholder); }
.tl-node.kind-event::after { background: #e6a23c; box-shadow: 0 0 0 2px #e6a23c; }
.tl-node.kind-action::after { background: #409eff; box-shadow: 0 0 0 2px #409eff; }
.tl-node.kind-memory::after { background: #67c23a; box-shadow: 0 0 0 2px #67c23a; }
.tl-node.kind-crowd::after, .tl-node.kind-common::after { background: #67c23a; box-shadow: 0 0 0 2px #67c23a; }
.tl-node.kind-world::after { background: #e6a23c; box-shadow: 0 0 0 2px #e6a23c; }
.tl-node.kind-evolution::after { background: #f56c6c; box-shadow: 0 0 0 2px #f56c6c; }
.tl-node.kind-world_frame::after { background: #e6a23c; box-shadow: 0 0 0 2px #e6a23c; }
.tl-node.kind-manual::after { background: #909399; box-shadow: 0 0 0 2px #909399; }
.tl-node.kind-system::after { background: #909399; box-shadow: 0 0 0 2px #909399; }
.tl-node.kind-roleplay::after { background: #409eff; box-shadow: 0 0 0 2px #409eff; }
/* V2.0 批次二：680 硬编码 → 长文阅读列令牌（时间线卡片承载事件正文，与正文列同源，CJK 40 字/行） */
.tl-card { border: 1px solid var(--border-light); border-radius: 10px; padding: 10px 14px; transition: box-shadow .2s, border-color .2s; max-width: var(--prose-measure, 40em); }
.tl-card:hover { box-shadow: var(--card-shadow); border-color: var(--brand-primary); }
.tl-card-head { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.src-tag { margin-left: 0; }
.tl-card-time { font-size: 0.72rem; color: var(--text-placeholder); margin-left: auto; }
.tl-card-real { font-size: 0.68rem; color: var(--text-placeholder); margin-bottom: 2px; }
.tl-card-title { font-size: 0.92rem; font-weight: 600; color: var(--text-primary); }
.tl-card-text { font-size: 0.82rem; color: var(--text-secondary); line-height: 1.6; margin-top: 4px; white-space: pre-wrap; word-break: break-word; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
/* 横红线：标记「现在」（轴顶——最新的节点紧贴它，向下才是历史） */
.tl-now-line { position: relative; margin-bottom: 10px; margin-left: -26px; padding-left: 26px; }
.tl-now-line::before { content: ''; position: absolute; left: 0; right: 0; top: 6px; border-top: 2px solid var(--el-color-danger); }
.tl-now-label { position: relative; z-index: 1; display: inline-block; background: var(--el-color-danger); color: #fff; font-size: 0.72rem; padding: 2px 10px; border-radius: 4px; font-weight: 600; }

/* 续加载提示条（也是 IntersectionObserver 的观测哨兵：滚到它就自动拉下一批） */
.tl-foot { display: flex; align-items: center; justify-content: center; gap: 12px; padding: 14px 0 6px; border-top: 1px dashed var(--border-light); margin-top: 12px; }
.tl-count { font-size: 0.76rem; color: var(--text-placeholder); }
.tl-end { font-size: 0.76rem; color: var(--text-placeholder); }

/* 详情抽屉 */
.dt-grid { display: flex; flex-direction: column; gap: 10px; margin-bottom: 16px; }
.dt-item { display: flex; gap: 8px; }
.dt-item .k { color: var(--text-secondary); font-size: 0.8rem; width: 70px; flex-shrink: 0; }
.dt-item .v { color: var(--text-primary); font-size: 0.85rem; }
.dt-content { font-size: 0.9rem; color: var(--text-regular); line-height: 1.8; white-space: pre-wrap; word-break: break-word; background: var(--bg-layer-2); border-radius: 8px; padding: 14px; }
</style>
