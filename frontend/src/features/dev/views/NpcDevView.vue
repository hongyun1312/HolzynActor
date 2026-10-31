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
    title="角色与状态"
    desc="角色列表、当前状态与需求调参。"
  >
    <template #actions>
      <el-tag v-if="state.lastAt" size="small" effect="plain">数据于 {{ state.lastAt }}</el-tag>
    </template>
  </PageHeader>

  <div v-if="state.error" class="dev-notice is-error">
    <el-icon class="dev-notice-icon"><HIcon name="Warning" /></el-icon>
    <div>{{ state.error }}</div>
  </div>

  <PageTabs v-model="tab">
    <!-- ① 角色列表 -->
    <el-tab-pane label="角色列表" name="list" :lazy="true">
      <DevCard
        title="全部角色"
        :desc="`${rows.length} 位角色（特殊型 ${specialCount} / 普通型 ${rows.length - specialCount}）。点击任意一行进入全息详情。`"
        flush
      >
        <template #actions>
          <el-input v-model="keyword" size="small" placeholder="搜索姓名/职业/地点" clearable style="width: 200px">
            <template #prefix><el-icon><HIcon name="Search" /></el-icon></template>
          </el-input>
        </template>
        <el-table
          :data="filteredRows"
          class="dev-table"
          size="small"
          max-height="560"
          highlight-current-row
          @row-click="openDetail"
        >
          <el-table-column label="姓名" width="140" fixed>
            <template #default="{ row }">
              <span class="dev-npc-name">{{ row.name }}</span>
              <el-tag v-if="row.isProtagonist" size="small" type="warning" effect="plain" class="dev-npc-flag">主角</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="typeLabel" label="类型" width="80" />
          <el-table-column prop="title" label="头衔" width="120" show-overflow-tooltip />
          <el-table-column prop="occupation" label="职业" width="110" show-overflow-tooltip />
          <el-table-column prop="stage" label="阶段" width="80" show-overflow-tooltip />
          <el-table-column prop="brainTier" label="脑力档" width="90" show-overflow-tooltip />
          <el-table-column prop="location" label="位置" width="130" show-overflow-tooltip />
          <el-table-column prop="currentActivity" label="当前活动" min-width="140" show-overflow-tooltip />
          <el-table-column label="紧急度" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.urgencyLevel != null" size="small" :type="urgencyType(row.urgencyLevel)" effect="plain">
                {{ row.urgencyLevel }}
              </el-tag>
              <span v-else class="dev-toolbar-note">—</span>
            </template>
          </el-table-column>
          <el-table-column label="主导情绪" width="110" show-overflow-tooltip>
            <template #default="{ row }">{{ row.dominantEmotion || row.mood || '—' }}</template>
          </el-table-column>
          <el-table-column label="PAD" width="150">
            <template #default="{ row }">
              <span v-if="row.emotionPleasure == null" class="dev-toolbar-note">—</span>
              <span v-else class="dev-mono">
                {{ fmt(row.emotionPleasure) }} / {{ fmt(row.emotionArousal) }} / {{ fmt(row.emotionDominance) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="饥饿 · 精力" width="130">
            <template #default="{ row }">
              <span v-if="row.hunger == null && row.energy == null" class="dev-toolbar-note">无状态行</span>
              <span v-else class="dev-mono">{{ row.hunger ?? '—' }} · {{ row.energy ?? '—' }}</span>
            </template>
          </el-table-column>
        </el-table>
        <div class="dev-toolbar dev-mt dev-tab-pad">
          <span class="dev-toolbar-note">提示：状态行缺失（显示「无状态行」）说明该角色还没被引擎初始化过——通常意味着它从没进入过 AI 判定。</span>
        </div>
      </DevCard>
    </el-tab-pane>

    <!-- ② 状态调参 -->
    <el-tab-pane label="状态调参" name="tuning" :lazy="true">
      <DevCard
        title="需求衰减与情绪参数"
        :desc="`共 ${tuningRows.length} 项（已改 ${tuningOverridden} 项）。这里的值实时写进运行中的引擎，改完立刻影响所有角色的饥饿/精力/情绪曲线。`"
        flush
      >
        <template #actions>
          <el-input v-model="tuningKeyword" size="small" placeholder="搜索键/标签" clearable style="width: 180px" />
          <el-button size="small" @click="reloadTuning">重新读取</el-button>
        </template>
        <el-table :data="filteredTuning" class="dev-table" size="small" max-height="560">
          <el-table-column prop="group" label="分组" width="110" show-overflow-tooltip />
          <el-table-column prop="label" label="参数" width="180" show-overflow-tooltip />
          <el-table-column label="键" width="220" show-overflow-tooltip>
            <template #default="{ row }"><span class="dev-mono">{{ row.key }}</span></template>
          </el-table-column>
          <el-table-column label="默认值" width="100">
            <template #default="{ row }"><span class="dev-mono">{{ row.defaultValue }}</span></template>
          </el-table-column>
          <el-table-column label="生效值" width="180">
            <template #default="{ row }">
              <el-input
                v-model="drafts[row.key]"
                size="small"
                :placeholder="String(row.defaultValue ?? '')"
                @keyup.enter="saveTuning(row)"
              />
            </template>
          </el-table-column>
          <el-table-column label="覆盖" width="70">
            <template #default="{ row }">
              <el-tag v-if="row.overridden" size="small" type="warning" effect="plain">已改</el-tag>
              <span v-else class="dev-toolbar-note">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button link size="small" type="primary" @click="saveTuning(row)">保存</el-button>
              <el-button link size="small" :disabled="!row.overridden" @click="resetTuning(row)">重置</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!tuningRows.length" class="dev-toolbar-note dev-tab-pad">未取到调参项（注册表为空？）</div>
      </DevCard>
    </el-tab-pane>
  </PageTabs>
</template>

<script setup>
/**
 * 开发者控制台 · 角色与状态域（列表 + 调参）。
 *
 * <p>列表页把「这个角色有没有状态行」显式标出来：状态行缺失是最常见的「角色不动」根因，
 * 靠人眼看空白单元格是发现不了的。</p>
 */
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/shared/layout/PageHeader.vue'
import PageTabs from '@/shared/layout/PageTabs.vue'
import HIcon from '@/shared/components/HIcon.vue'
import DevCard from '../components/DevCard.vue'
import { fetchDevNpcs, fetchNpcStateConfig, saveNpcStateConfig, resetNpcStateConfig } from '@/shared/api/dev'
import { useDevPage } from '../useDevPage'

const router = useRouter()
const tab = ref('list')
const keyword = ref('')
const tuningKeyword = ref('')
const tuningRows = ref([])
const drafts = reactive({})

const { state, load } = useDevPage(async ({ worldId }) => {
  const [npcs, tuning] = await Promise.all([
    fetchDevNpcs(worldId).catch(() => []),
    fetchNpcStateConfig().catch(() => ({ rows: [] }))
  ])
  tuningRows.value = tuning?.rows || []
  for (const row of tuningRows.value) {
    if (drafts[row.key] === undefined) drafts[row.key] = String(row.value ?? '')
  }
  return { npcs }
})

const rows = computed(() => state.data?.npcs || [])
const specialCount = computed(() => rows.value.filter((r) => r.typeLabel === '特殊型').length)
const tuningOverridden = computed(() => tuningRows.value.filter((r) => r.overridden).length)

/** 列表按关键字过滤（姓名/职业/地点/活动） */
const filteredRows = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return rows.value
  return rows.value.filter((r) => [r.name, r.title, r.occupation, r.location, r.currentActivity]
    .some((v) => String(v || '').toLowerCase().includes(kw)))
})

/** 调参按关键字过滤（键/标签/分组） */
const filteredTuning = computed(() => {
  const kw = tuningKeyword.value.trim().toLowerCase()
  if (!kw) return tuningRows.value
  return tuningRows.value.filter((r) => [r.key, r.label, r.group]
    .some((v) => String(v || '').toLowerCase().includes(kw)))
})

/** 数值格式化（最多 2 位小数） */
function fmt(v) {
  const n = Number(v)
  return Number.isFinite(n) ? String(Math.round(n * 100) / 100) : '—'
}

/** 紧急度 → 标签色 */
function urgencyType(level) {
  const n = Number(level)
  if (!Number.isFinite(n)) return 'info'
  if (n >= 3) return 'danger'
  if (n >= 2) return 'warning'
  return 'success'
}

/** 进入全息详情 */
function openDetail(row) {
  router.push(`/dev/npcs/${row.id}`)
}

/** 重新读取调参项 */
async function reloadTuning() {
  await load(true)
  ElMessage.success('已重新读取')
}

/** 保存单个调参项（危险：实时生效） */
async function saveTuning(row) {
  const value = drafts[row.key]
  if (value == null || String(value).trim() === '') {
    ElMessage.warning('值不能为空（要恢复默认请点「重置」）')
    return
  }
  try {
    await saveNpcStateConfig(row.key, String(value).trim())
    ElMessage.success(`${row.label || row.key} 已生效`)
    await load(true)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '保存失败')
  }
}

/** 重置为默认值 */
async function resetTuning(row) {
  try {
    await ElMessageBox.confirm(
      `把「${row.label || row.key}」恢复默认值 ${row.defaultValue}？`,
      '恢复默认',
      { type: 'warning', confirmButtonText: '恢复', cancelButtonText: '取消' }
    )
  } catch (_) {
    return
  }
  try {
    await resetNpcStateConfig(row.key)
    drafts[row.key] = String(row.defaultValue ?? '')
    ElMessage.success('已恢复默认')
    await load(true)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '重置失败')
  }
}
</script>

<style scoped>
.dev-mt { margin-top: var(--space-3, 12px); }
.dev-tab-pad { padding: 0 24px var(--density-card-pad, 16px); }
.dev-npc-name { font-weight: 600; color: var(--text-primary); }
.dev-npc-flag { margin-left: 6px; }
</style>
