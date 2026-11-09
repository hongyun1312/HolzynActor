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
  <div class="wf-tab">
    <div class="wf-filters">
      <el-select v-model="filters.status" size="default" class="wf-f-status" @change="reload">
        <el-option label="生效中" value="active" />
        <el-option label="已过期" value="expired" />
        <el-option label="已作废" value="void" />
        <el-option label="全部状态" value="" />
      </el-select>
      <el-select v-model="filters.factType" size="default" clearable placeholder="全部类型" class="wf-f-type" @change="reload">
        <el-option v-for="t in FACT_TYPES" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>
      <el-input v-model="filters.keyword" clearable placeholder="搜索事实键 / 名称 / 状态文本"
        class="wf-f-kw" @clear="reload" @keyup.enter="reload" />
      <el-button type="primary" @click="reload">
        <el-icon><HIcon name="Search" /></el-icon>&nbsp;查询
      </el-button>
      <span class="wf-spacer"></span>
      <span class="wf-count">{{ rows.length }} 条</span>
    </div>

    <div v-loading="loading" class="wf-body">
      <el-table :data="rows" row-key="id" height="100%" empty-text="暂无世界事实（对话承诺、传闻、设施状态等写入后会出现在这里）"
        @row-click="openFact">
        <el-table-column label="事实" min-width="250">
          <template #default="{ row }">
            <div class="wf-name">{{ row.name || '（未命名事实）' }}</div>
            <div class="wf-mono wf-key">{{ row.factKey }}</div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="92" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ typeLabel(row.factType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="82" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)" effect="light">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="可见性" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="visTag(row.visibility)" effect="plain">{{ visLabel(row.visibility) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="权威" width="82" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.authority === 'engine' ? 'warning' : 'info'" effect="plain">
              {{ row.authority === 'engine' ? '引擎' : '大脑' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="62" align="center" />
        <el-table-column label="地点" width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.locationName || '—' }}</template>
        </el-table-column>
        <el-table-column label="更新" width="132" align="center">
          <template #default="{ row }">{{ fmtTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="72" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click.stop="openFact(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-drawer v-model="detailVisible" size="580px" :with-header="false" class="wf-drawer">
      <template v-if="current">
        <div class="wf-drawer-head">
          <div>
            <div class="wf-eyebrow">WORLD FACT</div>
            <h2 class="wf-drawer-title">{{ current.name || '未命名事实' }}</h2>
            <div class="wf-mono wf-drawer-key">{{ current.factKey }}</div>
          </div>
          <el-button text size="small" @click="detailVisible = false">关闭</el-button>
        </div>

        <div class="wf-tags">
          <el-tag size="small" effect="plain">{{ typeLabel(current.factType) }}</el-tag>
          <el-tag size="small" :type="statusTag(current.status)" effect="light">{{ statusLabel(current.status) }}</el-tag>
          <el-tag size="small" :type="visTag(current.visibility)" effect="plain">可见性：{{ visLabel(current.visibility) }}</el-tag>
          <el-tag size="small" :type="current.authority === 'engine' ? 'warning' : 'info'" effect="plain">
            权威：{{ current.authority === 'engine' ? '引擎' : '大脑' }}
          </el-tag>
          <el-tag size="small" effect="plain">v{{ current.version }}</el-tag>
        </div>

        <div class="wf-detail-grid">
          <div class="wf-detail-item"><span class="wf-detail-label">地点</span><span>{{ current.locationName || '—' }}</span></div>
          <div class="wf-detail-item"><span class="wf-detail-label">来源事件</span><span class="wf-mono">{{ current.sourceEventId ?? '—' }}</span></div>
          <div class="wf-detail-item"><span class="wf-detail-label">生效</span><span class="wf-mono">{{ current.validFromGameSecond ?? '—' }}</span></div>
          <div class="wf-detail-item"><span class="wf-detail-label">失效</span><span class="wf-mono">{{ current.validToGameSecond ?? '永久' }}</span></div>
          <div class="wf-detail-item"><span class="wf-detail-label">更新时间</span><span>{{ fmtTime(current.updatedAt) }}</span></div>
        </div>

        <div class="wf-block">
          <div class="wf-block-title">当前状态</div>
          <div class="wf-state-text">{{ current.stateText || '—' }}</div>
        </div>

        <div v-if="prettyAttrs" class="wf-block">
          <div class="wf-block-title">属性</div>
          <pre class="wf-pre">{{ prettyAttrs }}</pre>
        </div>

        <div class="wf-block">
          <div class="wf-block-title">版本链 <span class="wf-dim">{{ versions.length }} 条</span></div>
          <div v-if="versionsLoading" class="wf-dim">加载中…</div>
          <div v-else-if="!versions.length" class="wf-dim">暂无版本日志</div>
          <el-timeline v-else class="wf-timeline">
            <el-timeline-item v-for="v in versions" :key="v.id"
              :timestamp="`v${v.version} · ${opLabel(v.op)} · ${v.writer === 'engine' ? '引擎' : '大脑'}`" placement="top">
              <div v-if="v.afterJson" class="wf-version-row"><span class="wf-version-label">后</span><pre class="wf-pre is-inline">{{ pretty(v.afterJson) }}</pre></div>
              <div v-if="v.beforeJson" class="wf-version-row"><span class="wf-version-label">前</span><pre class="wf-pre is-inline">{{ pretty(v.beforeJson) }}</pre></div>
              <div v-if="v.deltaJson" class="wf-version-row"><span class="wf-version-label">差</span><pre class="wf-pre is-inline">{{ pretty(v.deltaJson) }}</pre></div>
              <div class="wf-version-meta">
                <span v-if="v.gameSecond != null" class="wf-mono">gs {{ v.gameSecond }}</span>
                <span v-if="v.eventId != null" class="wf-mono">event #{{ v.eventId }}</span>
                <span v-if="v.idempotencyKey" class="wf-mono wf-idem">{{ v.idempotencyKey }}</span>
              </div>
            </el-timeline-item>
          </el-timeline>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
/**
 * 世界事实台账（项目记忆页第三个 Tab）。
 *
 * <p>只读浏览：按状态/类型/关键词筛选，点行看当前状态、属性与完整版本链。
 * 权威列直接暴露 brain/engine，避免 UI 把「大脑写的事实」和「引擎结算的事实」混成一种。</p>
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchWorldFacts, fetchWorldFactVersions } from '@/shared/api/worldMemory'

const props = defineProps({
  projectId: { type: Number, required: true }
})

const FACT_TYPES = [
  { value: 'facility', label: '设施' },
  { value: 'route', label: '路线' },
  { value: 'stock', label: '库存' },
  { value: 'prop', label: '物品' },
  { value: 'land', label: '土地' },
  { value: 'contract', label: '契约' },
  { value: 'reputation', label: '声望' },
  { value: 'office', label: '职位' },
  { value: 'secret', label: '秘密' }
]

const loading = ref(false)
const rows = ref([])
const filters = reactive({ status: 'active', factType: '', keyword: '' })

const detailVisible = ref(false)
const current = ref(null)
const versions = ref([])
const versionsLoading = ref(false)

const prettyAttrs = computed(() => pretty(current.value?.attributesJson))

onMounted(reload)

async function reload() {
  loading.value = true
  try {
    const params = { limit: 200 }
    if (filters.status) params.status = filters.status
    if (filters.factType) params.factType = filters.factType
    if (filters.keyword?.trim()) params.keyword = filters.keyword.trim()
    rows.value = (await fetchWorldFacts(props.projectId, params)) || []
  } catch (e) {
    ElMessage.error(e?.message || '世界事实加载失败')
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function openFact(row) {
  current.value = row
  detailVisible.value = true
  versions.value = []
  versionsLoading.value = true
  try {
    versions.value = (await fetchWorldFactVersions(props.projectId, row.id)) || []
  } catch (e) {
    ElMessage.error(e?.message || '版本链加载失败')
  } finally {
    versionsLoading.value = false
  }
}

function typeLabel(t) { return FACT_TYPES.find(x => x.value === t)?.label || t || '事实' }
function statusLabel(s) { return { active: '生效', expired: '过期', void: '作废' }[s] || s || '—' }
function statusTag(s) { return { active: 'success', expired: 'info', void: 'danger' }[s] || 'info' }
function visLabel(v) { return { public: '公开', location: '同地点', private: '私密' }[v] || v || '—' }
function visTag(v) { return { public: 'success', location: 'warning', private: 'danger' }[v] || 'info' }
function opLabel(op) { return { create: '创建', update: '更新', expire: '过期', void: '作废' }[op] || op || '—' }

function fmtTime(v) {
  if (!v) return '—'
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return String(v)
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function pretty(text) {
  if (!text) return ''
  try { return JSON.stringify(JSON.parse(text), null, 2) } catch { return String(text) }
}
</script>

<style scoped>
.wf-tab { display: flex; flex-direction: column; gap: 10px; height: 100%; min-height: 0; }
.wf-filters { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.wf-f-status { width: 120px; }
.wf-f-type { width: 124px; }
.wf-f-kw { width: 280px; }
.wf-spacer { flex: 1; }
.wf-count { font-size: 12px; color: var(--text-tertiary, #9ca3af); }
.wf-body { flex: 1; min-height: 0; background: var(--bg-layer-1, #fff); border: 1px solid var(--border-l1, #e5e7eb); border-radius: 10px; padding: 8px; overflow: hidden; }
.wf-name { font-size: 13px; color: var(--text-primary, #111827); line-height: 1.4; }
.wf-key { color: var(--text-tertiary, #9ca3af); margin-top: 2px; }
.wf-mono { font-family: ui-monospace, Consolas, monospace; font-size: 11px; }
.wf-drawer :deep(.el-drawer__body) { padding: 0; overflow-y: auto; }
.wf-drawer-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 20px 22px 12px; border-bottom: 1px solid var(--border-l1, #e5e7eb); }
.wf-eyebrow { font-size: 10px; letter-spacing: .16em; color: var(--text-tertiary, #9ca3af); }
.wf-drawer-title { margin: 4px 0 4px; font-size: 18px; line-height: 1.4; color: var(--text-primary, #111827); }
.wf-drawer-key { color: var(--text-secondary, #6b7280); }
.wf-tags { display: flex; gap: 6px; flex-wrap: wrap; padding: 14px 22px 0; }
.wf-detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px 16px; padding: 14px 22px 0; }
.wf-detail-item { display: flex; gap: 8px; font-size: 12.5px; color: var(--text-primary, #111827); min-width: 0; }
.wf-detail-label { color: var(--text-secondary, #6b7280); flex: none; min-width: 64px; }
.wf-block { padding: 18px 22px 0; }
.wf-block-title { font-size: 13px; font-weight: 700; color: var(--text-primary, #111827); margin-bottom: 6px; }
.wf-state-text { font-size: 13px; line-height: 1.65; color: var(--text-regular, #374151); white-space: pre-wrap; }
.wf-pre { margin: 6px 0 0; padding: 10px 12px; background: var(--bg-hover, #f9fafb); border-radius: 8px; font-family: ui-monospace, Consolas, monospace; font-size: 11.5px; line-height: 1.55; color: var(--text-regular, #374151); white-space: pre-wrap; word-break: break-word; max-height: 220px; overflow: auto; }
.wf-pre.is-inline { margin-top: 4px; max-height: 140px; }
.wf-dim { font-size: 11px; font-weight: 400; color: var(--text-tertiary, #9ca3af); }
.wf-timeline { padding-top: 4px; }
.wf-version-row { display: flex; gap: 6px; align-items: flex-start; }
.wf-version-label { flex: none; font-size: 10px; color: var(--text-tertiary, #9ca3af); padding-top: 8px; }
.wf-version-row .wf-pre { flex: 1; min-width: 0; }
.wf-version-meta { display: flex; gap: 10px; flex-wrap: wrap; margin-top: 4px; font-size: 10.5px; color: var(--text-tertiary, #9ca3af); }
.wf-idem { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%; }
</style>
