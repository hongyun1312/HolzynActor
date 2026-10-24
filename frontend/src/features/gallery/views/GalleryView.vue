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
  <!-- 统一外壳（global 模式）：顶栏 = AppShell 的 ShellTopbar（品牌/命令面板/图标抽屉/主题密度/通知/账户），
       本页不再自绘顶栏，避免与项目空间形成两套顶栏。
       云间列车液态玻璃背景已由 AppShell 全站常驻（无需本页再挂 CloudTrainBackground）。 -->
  <AppShell mode="global" brand-label="HolzynActor" :show-sidebar="false" :show-back="false">
    <!-- 场景内容：玻璃令牌由全局 .glass-scene（AppShell 根）提供 -->
    <div class="gallery-scene">
    <div class="content-col">
      <!-- 操作区（统一页头）：标题 + 说明 + 新增/导入/设置 -->
      <PageHeader
        title="项目画廊"
        desc="以项目为单位组织世界观、角色、对话与世界演化，点击项目进入项目空间。"
      >
        <template #actions>
          <el-button plain @click="openImportDialog">
            <el-icon><HIcon name="Upload" /></el-icon>&nbsp;导入项目
          </el-button>
          <!-- 首页设置入口：统一 SettingsButton → 打开全局设置弹窗（零路由依赖，保证可用） -->
          <SettingsButton @click="openSettingsDialog" />
          <el-button type="primary" data-tour="fr-add-project" @click="openNewProjectDialog">
            <el-icon><HIcon name="Plus" /></el-icon>&nbsp;新增项目
          </el-button>
        </template>
      </PageHeader>

    <!-- P0-5：未配置模型 API 常驻提醒横幅（会话内可关闭，下次启动再提醒） -->
    <el-alert
      v-if="showApiBanner"
      class="gallery-api-banner"
      type="warning"
      show-icon
      :closable="true"
      @close="dismissApiBanner"
      title="还没接上模型：角色暂时只会重复简单作息，不会自己思考和行动"
      description="接入一个 OpenAI 兼容的模型接口后，世界才会真正运转——角色会自己思考、行动与对话。现在是「能看不能演」的兜底状态。"
    >
      <template #default>
        <el-button size="small" type="warning" plain @click="openSettingsDialog('apis')">去接入模型</el-button>
      </template>
    </el-alert>

    <!-- 主体区 -->
    <div v-if="loading && projects.length === 0" v-loading="true" class="gallery-loading" />
    <EmptyState
      v-else-if="projects.length === 0"
      size="page"
      icon="ic_gallery_map_all"
      title="还没有项目"
      desc="每个项目是一个独立的世界：有自己的世界观、角色、时间与记忆，互不干扰。"
      primary-text="新建你的第一个项目"
      secondary-text="导入已有项目"
      @primary="openNewProjectDialog"
      @secondary="openImportDialog"
    >
      <template #extra>
        <!-- 新账户引导：无项目时优先配置用户级 API（解析文件建项目需要）；统一 SettingsButton 视觉 -->
        <SettingsButton label="配置 API（解析文件建项目需要）" @click="openSettingsDialog" />
      </template>
    </EmptyState>
    <template v-else>
      <!-- 上半栏 · 最近项目（按最近更新时间倒序，最多 6 个）：仅当它是「所有项目」的真子集时出现 -->
      <section v-if="showRecent" class="gallery-section">
        <div class="section-head">
          <div>
            <div class="section-title">最近项目</div>
            <div class="section-sub">点击项目以继续</div>
          </div>
        </div>
        <div v-if="recentProjects.length === 0" class="section-empty">暂无最近项目</div>
        <div v-else class="recent-row">
          <div v-for="p in recentProjects" :key="p.id" class="project-card tech-card" data-tour="project-card" :data-guide-demo="p.id === DEMO_PROJECT_ID ? '1' : undefined" @click="openProject(p)">
            <div class="card-cover" :style="coverStyle(p)">
              <span class="cover-name">{{ p.name || '未命名项目' }}</span>
              <el-tag v-if="p.characterCount > 0" size="small" class="cover-tag">{{ p.characterCount }} 角色</el-tag>
            </div>
            <div class="card-body">
              <div class="card-name">{{ p.name || '未命名项目' }}</div>
              <div class="card-summary">{{ p.summary || '暂无描述' }}</div>
              <div class="card-foot">
                <el-tag size="small" effect="light" :type="statusType(p.status)">{{ statusText(p.status) }}</el-tag>
                <span class="card-time">{{ fmtTime(p.updatedAt) }}</span>
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- 下半栏 · 所有项目（网格 + 搜索过滤） -->
      <section class="gallery-section">
        <div class="section-head">
          <div>
            <div class="section-title">所有项目</div>
            <div class="section-sub">共 {{ projects.length }} 个项目</div>
          </div>
          <el-input v-model="searchText" placeholder="搜索项目名称…" clearable class="all-search" :prefix-icon="Search" />
        </div>
        <div v-if="filteredProjects.length === 0" class="section-empty">没有匹配的项目</div>
        <div v-else class="project-grid">
          <div v-for="p in filteredProjects" :key="p.id" class="project-card tech-card" data-tour="project-card" :data-guide-demo="p.id === DEMO_PROJECT_ID ? '1' : undefined">
            <div class="card-cover" :style="coverStyle(p)" @click="openProject(p)">
              <span class="cover-name">{{ p.name || '未命名项目' }}</span>
              <el-tag v-if="p.characterCount > 0" size="small" class="cover-tag">{{ p.characterCount }} 角色</el-tag>
            </div>
            <div class="card-body">
              <div class="card-name" @click="openProject(p)">{{ p.name || '未命名项目' }}</div>
              <div class="card-summary" @click="openProject(p)">{{ p.summary || '暂无描述' }}</div>
              <div class="card-foot">
                <el-tag size="small" effect="light" :type="statusType(p.status)">{{ statusText(p.status) }}</el-tag>
                <el-dropdown trigger="click" @command="(cmd) => onCardCommand(cmd, p)">
                  <el-button size="small" text><el-icon><HIcon name="MoreFilled" /></el-icon></el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="open">打开项目空间</el-dropdown-item>
                      <el-dropdown-item command="export">导出 .holzyn</el-dropdown-item>
                      <el-dropdown-item command="delete" divided>删除项目</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </div>
          </div>
        </div>
      </section>
    </template>

    <!-- 导入项目对话框（与「新增项目 / 设置」共用 h-dialog 弹窗规范） -->
    <el-dialog v-model="importDialog.visible" title="导入项目" width="560px" top="6vh" class="h-dialog" destroy-on-close>
      <el-alert class="h-dialog-alert" type="info" :closable="false" show-icon
        title="导入 .holzyn 项目包，完整还原项目（世界观/角色/对话/知识/记忆/设置）。"
        description="已导入过的项目（相同 projectUid）会被幂等拦截避免脏数据；包内含密码加密的 API 配置时需输入导出时设置的密码。" />
      <el-upload drag :auto-upload="false" :limit="1" accept=".holzyn,.zip" :on-change="onImportFileChange" :on-remove="() => (importDialog.file = null)">
        <el-icon class="el-icon--upload"><HIcon name="UploadFilled" /></el-icon>
        <div class="el-upload__text">拖拽 .holzyn 文件到此处，或 <em>点击选择</em></div>
      </el-upload>
      <el-form label-width="90px" style="margin-top: 14px">
        <el-form-item label="密码（可选）">
          <el-input v-model="importDialog.password" type="password" show-password placeholder="包内含密码加密的 API 配置时填写" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="importDialog.loading" :disabled="!importDialog.file" @click="doImportPackage">
          导入项目
        </el-button>
      </template>
    </el-dialog>

    <!-- 导出项目对话框 -->
    <el-dialog v-model="exportDialog.visible" :title="`导出 .holzyn · ${exportDialog.project?.name || ''}`" width="520px">
      <el-alert type="info" :closable="false" show-icon
        title="导出为 .holzyn 项目包（全量业务数据 + 可选 API 敏感数据 + 可选密码加密）。"
        description="默认不携带任何 API 密钥，可安全分享/拷贝；勾选「包含敏感数据」后将随包导出 API 配置，建议同时设置密码加密。" />
      <el-form label-width="150px" style="margin-top: 14px">
        <el-form-item label="包含 API 敏感数据">
          <el-switch v-model="exportDialog.includeSensitive" />
        </el-form-item>
        <el-form-item v-if="exportDialog.includeSensitive" label="密码加密（可选）">
          <el-input v-model="exportDialog.password" type="password" show-password placeholder="设置后导入需输入此密码才能恢复 API 配置" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="exportDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="exportDialog.loading" @click="doExportPackage">导出</el-button>
      </template>
    </el-dialog>

    <!-- 首页设置弹窗：全局（用户级）设置四子页 Tab 内嵌，零路由依赖（操作区 / 空态两入口共用） -->
    <GlobalSettingsDialog ref="settingsRef" />

    <!-- 新增项目弹窗：与「导入项目 / 设置」共用 h-dialog 规范，Tab 组织四种创建方式 -->
    <NewProjectDialog ref="newProjectRef" />
    </div><!-- /.content-col -->
    </div><!-- /.gallery-scene -->
  </AppShell>
</template>

<script setup>
/**
 * 项目画廊。
 * <p>职责：登录后默认落地页——顶部栏（品牌/搜索/通知/用户）+ 操作区（新增/导入）+
 * 上半栏「最近项目」（按更新时间倒序取前 6）+ 下半栏「所有项目」（网格 + 搜索过滤）；
 * 点击项目进入项目空间（世界沙盘 S1：默认世界沙盘）；卡片菜单支持导出（占位）/删除（软删）。</p>
 * <p>数据来源：/api/projects（分页，全部加载后在本地排序/过滤）。</p>
 * <p>说明：后端暂无「最近访问时间」字段，「最近项目」以最近更新时间倒序近似。</p>
 */
import { ref, computed, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppShell from '@/shared/layout/AppShell.vue'
import PageHeader from '@/shared/layout/PageHeader.vue'
import SettingsButton from '@/shared/components/SettingsButton.vue'
// V2.0 批次二：空态统一走 EmptyState（空态即教学：标题 + 为什么空 + 唯一主 CTA）
import EmptyState from '@/shared/components/EmptyState.vue'
import GlobalSettingsDialog from '@/features/settings/views/GlobalSettingsDialog.vue'
import NewProjectDialog from '../components/NewProjectDialog.vue'
import { fetchProjects, deleteProject, importProjectPackage, exportProjectPackage, fetchModelApis } from '@/shared/api'

const router = useRouter()

const projects = ref([])
const loading = ref(false)
const searchText = ref('')
const importDialog = reactive({ visible: false, file: null, password: '', loading: false })
const exportDialog = reactive({ visible: false, project: null, includeSensitive: false, password: '', loading: false })
// 首页设置弹窗：全局（用户级）设置四子页 Tab 内嵌（零路由依赖，保证可用）
const settingsRef = ref(null)
// 新增项目弹窗（与导入/设置共用 h-dialog 弹窗规范；含四种创建方式 Tab）
const newProjectRef = ref(null)
// P0-5：未配置模型 API 提醒横幅（会话内可关闭；sessionStorage 记关闭，下次启动再提醒）
const showApiBanner = ref(false)
// 示例世界（内置演示项目）ID：浮生小镇 · 青屿镇——新手引导第③步的目标卡片
const DEMO_PROJECT_ID = 1

/** 打开首页设置弹窗（顶栏事件 / 操作区 / 空态三入口统一入口；可指定初始 Tab，默认 API 配置） */
function openSettingsDialog(tab) {
  settingsRef.value?.open(tab)
}

/** 打开新增项目弹窗（替代原跳转 /project/new 页面，与导入/设置统一为弹窗形态） */
function openNewProjectDialog() {
  newProjectRef.value?.open()
}

/** 会话内未配置模型 API 时展示常驻提醒横幅 */
async function checkApiConfigured() {
  try {
    if (sessionStorage.getItem('holzyn.apiBanner.dismissed') === '1') return
    const list = (await fetchModelApis(null)) || []
    showApiBanner.value = list.length === 0
  } catch (_) {
    showApiBanner.value = false
  }
}

/** 关闭横幅（仅本次会话；下次启动重新提醒） */
function dismissApiBanner() {
  sessionStorage.setItem('holzyn.apiBanner.dismissed', '1')
  showApiBanner.value = false
}

// 最近项目：按更新时间倒序取前 6（后端无最近访问字段，以 updatedAt 近似）
const recentProjects = computed(() =>
  [...projects.value].sort((a, b) => (b.updatedAt || '').localeCompare(a.updatedAt || '')).slice(0, 6)
)

// V2.0：项目数 ≤ 最近项目条数时，「最近项目」与「所有项目」是**同一批卡片**——
// 实测只有 1 个项目时，画廊会把同一张卡上下各渲染一次（同一页面同一内容的两个入口，
// 正是本方案要消除的问题）。所以「最近项目」只在它确实是**子集**时才出现：
// 项目多到需要"先给最近几个"的快捷入口时才显示，否则直接只留「所有项目」。
const showRecent = computed(() => projects.value.length > recentProjects.value.length)

// 所有项目：本地搜索过滤
const filteredProjects = computed(() => {
  const kw = searchText.value.trim().toLowerCase()
  if (!kw) return projects.value
  return projects.value.filter(p => (p.name || '').toLowerCase().includes(kw) || (p.code || '').toLowerCase().includes(kw))
})

/** 项目状态文案（0草稿/1已生成角色卡/2进行中） */
function statusText(s) {
  return s === 1 ? '已生成角色卡' : (s === 2 ? '进行中' : '草稿')
}

/** 项目状态标签类型 */
function statusType(s) {
  return s === 1 ? 'success' : (s === 2 ? 'primary' : 'info')
}

/** 封面样式：有封面图用图片，否则用品牌渐变占位色块 */
function coverStyle(p) {
  if (p.coverUrl) return { backgroundImage: `url(${p.coverUrl})`, backgroundSize: 'cover', backgroundPosition: 'center' }
  return {}
}

/** 时间格式化 */
function fmtTime(t) {
  if (!t) return ''
  return String(t).slice(0, 10)
}

/** 点击项目：进入项目空间默认世界沙盘（S1 项目主页；lastProjectId 由 ProjectLayout 进入时记录） */
function openProject(p) {
  router.push(`/project/${p.id}/sandbox`)
}

/** 打开导入项目占位对话框 */
function openImportDialog() {
  importDialog.visible = true
  importDialog.file = null
  importDialog.password = ''
}

/** 导入文件选择回调 */
function onImportFileChange(file) {
  importDialog.file = file.raw
}

/** 导入 .holzyn 包（幂等拦截 + 密码可选） */
async function doImportPackage() {
  if (!importDialog.file) return
  importDialog.loading = true
  try {
    const res = await importProjectPackage(importDialog.file, importDialog.password || undefined)
    ElMessage.success(`项目「${res.name}」导入成功（角色 ${res.characterCount} 位）`)
    importDialog.visible = false
    await loadProjects()
    router.push(`/project/${res.projectId}/sandbox`)
  } catch (e) {
    ElMessage.error(e.message || '导入失败')
  } finally { importDialog.loading = false }
}

/** 打开导出对话框 */
function openExportDialog(p) {
  Object.assign(exportDialog, { visible: true, project: p, includeSensitive: false, password: '', loading: false })
}

/** 导出 .holzyn 包（下载） */
async function doExportPackage() {
  exportDialog.loading = true
  try {
    const blob = await exportProjectPackage(exportDialog.project.id, exportDialog.includeSensitive, exportDialog.password || undefined)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${exportDialog.project.name || 'project'}.holzyn`
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('已导出 .holzyn 项目包')
    exportDialog.visible = false
  } catch (e) { ElMessage.error(e.message || '导出失败') }
  finally { exportDialog.loading = false }
}

/** 卡片菜单命令处理（打开/导出/删除） */
async function onCardCommand(cmd, p) {
  if (cmd === 'open') return openProject(p)
  if (cmd === 'export') return openExportDialog(p)
  if (cmd === 'delete') {
    try {
      await ElMessageBox.confirm(`确认删除项目「${p.name}」？此操作不可恢复。`, '删除确认', { type: 'warning' })
      await deleteProject(p.id)
      ElMessage.success('项目已删除')
      await loadProjects()
    } catch (_) { /* 用户取消或失败 */ }
  }
}

/** 加载全部项目（一次取 100 条，本地排序/过滤/分页） */
async function loadProjects() {
  loading.value = true
  try {
    const res = await fetchProjects({ page: 1, size: 100 })
    projects.value = (res && res.list) ? res.list : []
  } catch (e) { ElMessage.error(e.message || '加载项目失败') }
  finally { loading.value = false }
}

onMounted(() => {
  loadProjects()
  checkApiConfigured()
})
</script>

<style scoped>
/* 页面内容由 .content-col（全局类）限宽居中；这里只保留画廊特有的段落间距与卡片样式。
   云间列车液态玻璃场景：文本/控件令牌由 .glass-scene 提供，此处只做玻璃面板材质。 */
.gallery-loading { height: 300px; }

/* P0-5：未配置模型 API 提醒横幅 */
.gallery-api-banner { margin-top: 14px; }
.gallery-api-banner .el-alert__content { flex: 1; }

.gallery-section { margin-top: 16px; padding-bottom: 12px; }
.section-head { display: flex; align-items: flex-end; justify-content: space-between; margin-bottom: 14px; }
.section-title { font-size: 1.1rem; font-weight: 700; color: var(--text-primary); }
.section-sub { font-size: 0.8rem; color: var(--text-secondary); margin-top: 2px; }
.all-search { width: 240px; }
.section-empty {
  background: var(--glass-bg, rgba(14, 22, 34, 0.5));
  -webkit-backdrop-filter: blur(var(--glass-blur, 18px)) saturate(var(--glass-saturate, 1.4));
  backdrop-filter: blur(var(--glass-blur, 18px)) saturate(var(--glass-saturate, 1.4));
  border-radius: var(--radius-lg);
  border: 1px dashed var(--border-color);
  padding: 32px; text-align: center; color: var(--text-secondary);
}

/* 最近项目横排 */
.recent-row { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 16px; }
/* 所有项目网格 */
.project-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 20px; }

/* 项目卡片：液态玻璃材质（覆盖全局 .tech-card 的实心底）
   （-webkit- 前缀在标准属性之前，避免被 lightningcss 前缀合并吞掉） */
.project-card {
  cursor: pointer; overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: var(--radius-md);
  background: rgba(10, 17, 28, 0.52);
  -webkit-backdrop-filter: blur(var(--glass-blur, 20px)) saturate(var(--glass-saturate, 1.5));
  backdrop-filter: blur(var(--glass-blur, 20px)) saturate(var(--glass-saturate, 1.5));
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08), 0 10px 30px rgba(0, 0, 0, 0.35);
  transition: box-shadow .2s, border-color .2s, transform .2s;
}
.project-card:hover {
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.1), 0 16px 40px rgba(0, 0, 0, 0.5);
  border-color: rgba(255, 255, 255, 0.28);
  transform: translateY(-2px);
}
.card-cover { height: 110px; background: var(--brand-gradient); color: #fff; display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: 1.05rem; position: relative; padding: 10px; }
.cover-name { text-align: center; overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; word-break: break-all; }
.cover-tag { position: absolute; right: 10px; bottom: 10px; }
.card-body { padding: 14px; }
.card-name { font-size: 0.95rem; font-weight: 600; color: var(--text-primary); margin-bottom: 6px; }
.card-summary { font-size: 0.8rem; color: var(--text-secondary); margin-bottom: 10px; min-height: 36px; overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.card-foot { display: flex; align-items: center; justify-content: space-between; }
.card-time { font-size: 0.75rem; color: var(--text-placeholder); }
.empty-ops { display: flex; gap: 10px; justify-content: center; }

/* 空态卡片（EmptyState 是共享组件，内部不随玻璃场景变；在画廊场景内覆写为液态玻璃） */
.gallery-scene :deep(.es-card) {
  background: rgba(10, 17, 28, 0.52);
  -webkit-backdrop-filter: blur(var(--glass-blur, 20px)) saturate(var(--glass-saturate, 1.5));
  backdrop-filter: blur(var(--glass-blur, 20px)) saturate(var(--glass-saturate, 1.5));
  border: 1px solid rgba(255, 255, 255, 0.14);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08), 0 18px 48px rgba(0, 0, 0, 0.45);
}
</style>
