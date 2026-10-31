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
  <PageHeader
    title="AI 调用与 Prompt"
    desc="AI 调用记录、用量成本、Prompt 模板与场景路由。"
  >
    <template #actions>
      <el-tag v-if="page.lastAt" size="small" effect="plain">数据于 {{ page.lastAt }}</el-tag>
    </template>
  </PageHeader>

  <div v-if="page.error" class="dev-notice is-error">
    <el-icon class="dev-notice-icon"><HIcon name="Warning" /></el-icon>
    <div>{{ page.error }}</div>
  </div>

  <PageTabs v-model="tab">
    <!-- ① 调用列表 -->
    <el-tab-pane label="调用列表" name="calls" :lazy="true">
      <DevCard
        title="AI 调用"
        :desc="`共 ${calls.total} 条（第 ${calls.page}/${totalPages} 页）。这是最真实的证据来源：Prompt 里有什么，模型就只知道什么。`"
        flush
      >
        <template #actions>
          <el-select v-model="status" size="small" clearable placeholder="全部状态" style="width: 120px" @change="reloadCalls">
            <el-option label="成功" value="success" />
            <el-option label="失败" value="failed" />
            <el-option label="降级" value="degraded" />
          </el-select>
          <el-select v-model="sort" size="small" style="width: 130px" @change="reloadCalls">
            <el-option label="按时间" value="time" />
            <el-option label="按耗时" value="duration" />
            <el-option label="按 Token" value="tokens" />
          </el-select>
          <el-input v-model="keyword" size="small" placeholder="搜索关键词" clearable style="width: 180px" @keyup.enter="reloadCalls" />
          <el-checkbox v-model="failedFirst" size="small" @change="reloadCalls">失败优先</el-checkbox>
          <el-button size="small" @click="reloadCalls">刷新</el-button>
        </template>

        <el-table
          :data="calls.items"
          class="dev-table"
          size="small"
          max-height="440"
          highlight-current-row
          @row-click="openCall"
        >
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column label="场景" width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ row.sceneName || row.scene || '—' }}</template>
          </el-table-column>
          <el-table-column label="角色" width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ row.characterName || '—' }}</template>
          </el-table-column>
          <el-table-column label="模型" width="170" show-overflow-tooltip>
            <template #default="{ row }"><span class="dev-mono">{{ row.model || '—' }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="statusType(row.status)" effect="plain">{{ row.status || '—' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="Token 入/出" width="120">
            <template #default="{ row }"><span class="dev-mono">{{ row.tokenIn ?? '—' }}/{{ row.tokenOut ?? '—' }}</span></template>
          </el-table-column>
          <el-table-column label="缓存 命中/未命中" width="140">
            <template #default="{ row }"><span class="dev-mono">{{ row.cacheHit ?? 0 }}/{{ row.cacheMiss ?? 0 }}</span></template>
          </el-table-column>
          <el-table-column label="耗时" width="90">
            <template #default="{ row }">{{ row.durationMs != null ? `${row.durationMs} ms` : '—' }}</template>
          </el-table-column>
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
        </el-table>
        <div class="dev-toolbar dev-tab-pad dev-mt">
          <el-pagination
            v-model:current-page="pageNo"
            :page-size="pageSize"
            :total="calls.total"
            layout="prev, pager, next, sizes, total"
            :page-sizes="[20, 50, 100]"
            size="small"
            @current-change="reloadCalls"
            @size-change="onSizeChange"
          />
        </div>
      </DevCard>

      <DevCard v-if="callDetail" :title="`调用 #${callDetail.id} 详情`" :desc="`${callDetail.sceneName || callDetail.scene || ''} · ${callDetail.model || ''} · ${callDetail.status || ''}`">
        <template #actions>
          <el-button size="small" @click="callDetail = null">关闭</el-button>
        </template>
        <div class="dev-grid-2">
          <DevJson :value="callDetail.systemPrompt" title="systemPrompt" :max-height="300" />
          <DevJson :value="callDetail.userPrompt" title="userPrompt" :max-height="300" />
        </div>
        <div class="dev-grid-2 dev-mt">
          <DevJson :value="callDetail.response" title="response" :max-height="300" />
          <DevJson :value="callDetail.detailJson" title="detailJson" :max-height="300" />
        </div>
        <div v-if="callDetail.errorMessage" class="dev-notice is-error dev-mt">
          <el-icon class="dev-notice-icon"><HIcon name="Warning" /></el-icon>
          <span>{{ callDetail.errorMessage }}</span>
        </div>
      </DevCard>
    </el-tab-pane>

    <!-- ② 用量与成本 -->
    <el-tab-pane label="用量与成本" name="usage" :lazy="true">
      <DevCard title="用量聚合" :desc="`最近 ${usageDays} 天：按天 / 场景 / 项目聚合的 Token 与调用次数。`">
        <template #actions>
          <el-select v-model="usageDays" size="small" style="width: 120px" @change="reloadUsage">
            <el-option label="近 7 天" :value="7" />
            <el-option label="近 30 天" :value="30" />
            <el-option label="近 90 天" :value="90" />
          </el-select>
        </template>
        <DevAuto v-if="usage" :value="usage" />
        <div v-else class="dev-toolbar-note">未返回用量统计</div>
      </DevCard>
    </el-tab-pane>

    <!-- ③ Prompt 工程 -->
    <el-tab-pane label="Prompt 工程" name="prompt" :lazy="true">
      <DevCard title="Prompt 指标" desc="每个场景的模板版本、命中率、平均 Token——模板漂移的第一现场。">
        <DevAuto v-if="promptStats" :value="promptStats" />
        <div v-else class="dev-toolbar-note">未返回 Prompt 统计</div>
      </DevCard>
      <DevCard title="模板注册表" desc="PromptSpecRegistry 登记的全部模板（改模板必须同步计数测试，这里能核对）。">
        <DevAuto v-if="promptSpecs" :value="promptSpecs" />
        <div v-else class="dev-toolbar-note">未返回模板清单</div>
      </DevCard>
      <DevCard title="告警" desc="指标越界时的告警记录。">
        <DevAuto v-if="promptAlerts" :value="promptAlerts" />
        <div v-else class="dev-toolbar-note">无告警</div>
      </DevCard>
    </el-tab-pane>

    <!-- ④ 护栏与路由 -->
    <el-tab-pane label="护栏与路由" name="policy" :lazy="true">
      <DevCard title="护栏状态" desc="内容/成本/频率护栏的当前状态与阈值。">
        <DevAuto v-if="guardRails" :value="guardRails" />
        <div v-else class="dev-toolbar-note">未返回护栏状态</div>
      </DevCard>
      <DevCard title="场景路由表" desc="哪个场景走哪个模型 / 是否走 决策模型 判定层。">
        <DevAuto v-if="sceneRouting" :value="sceneRouting" />
        <div v-else class="dev-toolbar-note">未返回路由表</div>
      </DevCard>
      <DevCard title="供应商配置（脱敏）" desc="Key 已打码。用于确认「当前实际用的是哪个供应商/模型」。">
        <DevAuto v-if="debugConfig" :value="debugConfig" />
        <div v-else class="dev-toolbar-note">未返回配置</div>
      </DevCard>
    </el-tab-pane>
  </PageTabs>
</template>

<script setup>
/**
 * 开发者控制台 · AI 调用与 Prompt 域。
 *
 * <p>调用详情刻意把 systemPrompt / userPrompt / response / detailJson 四块并排：
 * 「模型为什么不按人设说话」只能是 prompt 里少了什么或多了什么，分开看才能定位。</p>
 *
 * <p>分页走服务端：`/api/debug/ai-calls` 支持 page/size/status/keyword/sort/failedFirst。</p>
 */
import { computed, ref, watch } from 'vue'
import PageHeader from '@/shared/layout/PageHeader.vue'
import PageTabs from '@/shared/layout/PageTabs.vue'
import HIcon from '@/shared/components/HIcon.vue'
import DevCard from '../components/DevCard.vue'
import DevAuto from '../components/DevAuto.vue'
import DevJson from '../components/DevJson.vue'
import {
  fetchAiCalls, fetchAiCallDetail, fetchUsageStats, fetchPromptSpecs, fetchPromptStats,
  fetchPromptAlerts, fetchGuardRails, fetchSceneRouting, fetchDebugConfig
} from '@/shared/api/dev'
import { useDevPage, formatTime } from '../useDevPage'

const tab = ref('calls')
const status = ref('')
const sort = ref('time')
const keyword = ref('')
const failedFirst = ref(false)
const pageNo = ref(1)
const pageSize = ref(20)
const callDetail = ref(null)
const usageDays = ref(30)
const usage = ref(null)

const { state: page, load } = useDevPage(async ({ worldId }) => {
  const safe = (p) => p.catch(() => null)
  const [calls, usageStats, specs, stats, alerts, rails, routing, config] = await Promise.all([
    safe(fetchAiCalls({
      projectId: worldId, status: status.value || undefined, keyword: keyword.value || undefined,
      sort: sort.value, failedFirst: failedFirst.value, page: pageNo.value, size: pageSize.value
    })),
    safe(fetchUsageStats(usageDays.value)),
    safe(fetchPromptSpecs()),
    safe(fetchPromptStats()),
    safe(fetchPromptAlerts()),
    safe(fetchGuardRails()),
    safe(fetchSceneRouting()),
    safe(fetchDebugConfig())
  ])
  return { calls, usageStats, specs, stats, alerts, rails, routing, config }
})

const calls = computed(() => {
  const c = page.data?.calls || {}
  return { items: c.items || [], total: c.total || 0, page: c.page || 1, size: c.size || pageSize.value }
})
const totalPages = computed(() => Math.max(1, Math.ceil(calls.value.total / (calls.value.size || 1))))
const promptSpecs = computed(() => page.data?.specs)
const promptStats = computed(() => page.data?.stats)
const promptAlerts = computed(() => page.data?.alerts)
const guardRails = computed(() => page.data?.rails)
const sceneRouting = computed(() => page.data?.routing)
const debugConfig = computed(() => page.data?.config)

// 用语聚合随窗口变化单独拉（首屏值同时回填）
watch(() => page.data?.usageStats, (v) => { if (v) usage.value = v })

/** 状态 → 标签色 */
function statusType(s) {
  if (s === 'success') return 'success'
  if (s === 'failed') return 'danger'
  if (s === 'degraded') return 'warning'
  return 'info'
}

/** 重新拉列表（筛选/排序/翻页变化） */
async function reloadCalls() {
  await load(true)
}

/** 每页条数变化：回到第一页再拉 */
function onSizeChange() {
  pageNo.value = 1
  reloadCalls()
}

/** 打开单条调用详情 */
async function openCall(row) {
  try {
    callDetail.value = await fetchAiCallDetail(row.id)
  } catch (e) {
    callDetail.value = { id: row.id, errorMessage: e?.message || '读取失败' }
  }
}

/** 重新拉用量 */
async function reloadUsage() {
  try {
    usage.value = await fetchUsageStats(usageDays.value)
  } catch (e) {
    usage.value = { error: e?.message || '读取失败' }
  }
}
</script>

<style scoped>
.dev-mt { margin-top: var(--space-3, 12px); }
.dev-tab-pad { padding: 0 24px var(--density-card-pad, 16px); }
</style>
