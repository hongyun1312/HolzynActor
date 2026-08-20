<template>
  <div class="gallery-view">
    <!-- 顶部栏：品牌 + 全局搜索（占位）+ 通知 + 用户菜单（顶栏「设置」事件 → 打开设置弹窗） -->
    <ConsoleTopbar brand-label="HolzynActor" :show-collapse="false" @open-settings="openSettingsDialog" />

    <!-- 操作区：新增项目 + 导入项目 -->
    <div class="gallery-ops">
      <div class="ops-left">
        <div class="ops-title">项目画廊</div>
        <div class="ops-desc">以项目为单位组织世界观、角色、对话与世界演化，点击项目进入项目空间。</div>
      </div>
      <div class="ops-right">
        <el-button plain @click="openImportDialog">
          <el-icon><HIcon name="Upload" /></el-icon>&nbsp;导入项目
        </el-button>
        <!-- 首页设置入口：统一 SettingsButton → 打开全局设置弹窗（零路由依赖，保证可用） -->
        <SettingsButton @click="openSettingsDialog" />
        <el-button type="primary" @click="$router.push('/project/new')">
          <el-icon><HIcon name="Plus" /></el-icon>&nbsp;新增项目
        </el-button>
      </div>
    </div>

    <!-- 主体区 -->
    <div v-if="loading && projects.length === 0" v-loading="true" class="gallery-loading" />
    <el-empty v-else-if="projects.length === 0" description="还没有项目，创建你的第一个项目开始构建世界">
      <div class="empty-ops">
        <el-button type="primary" @click="$router.push('/project/new')">新建你的第一个项目</el-button>
        <el-button plain @click="openImportDialog">导入项目</el-button>
        <!-- 新账户引导：无项目时优先配置用户级 API（解析文件建项目需要）；统一 SettingsButton 视觉 -->
        <SettingsButton label="配置 API" @click="openSettingsDialog" />
      </div>
    </el-empty>
    <template v-else>
      <!-- 上半栏 · 最近项目（按最近更新时间倒序，最多 6 个） -->
      <section class="gallery-section">
        <div class="section-head">
          <div>
            <div class="section-title">最近项目</div>
            <div class="section-sub">点击项目以继续</div>
          </div>
        </div>
        <div v-if="recentProjects.length === 0" class="section-empty">暂无最近项目</div>
        <div v-else class="recent-row">
          <div v-for="p in recentProjects" :key="p.id" class="project-card tech-card" @click="openProject(p)">
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
          <div v-for="p in filteredProjects" :key="p.id" class="project-card tech-card">
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

    <!-- 导入项目对话框（.holzyn 包导入，V2.0） -->
    <el-dialog v-model="importDialog.visible" title="导入 .holzyn 项目包" width="520px">
      <el-alert type="info" :closable="false" show-icon
        title="导入后将完整还原项目（世界观/角色/对话/知识/记忆/设置）。"
        description="已导入过的项目（相同 projectUid）会被幂等拦截避免脏数据；包内含密码加密的 API 配置时需输入导出时设置的密码。" />
      <el-upload drag :auto-upload="false" :limit="1" accept=".holzyn,.zip" :on-change="onImportFileChange" :on-remove="() => (importDialog.file = null)" style="margin-top: 14px">
        <el-icon class="el-icon--upload"><HIcon name="UploadFilled" /></el-icon>
        <div class="el-upload__text">拖拽 .holzyn 文件到此处，或 <em>点击选择</em></div>
      </el-upload>
      <el-form label-width="90px" style="margin-top: 12px">
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

    <!-- 导出项目对话框（.holzyn 包导出，V2.0） -->
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

    <!-- 首页设置弹窗：全局（用户级）设置四子页 Tab 内嵌，零路由依赖（顶栏事件 / 操作区 / 空态三入口共用） -->
    <GlobalSettingsDialog ref="settingsRef" />
  </div>
</template>

<script setup>
/**
 * 项目画廊（前端布局重构 V1.0 首页，设计文档 §3.1）。
 * <p>职责：登录后默认落地页——顶部栏（品牌/搜索/通知/用户）+ 操作区（新增/导入）+
 * 上半栏「最近项目」（按更新时间倒序取前 6）+ 下半栏「所有项目」（网格 + 搜索过滤）；
 * 点击项目进入项目空间（默认仪表盘）；卡片菜单支持导出（占位）/删除（软删）。</p>
 * <p>数据来源：/api/projects（分页，全部加载后在本地排序/过滤）。</p>
 * <p>说明：后端暂无「最近访问时间」字段，「最近项目」以最近更新时间倒序近似。</p>
 */
import { ref, computed, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import ConsoleTopbar from '@/shared/components/console/ConsoleTopbar.vue'
import SettingsButton from '@/shared/components/SettingsButton.vue'
import GlobalSettingsDialog from '@/features/settings/views/GlobalSettingsDialog.vue'
import { fetchProjects, deleteProject, importProjectPackage, exportProjectPackage } from '@/shared/api'

const router = useRouter()
const projects = ref([])
const loading = ref(false)
const searchText = ref('')
const importDialog = reactive({ visible: false, file: null, password: '', loading: false })
const exportDialog = reactive({ visible: false, project: null, includeSensitive: false, password: '', loading: false })
// 首页设置弹窗：全局（用户级）设置四子页 Tab 内嵌（零路由依赖，保证可用）
const settingsRef = ref(null)

/** 打开首页设置弹窗（顶栏事件 / 操作区 / 空态三入口统一入口；可指定初始 Tab，默认 API 配置） */
function openSettingsDialog(tab) {
  settingsRef.value?.open(tab)
}

// 最近项目：按更新时间倒序取前 6（后端无最近访问字段，以 updatedAt 近似）
const recentProjects = computed(() =>
  [...projects.value].sort((a, b) => (b.updatedAt || '').localeCompare(a.updatedAt || '')).slice(0, 6)
)

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

/** 点击项目：进入项目空间默认仪表盘 */
function openProject(p) {
  router.push(`/project/${p.id}/dashboard`)
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
    router.push(`/project/${res.projectId}/dashboard`)
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

onMounted(loadProjects)
</script>

<style scoped>
.gallery-view { min-height: 100vh; background: var(--bg-page, var(--bg-base)); }
.gallery-ops { max-width: 1280px; margin: 0 auto; padding: 28px 24px 8px; display: flex; align-items: flex-end; justify-content: space-between; }
.ops-title { font-size: 1.5rem; font-weight: 700; color: var(--text-primary); }
.ops-desc { font-size: 0.85rem; color: var(--text-secondary); margin-top: 4px; }
.ops-right { display: flex; gap: 10px; }
.gallery-loading { max-width: 1280px; margin: 0 auto; height: 300px; }

.gallery-section { max-width: 1280px; margin: 16px auto 0; padding: 0 24px 12px; }
.section-head { display: flex; align-items: flex-end; justify-content: space-between; margin-bottom: 14px; }
.section-title { font-size: 1.1rem; font-weight: 700; color: var(--text-primary); }
.section-sub { font-size: 0.8rem; color: var(--text-secondary); margin-top: 2px; }
.all-search { width: 240px; }
.section-empty { background: var(--bg-layer-1); border-radius: var(--radius-lg); border: 1px dashed var(--border-color); padding: 32px; text-align: center; color: var(--text-secondary); }

/* 最近项目横排 */
.recent-row { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 16px; }
/* 所有项目网格 */
.project-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 20px; }

.project-card { cursor: pointer; overflow: hidden; border: 1px solid var(--border-light); border-radius: var(--radius-md); transition: box-shadow .2s; }
.project-card:hover { box-shadow: var(--shadow-md); }
.card-cover { height: 110px; background: var(--brand-gradient); color: #fff; display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: 1.05rem; position: relative; padding: 10px; }
.cover-name { text-align: center; overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; word-break: break-all; }
.cover-tag { position: absolute; right: 10px; bottom: 10px; }
.card-body { padding: 14px; }
.card-name { font-size: 0.95rem; font-weight: 600; color: var(--text-primary); margin-bottom: 6px; }
.card-summary { font-size: 0.8rem; color: var(--text-secondary); margin-bottom: 10px; min-height: 36px; overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.card-foot { display: flex; align-items: center; justify-content: space-between; }
.card-time { font-size: 0.75rem; color: var(--text-placeholder); }
.empty-ops { display: flex; gap: 10px; justify-content: center; }
</style>
