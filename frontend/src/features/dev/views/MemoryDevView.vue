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
    title="记忆"
    desc="记忆健康度、注入预览、项目记忆与漂移度量。"
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
    <!-- ① 记忆内核 -->
    <el-tab-pane label="记忆内核" name="core" :lazy="true">
      <DevCard title="记忆健康度" desc="选一个角色看它的记忆库健康：条目数、过期率、向量化状态。">
        <template #actions>
          <el-select v-model="characterRef" size="small" filterable placeholder="选择角色" style="width: 200px" @change="reloadFocus">
            <el-option v-for="n in npcs" :key="n.ref" :label="n.name" :value="n.ref" />
          </el-select>
          <el-button size="small" @click="reloadFocus">刷新</el-button>
        </template>
        <DevAuto v-if="memoryHealth" :value="memoryHealth" />
        <div v-else class="dev-notice">选一个角色查看记忆健康度（不选则显示全项目口径）。</div>
      </DevCard>
    </el-tab-pane>

    <!-- ② 注入预览 -->
    <el-tab-pane label="注入预览" name="injection" :lazy="true">
      <DevCard title="注入预览" desc="给定一句输入，看引擎会为它检索并注入哪些记忆——「角色为什么突然提起那件事」的答案在这里。">
        <template #actions>
          <el-select v-model="characterRef" size="small" filterable placeholder="选择角色" style="width: 190px">
            <el-option v-for="n in npcs" :key="n.ref" :label="n.name" :value="n.ref" />
          </el-select>
          <el-input v-model="query" size="small" placeholder="试一句输入（如：你还记得上次的雨吗）" style="width: 280px" @keyup.enter="loadInjection" />
          <el-button size="small" :loading="injecting" @click="loadInjection">预览</el-button>
        </template>
        <DevAuto v-if="injectionPreview" :value="injectionPreview" />
        <div v-else class="dev-toolbar-note">输入一句输入后点「预览」</div>
      </DevCard>
      <DevCard v-if="injectRoute" title="注入路由" desc="记忆从哪个层被召回（核心 / 长期 / 工作 / 场景）。">
        <DevAuto :value="injectRoute" />
      </DevCard>
    </el-tab-pane>

    <!-- ③ 项目记忆 -->
    <el-tab-pane label="项目记忆" name="world" :lazy="true">
      <DevCard title="项目记忆概览" desc="世界级事实与实体状态：所有角色共享的那部分认知。">
        <DevAuto v-if="worldMemory" :value="worldMemory" />
        <div v-else class="dev-toolbar-note">未返回项目记忆概览</div>
      </DevCard>
      <DevCard title="编年史" desc="按时间记录的世界大事，供角色「回忆」时引用。">
        <DevAuto v-if="chronicle" :value="chronicle" />
        <div v-else class="dev-toolbar-note">未返回编年史</div>
      </DevCard>
      <DevCard title="实体状态" desc="世界里的实体（人物/地点/物品）及其公开状态。">
        <DevAuto v-if="entities" :value="entities" />
        <div v-else class="dev-toolbar-note">未返回实体状态</div>
      </DevCard>
    </el-tab-pane>

    <!-- ④ 漂移度量 -->
    <el-tab-pane label="漂移度量" name="drift" :lazy="true">
      <DevCard title="人格漂移度量" desc="目标一致率 / 矛盾率 / Token 成本 + 按日趋势 + 按角色排行——「角色越演越不像自己」的量化证据。">
        <template #actions>
          <el-select v-model="driftDays" size="small" style="width: 120px" @change="reloadDrift">
            <el-option label="近 7 天" :value="7" />
            <el-option label="近 30 天" :value="30" />
            <el-option label="近 90 天" :value="90" />
          </el-select>
          <el-button size="small" @click="reloadDrift">刷新</el-button>
        </template>
        <DevAuto v-if="drift" :value="drift" />
        <div v-else class="dev-toolbar-note">未返回漂移度量</div>
      </DevCard>
    </el-tab-pane>
  </PageTabs>
</template>

<script setup>
/**
 * 开发者控制台 · 记忆域。
 *
 * <p>记忆是「角色像真人」的核心：一个真人记得住昨天的事、也会忘记无关细节。
 * 这里的四个子页分别覆盖：记得多少（健康度）、此刻捞出了什么（注入预览）、
 * 世界共同记得什么（项目记忆）、以及有没有越来越不像自己（漂移）。</p>
 */
import { computed, ref, watch } from 'vue'
import PageHeader from '@/shared/layout/PageHeader.vue'
import PageTabs from '@/shared/layout/PageTabs.vue'
import HIcon from '@/shared/components/HIcon.vue'
import DevCard from '../components/DevCard.vue'
import DevAuto from '../components/DevAuto.vue'
import {
  fetchMemoryHealth, fetchInjectionPreview, fetchInjectRoute, fetchWorldMemoryOverview,
  fetchChronicle, fetchWorldEntities, fetchDriftMetrics, fetchDevNpcs
} from '@/shared/api/dev'
import { useDevPage } from '../useDevPage'
import { devContext } from '../devContext'

const tab = ref('core')
const characterRef = ref('')
const query = ref('')
const injecting = ref(false)
const injectionPreview = ref(null)
const injectRoute = ref(null)
const driftDays = ref(30)
const drift = ref(null)
const memoryHealth = ref(null)

const { state: page, load } = useDevPage(async ({ worldId }) => {
  const safe = (p) => p.catch(() => null)
  const [npcs, worldMemory, chronicle, entities, driftData] = await Promise.all([
    safe(fetchDevNpcs(worldId)),
    safe(fetchWorldMemoryOverview(worldId)),
    safe(fetchChronicle(worldId, { limit: 50 })),
    safe(fetchWorldEntities(worldId, { limit: 50 })),
    safe(fetchDriftMetrics({ projectId: worldId, days: driftDays.value }))
  ])
  return { npcs, worldMemory, chronicle, entities, driftData }
})

const npcs = computed(() => page.data?.npcs || [])
const worldMemory = computed(() => page.data?.worldMemory)
const chronicle = computed(() => page.data?.chronicle)
const entities = computed(() => page.data?.entities)

// 首屏数据到达后回填漂移结果；默认选中第一个角色并立即取它的记忆健康度
watch(() => page.data?.driftData, (v) => { if (v) drift.value = v })
watch(npcs, (list) => {
  if (!characterRef.value && list.length) characterRef.value = list[0].ref
}, { immediate: true })
watch(characterRef, () => { reloadFocus() })

/** 拉当前聚焦角色的记忆健康度 */
async function reloadFocus() {
  const id = devContext.worldId
  if (!id) return
  try {
    memoryHealth.value = await fetchMemoryHealth(id, characterRef.value || undefined)
  } catch (e) {
    memoryHealth.value = { error: e?.message || '读取失败' }
  }
}

/** 注入预览（按当前角色 + 测试输入） */
async function loadInjection() {
  const id = devContext.worldId
  if (!id) return
  injecting.value = true
  try {
    const params = { characterRef: characterRef.value || undefined, query: query.value || undefined }
    const [preview, route] = await Promise.all([
      fetchInjectionPreview(id, params).catch((e) => ({ error: e?.message })),
      fetchInjectRoute(id, params).catch(() => null)
    ])
    injectionPreview.value = preview
    injectRoute.value = route
  } finally {
    injecting.value = false
  }
}

/** 重新拉漂移度量 */
async function reloadDrift() {
  const id = devContext.worldId
  if (!id) return
  try {
    drift.value = await fetchDriftMetrics({ projectId: id, days: driftDays.value })
  } catch (e) {
    drift.value = { error: e?.message || '读取失败' }
  }
}

void load
</script>

