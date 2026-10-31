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
  <!-- V2.0：页面骨架化——原 .archive-page 内联 max-width:1200px（与全站其它内容列不统一），
       改由 PageShell 承载，页面不再自写限宽。
       2026-10 布局审计：本页是宽表（8 列 + 操作列），且与同属项目空间的其它页共用侧栏导航，
       content（1200）在 1920 屏与 1440 档页面之间会跳 120px，故升到 wide（1440）。 -->
  <PageShell width="wide" class="archive-page">
    <!-- 标题 + 操作 -->
    <div class="ar-top">
      <div class="ar-title-wrap">
        <h3 class="ar-title">
          <el-icon><HIcon name="FolderOpened" /></el-icon>&nbsp;记忆归档（L4 冷存储）
        </h3>
        <span class="ar-sub">超龄/被覆盖/反思降级/手动归档的记忆原文冷存，可按需检索/导出/还原；<b>不参与对话/决策注入</b>。</span>
      </div>
      <div class="ar-ops">
        <el-button size="small" :loading="scanning" @click="doScanAge" :title="'扫描并归档超龄（重要度≥阈值且超 N 游戏日未复核 + 长期无访问）的长期记忆'">
          <el-icon><HIcon name="Refresh" /></el-icon>&nbsp;手动触发超龄扫描
        </el-button>
        <el-button size="small" type="success" plain @click="doExport('json')" :loading="exporting">
          <el-icon><HIcon name="Download" /></el-icon>&nbsp;导出 JSON
        </el-button>
        <el-button size="small" type="primary" plain @click="doExport('markdown')" :loading="exporting">
          <el-icon><HIcon name="Download" /></el-icon>&nbsp;导出 Markdown
        </el-button>
      </div>
    </div>

    <!-- 过滤条 -->
    <div class="ar-panel tech-card">
      <div class="ar-filters">
        <el-select v-model="filters.characterRef" clearable filterable placeholder="全部角色" class="ar-f-filter ar-f-char" @change="reload">
          <el-option v-for="c in characters" :key="c.id" :label="c.name" :value="npcRef(c.id)" />
        </el-select>
        <el-select v-model="filters.type" clearable placeholder="全部类型" class="ar-f-filter" @change="reload">
          <el-option v-for="(label, key) in typeLabels" :key="key" :label="label" :value="key" />
        </el-select>
        <el-select v-model="filters.reason" clearable placeholder="全部原因" class="ar-f-filter" @change="reload">
          <el-option v-for="(label, key) in reasonLabels" :key="key" :label="label" :value="key" />
        </el-select>
        <el-input v-model="filters.keyword" clearable placeholder="搜索归档内容" class="ar-f-filter ar-f-kw" @clear="reload" @keyup.enter="reload">
          <template #prefix><el-icon><HIcon name="Search" /></el-icon></template>
        </el-input>
        <el-button size="default" type="primary" @click="reload">
          <el-icon><HIcon name="Search" /></el-icon>&nbsp;查询
        </el-button>
      </div>

      <!-- 归档列表 -->
      <div v-loading="loading" class="ar-body">
        <el-table :data="rows" class="ar-table" empty-text="暂无归档条目（在角色页记忆/核心块上点击「归档」，或由超龄扫描/版本链自动归档）" row-key="id">
          <el-table-column label="内容" min-width="240" show-overflow-tooltip>
            <template #default="{ row }">
              <div class="ar-content">{{ row.content }}</div>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="typeTag(row.type)" effect="plain">{{ typeLabels[row.type] || row.type }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="原因" width="110" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="reasonTag(row.archiveReason)" effect="light">{{ reasonLabels[row.archiveReason] || row.archiveReason }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="归属" width="130" align="center">
            <template #default="{ row }">
              <span class="ar-ref">{{ refLabel(row.characterRef, characters) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="重要度" width="80" align="center">
            <template #default="{ row }">
              <el-tag size="small" effect="light">{{ row.importanceAtArchive ?? '—' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="归档时间" width="150" align="center">
            <template #default="{ row }">{{ fmtTime(row.archivedAt) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.restored === 1 ? 'success' : 'info'" effect="plain">
                {{ row.restored === 1 ? '已还原' : '未还原' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" align="center" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text type="primary" :disabled="row.restored === 1" @click="doRestore(row)">还原</el-button>
              <el-button size="small" text type="danger" @click="doDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <!-- 分页 -->
        <div class="ar-pager">
          <el-pagination
            v-model:current-page="page"
            v-model:page-size="size"
            :total="total"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next, jumper"
            @current-change="load"
            @size-change="reload"
          />
        </div>
      </div>
    </div>
  </PageShell>
</template>

<script setup>
/**
 * 记忆归档页。
 * <p>职责：展示/检索项目级<b>记忆归档</b>（L4 冷存储）——按角色/类型/原因/关键词过滤分页 + 还原 + 删除 + 导出
 * （JSON 全量 / Markdown 阅读版）+ 手动触发超龄归档扫描。归档原文保留、可检索可还原，<b>不参与日常注入</b>。</p>
 * <p>数据来源：/api/worlds/{id}/archives（列表）/ /archives/{id}/restore（还原）/
 * /archives/{id}（删除）/ /api/worlds/{id}/archives/export（导出）/ .../scan-age（超龄扫描）。
 * 角色筛选下拉复用 /api/worlds/{id}/characters。</p>
 */
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchCharacters, fetchArchives, restoreArchive, deleteArchive, scanAgeArchive, exportArchives
} from '@/shared/api'
// 统一身份引用
import { npcRef, npcIdOf } from '@/shared/npcRef'
// V2.0：统一页面骨架（限宽/居中/纵向节奏只此一处）
import PageShell from '@/shared/layout/PageShell.vue'

const route = useRoute()
const projectId = Number(route.params.id)

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const scanning = ref(false)
const exporting = ref(false)
const characters = ref([])

const filters = reactive({ characterRef: '', type: '', reason: '', keyword: '' })

// 标签映射
const typeLabels = {
  semantic: '语义', episodic: '情节', reflection: '反思', plan: '计划', core: '核心块', working: '工作项'
}
const reasonLabels = {
  age: '超龄', superseded: '被覆盖', downgraded: '反思降级', evicted: '预算淘汰', manual: '手动归档'
}

function typeTag(t) {
  return { semantic: 'primary', episodic: 'success', reflection: 'warning', plan: 'info', core: 'danger', working: 'info' }[t] || ''
}
function reasonTag(r) {
  return { age: 'info', superseded: 'warning', downgraded: 'success', evicted: 'danger', manual: 'primary' }[r] || 'info'
}
function refLabel(ref, chars) {
  if (!ref) return '项目级'
  // ：归档归属引用统一 npc:{id}（单主表；特殊型/普通型共用一套 id 空间）
  const id = npcIdOf(ref)
  if (id != null) {
    const c = chars.find(x => x.id === id)
    return c ? c.name : ref
  }
  return ref
}
function fmtTime(t) {
  if (!t) return '—'
  const d = new Date(t)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

async function loadChars() {
  try { characters.value = await fetchCharacters(projectId) } catch (_) { characters.value = [] }
}

async function load() {
  loading.value = true
  try {
    const params = { page: page.value, size: size.value }
    if (filters.characterRef) params.characterRef = filters.characterRef
    if (filters.type) params.type = filters.type
    if (filters.reason) params.reason = filters.reason
    if (filters.keyword && filters.keyword.trim()) params.keyword = filters.keyword.trim()
    const data = await fetchArchives(projectId, params)
    rows.value = data.list || []
    total.value = data.total || 0
  } catch (e) {
    ElMessage.error(e.message || '加载归档失败')
  } finally { loading.value = false }
}

function reload() {
  page.value = 1
  load()
}

async function doRestore(row) {
  try {
    await ElMessageBox.confirm(`确认还原归档条目？将按来源表新建一条记录（${refLabel(row.characterRef, characters)} · ${typeLabels[row.type] || row.type}），原归档标记为已还原。`, '还原确认', { type: 'warning' })
  } catch (_) { return }
  try {
    const r = await restoreArchive(row.id)
    ElMessage.success(`已还原到 ${r.sourceTable === 'actor_core_memory' ? '核心块' : '长期记忆'}（id=${r.createdId}）`)
    await load()
  } catch (e) {
    ElMessage.error(e.message || '还原失败')
  }
}

async function doDelete(row) {
  try {
    await ElMessageBox.confirm('确认删除该归档条目（软删，超保留期后将物理清理）？', '删除确认', { type: 'warning' })
  } catch (_) { return }
  try {
    await deleteArchive(row.id)
    ElMessage.success('归档条目已删除')
    await load()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

async function doScanAge() {
  scanning.value = true
  try {
    const r = await scanAgeArchive(projectId)
    ElMessage.success(`超龄扫描完成，归档 ${r.archived} 条`)
    await load()
  } catch (e) {
    ElMessage.error(e.message || '超龄扫描失败')
  } finally { scanning.value = false }
}

/** 触发浏览器下载（blob，导出文件用） */
function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(new Blob([blob]))
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

async function doExport(format) {
  exporting.value = true
  try {
    const params = { format }
    if (filters.characterRef) params.characterRef = filters.characterRef
    const blob = await exportArchives(projectId, params)
    const ext = format === 'markdown' ? 'md' : 'json'
    downloadBlob(blob, `memory-archive-${projectId}.${ext}`)
    ElMessage.success('导出已开始下载')
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  } finally { exporting.value = false }
}

onMounted(() => { loadChars(); load() })
</script>

<style scoped>
/* V2.0：限宽交给 PageShell（原 max-width:1200px 已移除，避免全站并存两套栅格约定） */
.archive-page { display: flex; flex-direction: column; gap: 14px; }
.ar-top { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.ar-title-wrap { display: flex; flex-direction: column; gap: 4px; }
.ar-title { margin: 0; font-size: 1.05rem; font-weight: 700; color: var(--text-primary); display: flex; align-items: center; }
.ar-sub { font-size: 0.8rem; color: var(--text-secondary); }
.ar-ops { display: flex; gap: 8px; flex-wrap: wrap; }
/* 过滤条 + 表格 + 分页收进一张卡片：改前它们直接压在壁纸上（表头/行没有承载面，
   与「项目记忆」等同样是表格的页面不同，玻璃主题下正文可读性最差）。 */
.ar-panel { padding: 14px 16px 10px; display: flex; flex-direction: column; gap: 12px; }
.ar-filters { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.ar-f-filter { width: 150px; }
.ar-f-char { width: 170px; }
.ar-f-kw { width: 220px; flex: 1 1 220px; }
.ar-body { display: flex; flex-direction: column; gap: 12px; }
.ar-table { width: 100%; }
.ar-content { font-size: 0.85rem; color: var(--text-primary); }
.ar-ref { font-size: 0.8rem; color: var(--text-secondary); }
.ar-pager { display: flex; justify-content: flex-end; }
</style>
