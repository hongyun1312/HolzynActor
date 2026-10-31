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
  <div class="world-edit-view">
    <!-- 头部：返回世界详情 + 标题 + 保存 -->
    <PageHeader
      :title="`修改世界 · ${project?.name || ''}`"
      desc="与「世界详情」同款分节展示，各区块就地编辑；地点详情以表格维护（增删改查）。保存后覆盖更新当前版本。"
    >
      <template #actions>
        <el-button type="primary" :loading="saving" @click="saveWorld">
          <el-icon><HIcon name="Check" /></el-icon>&nbsp;保存世界观
        </el-button>
        <el-button @click="$router.push(`/world/${projectId}/world`)">
          <el-icon><HIcon name="Back" /></el-icon>&nbsp;返回世界详情
        </el-button>
      </template>
    </PageHeader>

    <!-- 分节编辑：复用与「新建世界第 2 步」「手动填写」同一份 WorldSettingForm，
         避免三处各写一套几乎相同的分节表单（新增字段时三处漏改） -->
    <!-- V2.0 §5.6：这是加载态，不是空态——不再用空态框写「加载中…」 -->
    <div v-if="!worldForm.name && !loadingDone" v-loading="true" class="empty-card tech-card edit-loading" />
    <div v-else class="edit-body">
      <div class="world-card tech-card">
        <WorldSettingForm
          :model="worldForm"
          :project-id="projectId"
          title="世界观设定"
          desc="各分节就地编辑；「地点详情」以表格维护（AI 提取 + 增删改查）。保存后覆盖更新当前版本。"
        />
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 世界详情修改页。
 * <p>职责：世界观专属编辑页——复用共享组件 {@link WorldSettingForm}（与「新建世界第 2 步」同一份表单，
 * 左置分节：基本信息/地理/势力/规则/文化/历史/补充 + 地点详情表格维护），避免三处各写一套分节表单。
 * 保存走现有 /api/worlds/{id}/world-setting 覆盖更新接口；地点表由表格组件独立保存。</p>
 */
import { ref, reactive, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { fetchProject, fetchWorldSetting, saveWorldSetting } from '@/shared/api'
import WorldSettingForm from '@/shared/components/WorldSettingForm.vue'
import PageHeader from '@/shared/layout/PageHeader.vue'

const route = useRoute()
const router = useRouter()
const projectId = Number(route.params.id)

const project = ref(null)
const saving = ref(false)
const loadingDone = ref(false)
const worldForm = reactive({ name: '', genre: '', tone: 'standard', era: '', geography: '', factions: '', magicSystem: '', culture: '', history: '', freeText: '' })

/** 加载项目详情 + 世界观（回填表单） */
async function loadAll() {
  try { project.value = await fetchProject(projectId) } catch (_) { /* 忽略 */ }
  try {
    const w = await fetchWorldSetting(projectId)
    if (w) {
      Object.assign(worldForm, {
        name: w.name, genre: w.genre, tone: w.tone || 'standard', era: w.era, geography: w.geography, factions: w.factions,
        magicSystem: w.magicSystem, culture: w.culture, history: w.history, freeText: w.freeText
      })
    }
  } catch (_) { /* 无世界观时保持空表单 */ }
  loadingDone.value = true
}

/** 保存世界观（覆盖更新） */
async function saveWorld() {
  if (!worldForm.name.trim()) return ElMessage.warning('请填写世界观名称')
  saving.value = true
  try {
    await saveWorldSetting(projectId, { ...worldForm })
    ElMessage.success('世界观已保存（覆盖更新）')
    router.push(`/world/${projectId}/world`)
  } catch (e) { ElMessage.error(e.message || '保存失败') }
  finally { saving.value = false }
}

onMounted(loadAll)
</script>

<style scoped>
.world-edit-view { width: 100%; max-width: var(--content-max-w, 1440px); margin: 0 auto; }
/* 页头与左置标签样式由全局类/PageTabs 统一提供 */
.empty-card { padding: 40px 0; }
/* 加载占位需要有高度，否则 v-loading 的遮罩只有一行高（视觉上像页面坏了） */
.edit-loading { min-height: 240px; }
.edit-body { min-height: 360px; }
.world-card { padding: 20px 24px; }
.section-form { padding-top: 4px; }
.section-title-row { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.section-title { font-weight: 700; color: var(--text-primary); }
.section-desc { font-size: 0.8rem; color: var(--text-secondary); }
</style>
