<template>
  <div class="general-settings">
    <!-- 全局设置提示：仅首页/未进入项目时显示（项目专属设置在项目空间内；内嵌弹窗时由弹窗顶部说明承担） -->
    <div v-if="!projectId && !embedded" class="gs-card tech-card">
      <div class="gs-title">全局设置（首页）</div>
      <div class="gs-desc">此处为「用户级」全局设置：本地账户、主题与外观、群聊每轮回复上限（全局）。
        项目专属设置（基本信息 / 世界时钟 / 导入导出 / 危险区）请进入对应项目空间 → 设置 中配置。</div>
    </div>

    <!-- ⓪ 本地账户（本地单用户，编辑入口） -->
    <div class="gs-card tech-card">
      <div class="gs-title">本地账户</div>
      <div class="gs-desc">本地个人账户信息（昵称 / 头像 / 个性签名 + 「NPC 眼中的你」：身份 / 职业 / 喜好 / 禁忌 / 个人档案）。
        这些信息会注入 NPC 对话上下文，让角色基于对你的了解做出定制化回应；所有字段选填。</div>
      <el-button type="primary" plain @click="router.push('/account')"><el-icon><HIcon name="User" /></el-icon>&nbsp;编辑本地账户</el-button>
    </div>

    <!-- ⓪.5 主题与外观（双主题分层 + 可换肤主色 + 世界题材联动） -->
    <div class="gs-card tech-card">
      <div class="gs-title">主题与外观</div>
      <div class="gs-desc" v-if="projectId">深浅主题（可跟随系统）、个性化主色，以及按本项目世界观题材自动换肤（恋爱→粉、科幻→蓝青、奇幻→紫…）。</div>
      <div class="gs-desc" v-else>深浅主题（可跟随系统）、个性化主色（全局默认）；世界题材联动在进入项目时按题材自动应用。</div>
      <el-form label-width="110px" class="gs-form">
        <el-form-item label="深浅模式">
          <el-radio-group v-model="theme.mode" @change="onModeChange">
            <el-radio-button value="system">跟随系统</el-radio-button>
            <el-radio-button value="light">浅色</el-radio-button>
            <el-radio-button value="dark">深色</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="个性化主色">
          <div class="theme-swatches">
            <button
              v-for="p in presets"
              :key="p.id"
              class="swatch"
              :class="{ active: (projectOverride || theme.accent) === p.color }"
              :style="{ background: p.color }"
              :title="p.label"
              @click="onPickAccent(p.color)"
            ></button>
            <el-color-picker v-model="accentModel" size="small" @change="onPickAccent" />
            <span class="gs-hint">当前主色：<b class="accent-text">{{ projectOverride || theme.accent }}</b></span>
          </div>
        </el-form-item>
        <el-form-item label="世界题材换肤">
          <el-switch v-model="theme.genreFollow" @change="onGenreFollow" />
          <span class="gs-hint" v-if="worldGenre">当前题材：<b>{{ worldGenre }}</b>{{ theme.genreFollow ? '（已自动应用题材主色）' : '（未启用）' }}</span>
          <span class="gs-hint" v-else-if="projectId">本项目暂无世界观题材</span>
          <span class="gs-hint" v-else>进入项目后按世界观题材自动换肤</span>
        </el-form-item>
        <el-form-item v-if="projectId" label="本项目主色">
          <div class="theme-swatches">
            <el-color-picker v-model="projectOverrideModel" size="small" @change="onProjectOverride" />
            <el-button v-if="projectOverride" size="small" text type="danger" @click="onResetProjectOverride">重置为默认/题材色</el-button>
            <span class="gs-hint">仅影响本项目的界面主色（覆盖题材联动）</span>
          </div>
        </el-form-item>
      </el-form>
    </div>

    <!-- ① 基本信息（仅项目空间） -->
    <div v-if="projectId" class="gs-card tech-card">
      <div class="gs-title">基本信息</div>
      <el-form :model="infoForm" label-width="80px" class="gs-form">
        <el-form-item label="项目名称" required>
          <el-input v-model="infoForm.name" maxlength="100" show-word-limit />
        </el-form-item>
        <el-form-item label="项目编码">
          <el-input v-model="infoForm.code" maxlength="50" />
        </el-form-item>
        <el-form-item label="项目概要">
          <el-input v-model="infoForm.summary" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="savingInfo" @click="saveInfo">保存项目信息</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- ② 世界时钟（仅项目空间） -->
    <div v-if="projectId" class="gs-card tech-card">
      <div class="gs-title">世界时钟</div>
      <div class="gs-desc">速率（每真实小时推进的游戏小时数）、暂停/恢复开关、锚点与起始游戏时刻；与「世界演化」控制条联动。</div>
      <el-form label-width="140px" class="gs-form">
        <el-form-item label="速率（游戏时/真实时）">
          <el-input-number v-model="clockForm.rate" :min="1" :max="240" style="width: 140px" />
          <span class="gs-hint">默认 24 = 1 真实小时推进 1 游戏日</span>
        </el-form-item>
        <el-form-item label="模拟状态">
          <el-switch v-model="clockForm.paused" active-text="已暂停" inactive-text="推进中" />
          <span class="gs-hint">{{ clockForm.paused ? '世界模拟已暂停（点击恢复推进）' : '世界模拟正在推进' }}</span>
        </el-form-item>
        <el-form-item label="锚点真实时刻">
          <el-date-picker v-model="clockForm.worldStartAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="锚点时间（世界模拟起算点）" style="width: 240px" />
        </el-form-item>
        <el-form-item label="起始游戏时刻">
          <el-input-number v-model="clockForm.worldStartGameHour" :min="0" style="width: 140px" />
          <span class="gs-hint">锚点对应的游戏起始小时数</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="savingClock" @click="saveClock">保存世界时钟</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- ③ 群聊配置 -->
    <div class="gs-card tech-card">
      <div class="gs-title">群聊配置</div>
      <div class="gs-desc">群聊每轮回复上限（1~20）。</div>
      <el-form label-width="140px" class="gs-form">
        <el-form-item label="每轮回复上限">
          <el-input-number v-model="groupReplies" :min="1" :max="20" style="width: 140px" />
          <el-button class="gs-hint-btn" type="primary" text @click="saveGroupReplies">保存</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- ④ 导入 / 导出（仅项目空间） -->
    <div v-if="projectId" class="gs-card tech-card">
      <div class="gs-title">导入 / 导出</div>
      <div class="gs-desc">导出为 .holzyn 项目包（全量业务数据 + 可选 API 敏感数据 + 可选密码加密）；导入 .holzyn 包完整还原。</div>
      <div class="io-ops">
        <el-button type="primary" @click="openExport"><el-icon><HIcon name="Download" /></el-icon>&nbsp;导出 .holzyn</el-button>
        <el-button plain @click="openImport"><el-icon><HIcon name="Upload" /></el-icon>&nbsp;导入 .holzyn 包</el-button>
      </div>
    </div>

    <!-- ⑤ 危险区（仅项目空间） -->
    <div v-if="projectId" class="gs-card tech-card danger">
      <div class="gs-title">危险区</div>
      <div class="gs-desc">删除项目为软删（不可恢复），删除后该项目的世界观/角色/对话/知识/记忆等数据将不可访问。</div>
      <el-button type="danger" plain @click="doDeleteProject"><el-icon><HIcon name="Delete" /></el-icon>&nbsp;删除项目</el-button>
    </div>

    <!-- 导出对话框 -->
    <el-dialog v-model="exportDialog" :title="`导出 .holzyn · ${infoForm.name || ''}`" width="520px">
      <el-alert type="info" :closable="false" show-icon
        title="导出 = 项目基本信息/世界观/角色/对话/知识/记忆/行动/事件/人群/世界时钟/项目级设置 + 可选 API 敏感数据 + 可选密码加密（PBKDF2 + AES-GCM）。"
        description="默认不携带任何 API 密钥，可安全分享/拷贝；勾选「包含敏感数据」后建议设置密码加密。" />
      <el-form label-width="150px" style="margin-top: 14px">
        <el-form-item label="包含 API 敏感数据">
          <el-switch v-model="exportForm.includeSensitive" />
        </el-form-item>
        <el-form-item v-if="exportForm.includeSensitive" label="密码加密（可选）">
          <el-input v-model="exportForm.password" type="password" show-password placeholder="设置后导入需输入此密码才能恢复 API 配置" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="exportDialog = false">取消</el-button>
        <el-button type="primary" :loading="exportForm.loading" @click="doExportPackage">导出</el-button>
      </template>
    </el-dialog>

    <!-- 导入对话框 -->
    <el-dialog v-model="importDialog" title="导入 .holzyn 项目包" width="520px">
      <el-alert type="info" :closable="false" show-icon
        title="导入后将完整还原项目（世界观/角色/对话/知识/记忆/设置）；已导入过的项目（相同 projectUid）会被幂等拦截。" />
      <el-upload drag :auto-upload="false" :limit="1" accept=".holzyn,.zip" :on-change="onImportFileChange" :on-remove="() => (importForm.file = null)" style="margin-top: 14px">
        <el-icon class="el-icon--upload"><HIcon name="UploadFilled" /></el-icon>
        <div class="el-upload__text">拖拽 .holzyn 文件到此处，或 <em>点击选择</em></div>
      </el-upload>
      <el-form label-width="90px" style="margin-top: 12px">
        <el-form-item label="密码（可选）">
          <el-input v-model="importForm.password" type="password" show-password placeholder="包内含密码加密的 API 配置时填写" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importDialog = false">取消</el-button>
        <el-button type="primary" :loading="importForm.loading" :disabled="!importForm.file" @click="doImportPackage">导入项目</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 设置-通用设置（模块化重构 V1.0 + 主题与外观；双模式改造 V1.2）。
 * <p>职责：项目/全局双模式通用设置——
 * 项目模式（/project/:id/settings/general）：本地账户 + 主题与外观 + 基本信息 + 世界时钟 + 群聊配置 + 导入/导出 + 危险区；
 * 全局模式（/settings/general，首页顶栏「设置」进入）：仅本地账户 + 主题与外观（无项目覆盖）+ 群聊配置（全局），
 * 并提示项目专属设置在项目空间内配置。</p>
 * <p>数据来源：projects、world-clock、group-chat/config 现有接口；主题配置走本地 localStorage（useTheme）。</p>
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchProject, updateProject, deleteProject,
  fetchWorldClock, updateWorldClock,
  fetchGroupChatConfig, saveGroupChatConfig,
  fetchWorldSetting,
  exportProjectPackage, importProjectPackage
} from '@/shared/api'
import { useTheme, THEME_PRESETS } from '@/shared/theme'

const route = useRoute()
const router = useRouter()
/** 当前项目 ID：项目空间内存在；全局设置（首页 /settings）为 null（隐藏项目专属卡片） */
const projectId = computed(() => (route.params.id ? Number(route.params.id) : null))

// embedded：内嵌到设置弹窗（GlobalSettingsDialog）时隐藏「全局设置（首页）」引导卡片，避免与弹窗说明重复
defineProps({ embedded: { type: Boolean, default: false } })

// ===== 主题与外观 =====
const themeApi = useTheme()
const theme = themeApi.theme
const presets = THEME_PRESETS
const worldGenre = ref(null)
/** 当前项目主色覆盖（存在时为该值，否则空；全局设置无项目覆盖） */
const projectOverride = computed(() => (projectId.value ? theme.value.overrides[String(projectId.value)] || '' : ''))
const accentModel = ref(theme.value.accent)
const projectOverrideModel = ref(projectOverride.value || theme.value.accent)

/** 切换深浅模式（radio change 触发） */
function onModeChange() { themeApi.setMode(theme.value.mode) }

/** 选择个性化主色（预设或自由取色） */
function onPickAccent(color) {
  if (!color) return
  themeApi.setAccent(color)
  accentModel.value = color
}

/** 开关题材联动 */
function onGenreFollow() { themeApi.setGenreFollow(theme.value.genreFollow) }

/** 设置本项目主色覆盖 */
function onProjectOverride(color) {
  if (!color || !projectId.value) return
  themeApi.setProjectOverride(projectId.value, color)
  projectOverrideModel.value = color
}

/** 重置本项目主色覆盖（回退到题材联动/默认色） */
function onResetProjectOverride() {
  if (!projectId.value) return
  themeApi.removeProjectOverride(projectId.value)
  projectOverrideModel.value = theme.value.accent
}

const infoForm = reactive({ name: '', code: '', summary: '' })
const savingInfo = ref(false)

const clockForm = reactive({ rate: 24, paused: false, worldStartAt: null, worldStartGameHour: 0 })
const savingClock = ref(false)

const groupReplies = ref(5)

const exportDialog = ref(false)
const importDialog = ref(false)
// 导出/导入表单状态（V2.0 真实功能）
const exportForm = reactive({ includeSensitive: false, password: '', loading: false })
const importForm = reactive({ file: null, password: '', loading: false })

/** 加载全部通用设置数据 */
async function loadAll() {
  // 全局设置（无项目）仅加载用户级/全局项：群聊配置；项目专属数据由项目空间内设置页承载
  if (projectId.value) {
    try {
      const p = await fetchProject(projectId.value)
      Object.assign(infoForm, { name: p.name, code: p.code, summary: p.summary })
    } catch (_) { /* 忽略 */ }
    try {
      const c = await fetchWorldClock(projectId.value)
      if (c) Object.assign(clockForm, { rate: c.rate || 24, paused: !!c.paused, worldStartAt: c.worldStartAt, worldStartGameHour: c.worldStartGameHour || 0 })
    } catch (_) { /* 忽略 */ }
    // 当前项目世界观题材（主题联动展示）
    try {
      const w = await fetchWorldSetting(projectId.value)
      worldGenre.value = w?.genre || null
    } catch (_) { /* 忽略 */ }
  }
  try {
    const r = await fetchGroupChatConfig()
    groupReplies.value = r.maxReplies || 5
  } catch (_) { /* 默认 5 */ }
}

/** 保存项目信息 */
async function saveInfo() {
  if (!projectId.value) return
  if (!infoForm.name.trim()) return ElMessage.warning('项目名称不能为空')
  savingInfo.value = true
  try {
    await updateProject(projectId.value, { name: infoForm.name.trim(), code: infoForm.code || null, summary: infoForm.summary || null })
    ElMessage.success('项目信息已保存')
  } catch (e) { ElMessage.error(e.message || '保存失败') } finally { savingInfo.value = false }
}

/** 保存世界时钟 */
async function saveClock() {
  if (!projectId.value) return
  savingClock.value = true
  try {
    await updateWorldClock(projectId.value, {
      rate: clockForm.rate,
      paused: clockForm.paused,
      worldStartAt: clockForm.worldStartAt || undefined,
      worldStartGameHour: clockForm.worldStartGameHour
    })
    ElMessage.success('世界时钟已保存')
  } catch (e) { ElMessage.error(e.message || '保存失败') } finally { savingClock.value = false }
}

/** 保存群聊回复上限（当前为全局配置） */
async function saveGroupReplies() {
  try {
    const r = await saveGroupChatConfig({ maxReplies: groupReplies.value })
    groupReplies.value = r.maxReplies
    ElMessage.success(`每轮回复上限已设为 ${r.maxReplies}`)
  } catch (e) { ElMessage.error(e.message || '保存失败') }
}

/** 打开导出对话框 */
function openExport() {
  Object.assign(exportForm, { includeSensitive: false, password: '', loading: false })
  exportDialog.value = true
}

/** 打开导入对话框 */
function openImport() {
  Object.assign(importForm, { file: null, password: '', loading: false })
  importDialog.value = true
}

/** 导入文件选择回调 */
function onImportFileChange(file) {
  importForm.file = file.raw
}

/** 导出 .holzyn 包（下载） */
async function doExportPackage() {
  if (!projectId.value) return
  exportForm.loading = true
  try {
    const blob = await exportProjectPackage(projectId.value, exportForm.includeSensitive, exportForm.password || undefined)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${infoForm.name || 'project'}.holzyn`
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('已导出 .holzyn 项目包')
    exportDialog.value = false
  } catch (e) { ElMessage.error(e.message || '导出失败') }
  finally { exportForm.loading = false }
}

/** 导入 .holzyn 包（幂等拦截 + 密码可选） */
async function doImportPackage() {
  if (!importForm.file) return
  importForm.loading = true
  try {
    const res = await importProjectPackage(importForm.file, importForm.password || undefined)
    ElMessage.success(`项目「${res.name}」导入成功（角色 ${res.characterCount} 位）`)
    importDialog.value = false
    router.replace('/')
  } catch (e) { ElMessage.error(e.message || '导入失败') }
  finally { importForm.loading = false }
}

/** 删除项目（二次确认，软删）后返回画廊 */
async function doDeleteProject() {
  if (!projectId.value) return
  try {
    await ElMessageBox.confirm(`确认删除项目「${infoForm.name}」？此操作不可恢复。`, '删除确认', { type: 'warning' })
    await deleteProject(projectId.value)
    ElMessage.success('项目已删除')
    router.replace('/')
  } catch (_) { /* 用户取消或失败 */ }
}

onMounted(loadAll)
</script>

<style scoped>
.general-settings { display: flex; flex-direction: column; gap: 16px; }
.gs-card { padding: 18px 22px; }
.gs-card.danger { border-color: var(--state-danger-bg); }
.gs-title { font-weight: 700; color: var(--text-primary); margin-bottom: 6px; }
.gs-desc { font-size: 0.8rem; color: var(--text-secondary); margin-bottom: 10px; }
.gs-form { padding-top: 6px; max-width: 720px; }
.gs-hint { margin-left: 10px; font-size: 0.75rem; color: var(--text-secondary); }
.gs-hint-btn { margin-left: 10px; }
/* 主题色板 */
.theme-swatches { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.swatch {
  width: 24px; height: 24px; border-radius: 999px; border: 2px solid transparent;
  cursor: pointer; padding: 0; transition: transform .15s var(--dsw-ease), box-shadow .15s var(--dsw-ease);
  box-shadow: var(--shadow-lv1);
}
.swatch:hover { transform: scale(1.15); }
.swatch.active { border-color: var(--text-primary); box-shadow: 0 0 0 2px var(--bg-layer-1), var(--shadow-lv2); }
.io-ops { display: flex; gap: 10px; margin-bottom: 12px; }
.io-alert { }
</style>
