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
  <div class="world-memory-page">
    <!-- 标题 + 操作（统一页头：图标 + 标题 + 说明 + 右侧操作） -->
    <PageHeader icon="Notebook">
      <template #default>项目记忆（世界编年史 · 实体状态）</template>
      <template #desc>
        项目记忆 = 世界视角公共记忆（双系统并列）：<b>编年史 L2</b> 记录「世界发生了什么」
        （重要事件即时提炼 + 游戏日 01:00 每日批量提炼 + 巩固补漏）；<b>实体状态 L3</b> 记录
        地点/人物/势力当前状态（Zep 式失效更新，可查历史版本链）。所有角色经【世界近闻】【世界动态】注入知晓。
      </template>
      <template #actions>
        <el-button size="small" type="primary" plain @click="openAddChronicle">
          <el-icon><HIcon name="Plus" /></el-icon>&nbsp;补充编年史
        </el-button>
        <el-button size="small" type="success" plain @click="openUpdateEntity">
          <el-icon><HIcon name="EditPen" /></el-icon>&nbsp;更新实体状态
        </el-button>
      </template>
    </PageHeader>

    <!-- 概览卡片 -->
    <div v-if="overview" class="wm-cards">
      <div class="wm-card"><div class="wm-card-label">编年史条目</div><div class="wm-card-value">{{ overview.chronicleCount }}</div></div>
      <div class="wm-card"><div class="wm-card-label">实体当前态</div><div class="wm-card-value">{{ overview.activeEntityCount }}</div></div>
      <div class="wm-card"><div class="wm-card-label">实体总版本行</div><div class="wm-card-value">{{ overview.entityTotalCount }}</div></div>
      <div class="wm-card"><div class="wm-card-label">编年史归档</div><div class="wm-card-value">{{ overview.archiveCount }}</div></div>
      <div class="wm-card wm-card-wide">
        <div class="wm-card-label">最新编年史</div>
        <div class="wm-card-text" :title="overview.latestChronicle?.content">
          {{ overview.latestChronicle ? `第${overview.latestChronicle.gameDay + 1}日 · ${overview.latestChronicle.title}` : '（暂无）' }}
        </div>
      </div>
      <div class="wm-card wm-card-wide">
        <div class="wm-card-label">近期实体变化</div>
        <div class="wm-card-text">
          <span v-for="e in overview.latestEntityChanges" :key="e.id" class="wm-change-chip" :title="e.currentState">
            {{ typeLabel(e.entityType) }}·{{ e.name }}：{{ e.recentChange }}
          </span>
          <span v-if="!overview.latestEntityChanges?.length" class="wm-dim">（暂无）</span>
        </div>
      </div>
    </div>

    <!-- Tab：编年史 / 实体状态 -->
    <PageTabs v-model="tab">
      <!-- ==================== 编年史 ==================== -->
      <el-tab-pane label="世界编年史" name="chronicle">
        <div class="wm-filters">
          <el-input-number v-model="filters.fromDay" :min="0" placeholder="起始游戏日" controls-position="right" class="wm-f-day" @change="reloadChronicle" />
          <span class="wm-f-sep">—</span>
          <el-input-number v-model="filters.toDay" :min="0" placeholder="结束游戏日" controls-position="right" class="wm-f-day" @change="reloadChronicle" />
          <el-input v-model="filters.entityKey" clearable placeholder="关联实体键（如 location:村口牌坊）" class="wm-f-kw" @clear="reloadChronicle" @keyup.enter="reloadChronicle" />
          <el-select v-model="filters.importance" clearable placeholder="重要度≥" class="wm-f-imp" @change="reloadChronicle">
            <el-option v-for="n in [2, 3, 4, 5]" :key="n" :label="`≥${n}`" :value="n" />
          </el-select>
          <el-button size="default" type="primary" @click="reloadChronicle">
            <el-icon><HIcon name="Search" /></el-icon>&nbsp;查询
          </el-button>
        </div>
        <div v-loading="loading" class="wm-body">
          <el-table :data="chronicles" empty-text="暂无编年史条目（重要事件即时提炼 / 每日 01:00 批量提炼后自动生成）" row-key="id">
            <el-table-column label="游戏日" width="80" align="center">
              <template #default="{ row }">第{{ row.gameDay + 1 }}日</template>
            </el-table-column>
            <el-table-column label="标题" width="200" show-overflow-tooltip>
              <template #default="{ row }">{{ row.title }}</template>
            </el-table-column>
            <el-table-column label="内容" min-width="300" show-overflow-tooltip>
              <template #default="{ row }">{{ row.content }}</template>
            </el-table-column>
            <el-table-column label="重要度" width="80" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="impTag(row.importance)" effect="light">{{ row.importance }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="涉及实体" width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ (row.involvedEntities || []).join('、') || '—' }}</template>
            </el-table-column>
            <el-table-column label="来源" width="140" align="center">
              <template #default="{ row }">
                <span class="wm-src-cell">
                  <el-tag size="small" effect="plain">{{ sourceLabel(row.source) }}</el-tag>
                  <!-- 编年史标签：宏观社会演化事件（kind=macro）→「宏观」徽标 -->
                  <el-tag v-if="(row.tags || []).includes('macro')" size="small" type="warning" effect="light">宏观</el-tag>
                </span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" align="center" fixed="right">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="openDetail(row)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="wm-pager">
            <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
              :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next, jumper"
              @current-change="loadChronicle" @size-change="reloadChronicle" />
          </div>
        </div>
      </el-tab-pane>

      <!-- ==================== 实体状态 ==================== -->
      <el-tab-pane label="实体状态（三域）" name="entity">
        <div class="wm-filters">
          <el-select v-model="filters.type" clearable placeholder="全部类型" class="wm-f-imp" @change="reloadEntity">
            <el-option label="地点" value="location" />
            <el-option label="人物" value="person" />
            <el-option label="势力" value="faction" />
          </el-select>
          <el-input-number v-model="filters.asOfGameDay" :min="0" placeholder="按时间点查询（游戏日，空=当前态）"
            controls-position="right" class="wm-f-day" @change="reloadEntity" />
          <el-button size="default" type="primary" @click="reloadEntity">
            <el-icon><HIcon name="Search" /></el-icon>&nbsp;查询
          </el-button>
        </div>
        <div v-loading="loading" class="wm-body">
          <el-table :data="entities" empty-text="暂无实体状态（对话抽取路由 / 编年史实体更新判定自动生成）" row-key="id">
            <el-table-column label="类型" width="70" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="typeTag(row.entityType)" effect="plain">{{ typeLabel(row.entityType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="实体键" width="190" show-overflow-tooltip>
              <template #default="{ row }"><span class="wm-mono">{{ row.entityKey }}</span></template>
            </el-table-column>
            <el-table-column label="当前状态" min-width="280" show-overflow-tooltip>
              <template #default="{ row }">{{ row.currentState }}</template>
            </el-table-column>
            <el-table-column label="最近变化" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.recentChange || '—' }}</template>
            </el-table-column>
            <el-table-column label="有效窗口" width="150" align="center">
              <template #default="{ row }">
                <span class="wm-mono">{{ row.validFromGameDay }} → {{ row.validToGameDay ?? '至今' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="来源" width="90" align="center">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{ provLabel(row.provenance) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110" align="center" fixed="right">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="openEntityHistory(row)">版本链</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="wm-pager">
            <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
              :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next, jumper"
              @current-change="loadEntity" @size-change="reloadEntity" />
          </div>
        </div>
      </el-tab-pane>
    </PageTabs>

    <!-- 编年史详情抽屉 -->
    <el-drawer v-model="detailVisible" size="460px" :title="detail?.title || '编年史详情'">
      <div v-if="detail" class="wm-detail">
        <div class="wm-detail-row"><span class="wm-detail-label">游戏日</span><span>第 {{ detail.gameDay + 1 }} 日{{ detail.gameHour != null ? ` · ${String(Math.floor(detail.gameHour % 24)).padStart(2, '0')} 时` : '' }}</span></div>
        <div class="wm-detail-row"><span class="wm-detail-label">重要度</span><el-tag size="small" :type="impTag(detail.importance)" effect="light">{{ detail.importance }}</el-tag></div>
        <div class="wm-detail-row"><span class="wm-detail-label">来源</span>
          <el-tag size="small" effect="plain">{{ sourceLabel(detail.source) }}</el-tag>
          <el-tag v-if="(detail.tags || []).includes('macro')" size="small" type="warning" effect="light">宏观</el-tag>
        </div>
        <div class="wm-detail-block"><div class="wm-detail-label">内容</div><div class="wm-detail-text">{{ detail.content }}</div></div>
        <div class="wm-detail-block"><div class="wm-detail-label">涉及实体</div><div class="wm-detail-text">{{ (detail.involvedEntities || []).join('、') || '—' }}</div></div>
        <div class="wm-detail-block"><div class="wm-detail-label">关联事件 id</div><div class="wm-detail-text wm-mono">{{ (detail.eventIds || []).join('、') || '—' }}</div></div>
        <div class="wm-detail-block"><div class="wm-detail-label">天气快照</div><div class="wm-detail-text wm-mono">{{ detail.weatherSnapshot || '—（第二轮时间季节系统填充）' }}</div></div>
      </div>
    </el-drawer>

    <!-- 实体版本链抽屉 -->
    <el-drawer v-model="historyVisible" size="520px" :title="`实体版本链 · ${historyEntity?.entityKey || ''}`">
      <div v-if="historyEntity" class="wm-detail">
        <div class="wm-detail-block"><div class="wm-detail-label">当前状态</div><div class="wm-detail-text">{{ historyEntity.currentState }}</div></div>
        <el-timeline class="wm-timeline">
          <el-timeline-item v-for="h in historyRows" :key="h.id" :timestamp="`第 ${h.validFromGameDay + 1} 日 起${h.validToGameDay != null ? ` 至 第 ${h.validToGameDay + 1} 日` : '（至今）'}`" placement="top">
            <div class="wm-tl-state">{{ h.currentState }}</div>
            <div class="wm-tl-meta">
              <el-tag size="small" effect="plain">{{ provLabel(h.provenance) }}</el-tag>
              <span class="wm-mono">id={{ h.id }}{{ h.supersedesId != null ? ` · supersedes=${h.supersedesId}` : '' }}</span>
            </div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-drawer>

    <!-- 手动补充编年史 -->
    <el-dialog v-model="addVisible" title="补充编年史条目" width="520px">
      <el-form label-width="90px">
        <el-form-item label="游戏日"><el-input-number v-model="addForm.gameDay" :min="0" controls-position="right" style="width: 100%" /></el-form-item>
        <el-form-item label="标题"><el-input v-model="addForm.title" placeholder="留空=取内容前 40 字" /></el-form-item>
        <el-form-item label="内容" required><el-input v-model="addForm.content" type="textarea" :rows="4" placeholder="发生了什么+影响" /></el-form-item>
        <el-form-item label="重要度"><el-rate v-model="addForm.importance" :max="5" /></el-form-item>
        <el-form-item label="涉及实体"><el-input v-model="addForm.involvedEntities" placeholder='JSON 数组，如 ["location:村口牌坊"]' /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doAddChronicle">保存</el-button>
      </template>
    </el-dialog>

    <!-- 手动更新实体状态 -->
    <el-dialog v-model="updateVisible" title="更新实体状态（Zep 式失效更新）" width="520px">
      <el-form label-width="90px">
        <el-form-item label="实体键" required><el-input v-model="entityForm.entityKey" placeholder="location:村口牌坊 / person:npc:110 / faction:青禾村" /></el-form-item>
        <el-form-item label="当前状态" required><el-input v-model="entityForm.currentState" type="textarea" :rows="3" placeholder="变化后的世界视角公共状态" /></el-form-item>
        <el-form-item label="最近变化"><el-input v-model="entityForm.recentChange" placeholder="一句话变化描述（可空）" /></el-form-item>
        <el-form-item label="游戏日"><el-input-number v-model="entityForm.gameDay" :min="0" controls-position="right" style="width: 100%" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="updateVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doUpdateEntity">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 项目记忆页。
 * <p>职责：展示/管理<b>项目记忆</b>（世界视角公共记忆，与角色记忆双系统并列）——</p>
 * <ul>
 * <li>概览卡片：编年史条数/实体当前态/实体总版本行/归档数/最新条目/近期实体变化；</li>
 * <li>世界编年史 Tab：按游戏日区间/关联实体/重要度过滤分页 + 详情抽屉 + 手动补充；</li>
 * <li>实体状态 Tab：按类型/按时间点（as-of）查询 + 版本链历史抽屉 + 手动更新（触发失效更新）。</li>
 * </ul>
 * <p>数据来源：/api/worlds/{id}/world-memory/*（chronicle 查询/详情/补充、entities 查询/历史/更新、overview）。</p>
 */
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  fetchWorldMemoryOverview, fetchChronicles, fetchChronicleDetail, addChronicle,
  fetchEntities, fetchEntityHistory, updateEntity
} from '@/shared/api'
import PageHeader from '@/shared/layout/PageHeader.vue'
import PageTabs from '@/shared/layout/PageTabs.vue'

const route = useRoute()
const projectId = Number(route.params.id)

const tab = ref('chronicle')
const loading = ref(false)
const saving = ref(false)

// 概览
const overview = ref(null)

// 编年史列表
const chronicles = ref([])
const page = ref(1)
const size = ref(20)
const total = ref(0)
const filters = reactive({ fromDay: null, toDay: null, entityKey: '', importance: null, type: null, asOfGameDay: null })

// 实体列表
const entities = ref([])

// 详情/历史
const detailVisible = ref(false)
const detail = ref(null)
const historyVisible = ref(false)
const historyEntity = ref(null)
const historyRows = ref([])

// 手动补充/更新
const addVisible = ref(false)
const addForm = reactive({ gameDay: null, title: '', content: '', importance: 3, involvedEntities: '' })
const updateVisible = ref(false)
const entityForm = reactive({ entityKey: '', currentState: '', recentChange: '', gameDay: null })

onMounted(() => {
  loadOverview()
  reloadChronicle()
})

async function loadOverview() {
  try { overview.value = await fetchWorldMemoryOverview(projectId) } catch { overview.value = null }
}

async function loadChronicle() {
  loading.value = true
  try {
    const params = { page: page.value, size: size.value }
    if (filters.fromDay != null) params.fromDay = filters.fromDay
    if (filters.toDay != null) params.toDay = filters.toDay
    if (filters.entityKey?.trim()) params.entityKey = filters.entityKey.trim()
    if (filters.importance != null) params.importance = filters.importance
    const res = await fetchChronicles(projectId, params)
    chronicles.value = res.list || []
    total.value = res.total || 0
  } catch (e) {
    ElMessage.error(e.message || '编年史加载失败')
  } finally {
    loading.value = false
  }
}

function reloadChronicle() { page.value = 1; loadChronicle() }

async function loadEntity() {
  loading.value = true
  try {
    const params = { page: page.value, size: size.value }
    if (filters.type) params.type = filters.type
    if (filters.asOfGameDay != null) params.asOfGameDay = filters.asOfGameDay
    const res = await fetchEntities(projectId, params)
    entities.value = res.list || []
    total.value = res.total || 0
  } catch (e) {
    ElMessage.error(e.message || '实体状态加载失败')
  } finally {
    loading.value = false
  }
}

function reloadEntity() { page.value = 1; loadEntity() }

async function openDetail(row) {
  try {
    detail.value = await fetchChronicleDetail(projectId, row.id)
    detailVisible.value = true
  } catch (e) {
    ElMessage.error(e.message || '详情加载失败')
  }
}

async function openEntityHistory(row) {
  historyEntity.value = row
  historyVisible.value = true
  try {
    historyRows.value = await fetchEntityHistory(projectId, row.entityKey)
  } catch (e) {
    ElMessage.error(e.message || '版本链加载失败')
  }
}

function openAddChronicle() {
  Object.assign(addForm, { gameDay: null, title: '', content: '', importance: 3, involvedEntities: '' })
  addVisible.value = true
}

async function doAddChronicle() {
  if (!addForm.content?.trim()) { ElMessage.warning('内容不能为空'); return }
  saving.value = true
  try {
    const body = { title: addForm.title || undefined, content: addForm.content, importance: addForm.importance || 3 }
    if (addForm.gameDay != null) body.gameDay = addForm.gameDay
    if (addForm.involvedEntities?.trim()) body.involvedEntities = addForm.involvedEntities.trim()
    await addChronicle(projectId, body)
    ElMessage.success('编年史条目已补充')
    addVisible.value = false
    loadOverview()
    reloadChronicle()
  } catch (e) {
    ElMessage.error(e.message || '补充失败')
  } finally {
    saving.value = false
  }
}

function openUpdateEntity() {
  Object.assign(entityForm, { entityKey: '', currentState: '', recentChange: '', gameDay: null })
  updateVisible.value = true
}

async function doUpdateEntity() {
  if (!entityForm.entityKey?.trim() || !entityForm.currentState?.trim()) { ElMessage.warning('实体键与当前状态不能为空'); return }
  saving.value = true
  try {
    const body = { entityKey: entityForm.entityKey.trim(), currentState: entityForm.currentState.trim() }
    if (entityForm.recentChange?.trim()) body.recentChange = entityForm.recentChange.trim()
    if (entityForm.gameDay != null) body.gameDay = entityForm.gameDay
    await updateEntity(projectId, body)
    ElMessage.success('实体状态已更新（旧状态行已失效归档保留）')
    updateVisible.value = false
    loadOverview()
    reloadEntity()
  } catch (e) {
    ElMessage.error(e.message || '更新失败')
  } finally {
    saving.value = false
  }
}

// ===== 展示辅助 =====
function typeLabel(t) { return { location: '地点', person: '人物', faction: '势力' }[t] || t || '实体' }
function typeTag(t) { return { location: 'primary', person: 'success', faction: 'warning' }[t] || 'info' }
function impTag(i) { return i >= 4 ? 'danger' : i === 3 ? 'warning' : 'info' }
function sourceLabel(s) { return { instant: '即时', daily: '每日批量', consolidation: '巩固补漏', manual: '手动' }[s] || s || '—' }
function provLabel(p) { return { chronicle: '编年史', event: '事件', manual: '手动', consolidation: '巩固', dialog: '对话路由' }[p] || p || '—' }
</script>

<style scoped>
/* 页面容器：限宽走统一令牌；高度取外壳内容区 100%（替代旧的 calc(100vh - 140px) 魔法值，
   顶栏/内边距/密度变化时自动适应）；gap 与页头自带外边距不叠加（置零首个子项外边距）。 */
.world-memory-page { display: flex; flex-direction: column; gap: 14px; width: 100%; max-width: var(--content-max-w, 1440px); height: 100%; min-height: 0; margin: 0 auto; }
.world-memory-page > .page-header { margin-bottom: 0; }
/* 页头/说明样式由全局类提供（原自绘 .wm-top/.wm-title/.wm-sub/.wm-ops 已移除） */
.page-tabs { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.page-tabs :deep(.el-tabs__content) { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.page-tabs :deep(.el-tab-pane) { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.wm-cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
.wm-card { background: var(--bg-layer-1, #fff); border: 1px solid var(--border-light, #eee); border-radius: 10px; padding: 10px 14px; }
.wm-card-wide { grid-column: span 2; }
.wm-card-label { color: var(--text-secondary, #888); font-size: 0.75rem; }
.wm-card-value { font-size: 1.4rem; font-weight: 700; margin-top: 2px; }
.wm-card-text { font-size: 0.8rem; margin-top: 4px; line-height: 1.5; color: var(--text-primary, #333); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.wm-change-chip { display: inline-block; background: var(--primary-bg, #ecf5ff); border-radius: 4px; padding: 1px 6px; margin: 1px 6px 1px 0; font-size: 0.75rem; }
.wm-dim { color: var(--text-placeholder, #aaa); font-size: 0.78rem; }
.wm-tabs { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.wm-tabs :deep(.el-tabs__content) { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.wm-tabs :deep(.el-tab-pane) { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.wm-filters { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.wm-f-day { width: 140px; }
.wm-f-kw { width: 260px; }
.wm-f-imp { width: 130px; }
.wm-f-sep { color: var(--text-placeholder, #aaa); }
.wm-body { flex: 1; min-height: 0; display: flex; flex-direction: column; background: var(--bg-layer-1, #fff); border: 1px solid var(--border-light, #eee); border-radius: 10px; padding: 12px; overflow: hidden; }
.wm-pager { display: flex; justify-content: flex-end; margin-top: 10px; }
.wm-mono { font-family: ui-monospace, Consolas, monospace; font-size: 0.78rem; }
.wm-detail { display: flex; flex-direction: column; gap: 12px; }
.wm-detail-row { display: flex; align-items: center; gap: 10px; font-size: 0.85rem; }
.wm-src-cell { display: inline-flex; align-items: center; gap: 4px; }
.wm-detail-label { color: var(--text-secondary, #888); font-size: 0.78rem; min-width: 70px; }
.wm-detail-block { display: flex; flex-direction: column; gap: 4px; }
.wm-detail-text { font-size: 0.85rem; line-height: 1.6; }
.wm-timeline { margin-top: 10px; }
.wm-tl-state { font-size: 0.85rem; line-height: 1.5; }
.wm-tl-meta { display: flex; align-items: center; gap: 8px; margin-top: 4px; }
</style>
