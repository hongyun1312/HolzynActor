<template>
  <div class="world-detail-view">
    <!-- 头部：名称 + 修改 + 版本标签 + 历史版本入口 -->
    <div class="page-header">
      <div>
        <div class="page-title">
          {{ world?.name || '世界详情' }}
          <el-tag v-if="world" size="small" effect="plain" class="version-tag">v{{ world.version || 1 }}</el-tag>
        </div>
        <div class="page-desc">项目世界观设定（只读全文展示），修改走专属编辑页；保存生成新版本（当前为覆盖更新）。</div>
      </div>
      <div class="header-ops">
        <el-button @click="openVersions">
          <el-icon><HIcon name="Clock" /></el-icon>&nbsp;历史版本
        </el-button>
        <el-button type="primary" @click="$router.push(`/project/${projectId}/world/edit`)">
          <el-icon><HIcon name="Edit" /></el-icon>&nbsp;修改
        </el-button>
      </div>
    </div>

    <!-- 世界观全文区（只读，无输入框） -->
    <div v-if="!world" class="empty-card tech-card">
      <el-empty description="该项目尚未创建世界观设定">
        <el-button type="primary" @click="$router.push(`/project/${projectId}/world/edit`)">创建世界观</el-button>
      </el-empty>
    </div>
    <div v-else class="world-body">
      <!-- 左侧导航分块查看：各部分不再平铺，避免页面过长 -->
      <el-tabs v-model="activeSection" tab-position="left" class="world-tabs">
        <el-tab-pane label="基本信息" name="basic">
          <div class="world-card tech-card">
            <div class="wc-section">
              <div class="wc-label">名称</div>
              <div class="wc-value">{{ world.name }}</div>
            </div>
            <el-divider />
            <div class="wc-grid">
              <div class="wc-cell">
                <div class="wc-label">题材</div>
                <div class="wc-value">{{ world.genre || '—' }}</div>
              </div>
              <div class="wc-cell">
                <div class="wc-label">时代背景</div>
                <div class="wc-value">{{ world.era || '—' }}</div>
              </div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="地理设定" name="geography">
          <div class="world-card tech-card">
            <div class="wc-section">
              <div class="wc-label">地理设定</div>
              <div class="wc-text">{{ world.geography || '—' }}</div>
            </div>
          </div>
        </el-tab-pane>

        <!-- 地点详情：世界观地点表（地理设定下，AI 提取 + 修改增删改查） -->
        <el-tab-pane label="地点详情" name="locations">
          <div class="world-card tech-card">
            <div class="wc-title-row">
              <span class="wc-title">世界观地点表</span>
              <el-tag size="small" type="info" effect="plain">从地理设定提取 · 对话所在地候选</el-tag>
            </div>
            <div class="loc-hint">由 AI 从「地理设定」文本识别地点并生成简介（也可在「新建项目解析」时自动提取）；
              点击「修改」进入增删改查；此处地点将作为对话创建「对话所在地」的候选下拉来源。</div>
            <WorldLocationTable :project-id="projectId" editable extractable />
          </div>
        </el-tab-pane>

        <el-tab-pane label="势力阵营" name="factions">
          <div class="world-card tech-card">
            <div class="wc-section">
              <div class="wc-label">势力阵营</div>
              <div class="wc-text">{{ world.factions || '—' }}</div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="规则体系" name="magic">
          <div class="world-card tech-card">
            <div class="wc-section">
              <div class="wc-label">规则体系</div>
              <div class="wc-text">{{ world.magicSystem || '—' }}</div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="文化风俗" name="culture">
          <div class="world-card tech-card">
            <div class="wc-section">
              <div class="wc-label">文化风俗</div>
              <div class="wc-text">{{ world.culture || '—' }}</div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="历史背景" name="history">
          <div class="world-card tech-card">
            <div class="wc-section">
              <div class="wc-label">历史背景</div>
              <div class="wc-text">{{ world.history || '—' }}</div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="完整世界观" name="freeText">
          <div class="world-card tech-card">
            <div class="wc-section">
              <div class="wc-label">自由文本（完整世界观）</div>
              <div class="wc-text">{{ world.freeText || '—' }}</div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="项目大事记" name="memories">
          <div class="world-card tech-card">
            <div class="wc-title-row">
              <span class="wc-title">项目级记忆（世界大事记）</span>
              <el-tag size="small" type="info" effect="plain">{{ projectMemories.length }} 条</el-tag>
            </div>
            <div class="mem-hint">由对话抽取 + 世界模拟自动生成，所有角色知晓。此区与「知识库 · 项目大事记」同源。</div>
            <div v-loading="memLoading" class="mem-list">
              <div v-if="projectMemories.length === 0" class="mem-empty">暂无项目级记忆</div>
              <div v-for="m in projectMemories" :key="m.id" class="mem-item">
                <div class="mem-head">
                  <el-tag size="small" :type="m.kind === 'summary' ? 'primary' : 'info'" effect="plain">
                    {{ m.kind === 'summary' ? '摘要' : '事实' }}
                  </el-tag>
                  <el-tag size="small" :type="m.importance >= 4 ? 'danger' : (m.importance >= 3 ? 'warning' : 'info')" effect="light">
                    重要度 {{ m.importance }}
                  </el-tag>
                  <span class="mem-time">{{ (m.createdAt || '').slice(0, 16).replace('T', ' ') }}</span>
                  <el-button class="mem-del" size="small" text type="danger" @click="doDeleteMemory(m)" title="删除该记忆">
                    <el-icon><HIcon name="Delete" /></el-icon>
                  </el-button>
                </div>
                <div class="mem-content">{{ m.content }}</div>
              </div>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- 历史版本占位对话框 -->
    <el-dialog v-model="versionDialog" title="世界观版本历史" width="520px">
      <el-alert type="info" :closable="false" show-icon
        title="版本历史为待开发功能。"
        description="当前后端保存世界观为「覆盖更新」（不产生新版本记录）；版本化存取将在后续阶段实现（设计文档 §6.1-5）。" />
      <div class="ver-current" v-if="world">
        当前版本：<el-tag size="small">v{{ world.version || 1 }}</el-tag>
        <span class="ver-time">（更新于 {{ (world.updatedAt || '').slice(0, 16).replace('T', ' ') }}）</span>
      </div>
      <template #footer>
        <el-button @click="versionDialog = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 世界详情页（前端布局重构 V1.0，设计文档 §3.4）。
 * <p>职责：完整呈现项目世界观设定（只读全文渲染，不使用输入框）+ 项目级记忆（世界大事记）；
 * 头部提供「修改」入口（跳专属修改页 /world/edit）与「历史版本」入口（占位：当前后端为覆盖更新）。
 * 修改走专属编辑页，展示区保持只读优先。</p>
 * <p>数据来源：/api/projects/{id}/world-setting、memories。</p>
 */
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchWorldSetting, fetchMemories, deleteMemory } from '@/shared/api'
import WorldLocationTable from '@/shared/components/WorldLocationTable.vue'

const route = useRoute()
const projectId = Number(route.params.id)

const world = ref(null)
const projectMemories = ref([])
const memLoading = ref(false)
const versionDialog = ref(false)
// 左侧导航当前分块（基本信息/地理设定/地点详情/势力阵营/规则体系/文化风俗/历史背景/完整世界观/项目大事记）
const activeSection = ref('basic')

/** 打开历史版本占位对话框 */
function openVersions() { versionDialog.value = true }

/** 加载世界观全文 + 项目级记忆 */
async function loadAll() {
  try { world.value = await fetchWorldSetting(projectId) } catch (_) { world.value = null }
  memLoading.value = true
  try {
    const page = await fetchMemories(projectId, { page: 1, size: 100 })
    projectMemories.value = (page.list || []).filter((m) => !m.characterId)
  } catch (_) { projectMemories.value = [] }
  finally { memLoading.value = false }
}

/** 删除项目级记忆（软删） */
async function doDeleteMemory(m) {
  try {
    await ElMessageBox.confirm(`确认删除该条记忆？删除后不再注入所有角色对话。`, '删除确认', { type: 'warning' })
    await deleteMemory(m.id)
    ElMessage.success('已删除')
    projectMemories.value = projectMemories.value.filter((x) => x.id !== m.id)
  } catch (_) { /* 用户取消 */ }
}

onMounted(loadAll)
</script>

<style scoped>
/* 容器加宽（原 1000px 偏窄，扩到 1200px 与设置页一致，地点表格更舒展） */
.world-detail-view { max-width: 1200px; margin: 0 auto; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; }
.page-title { font-size: 1.3rem; font-weight: 700; color: var(--text-primary); }
.version-tag { margin-left: 8px; vertical-align: middle; }
.page-desc { font-size: 0.85rem; color: var(--text-secondary); margin-top: 4px; }
.header-ops { display: flex; gap: 8px; }
.empty-card { padding: 40px 0; }
.world-body { min-height: 360px; }
/* 左侧导航分块：导航列固定宽度，内容区自适应 */
.world-tabs { width: 100%; }
.world-tabs :deep(.el-tabs__header) { margin-right: 0; }
.world-tabs :deep(.el-tabs__nav-wrap) { width: 128px; }
.world-tabs :deep(.el-tabs__content) { overflow: visible; }
.world-card { padding: 20px 24px; }
.wc-section { margin-bottom: 4px; }
.wc-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.wc-cell { min-width: 0; }
.wc-label { font-size: 0.78rem; color: var(--text-secondary); font-weight: 600; margin-bottom: 6px; }
.wc-value { font-size: 0.95rem; color: var(--text-primary); font-weight: 600; }
.wc-text { font-size: 0.92rem; color: var(--text-regular); line-height: 1.8; white-space: pre-wrap; word-break: break-word; }
/* 项目级记忆 */
.wc-title-row { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.wc-title { font-weight: 700; color: var(--text-primary); }
/* 地点详情提示 */
.loc-hint { font-size: 0.8rem; color: var(--text-secondary); margin-bottom: 12px; line-height: 1.6; }
.mem-hint { font-size: 0.8rem; color: var(--text-secondary); margin-bottom: 12px; }
.mem-list { max-height: 420px; overflow-y: auto; display: flex; flex-direction: column; gap: 10px; }
.mem-empty { color: var(--text-secondary); text-align: center; padding: 24px 0; }
.mem-item { border: 1px solid var(--border-light); border-radius: 8px; padding: 10px 12px; background: var(--bg-layer-2); }
.mem-head { display: flex; align-items: center; gap: 8px; }
.mem-time { font-size: 0.72rem; color: var(--text-secondary); }
.mem-del { margin-left: auto; opacity: 0; transition: opacity .15s; }
.mem-item:hover .mem-del { opacity: 1; }
.mem-content { font-size: 0.86rem; color: var(--text-primary); margin-top: 8px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.ver-current { margin-top: 14px; font-size: 0.85rem; color: var(--text-regular); }
.ver-time { font-size: 0.75rem; color: var(--text-secondary); }
</style>
