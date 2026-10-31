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
  <!-- 统一外壳（global 模式）：顶栏与侧栏均来自 AppShell，本页不再自绘。 -->
  <AppShell
    mode="global"
    brand-label="HolzynActor"
    :breadcrumb="breadcrumb"
    :show-sidebar="false"
    :show-back="true"
    back-to="/"
    back-title="返回世界画廊"
  >
    <template #topbar-actions>
      <SettingsButton @click="openSettingsDialog()" />
    </template>

    <div class="np-wizard">
      <!-- 一页一头：本页唯一的页头（三个步骤共用，标题随步骤变化）；
           步骤内导航统一放在底部操作条，页头不再重复放「上一步」，避免一屏两个同名按钮 -->
      <PageHeader size="sm" :title="headerTitle" :desc="headerDesc" />

      <!-- 三步进度（可点回已完成的步骤，不能跳跃到未到达的步骤） -->
      <div class="wiz-steps tech-card">
        <button
          v-for="(s, i) in WIZ_STEPS"
          :key="s.key"
          class="wiz-step"
          :class="{ active: step === i, done: step > i }"
          :disabled="i > step || saving"
          @click="jumpTo(i)"
        >
          <span class="ws-idx">{{ i + 1 }}</span>
          <span class="ws-body">
            <span class="ws-name">{{ s.name }}</span>
            <span class="ws-desc">{{ s.desc }}</span>
          </span>
        </button>
      </div>

      <!-- ========== 第 1 步：创建方式 ========== -->
      <template v-if="step === 0">
        <div class="method-grid">
          <button
            v-for="m in METHODS"
            :key="m.key"
            class="method-card"
            :class="{ active: method === m.key }"
            @click="selectMethod(m.key)"
          >
            <span class="mc-icon"><HIcon :name="m.icon" /></span>
            <span class="mc-name">{{ m.name }}</span>
            <span class="mc-desc">{{ m.desc }}</span>
          </button>
        </div>

        <!-- 创意发散：嵌入生成面板，生成完成后由本页接管「回填表单」 -->
        <WorldgenPanel
          v-if="method === 'worldgen'"
          embedded
          @generated="onGenerated"
          @open-settings="openSettingsDialog"
        />
        <!-- 文件解析：嵌入解析面板，解析成功后由本页接管 -->
        <FileParsePanel
          v-else-if="method === 'file'"
          embedded
          @created="onParsed"
          @open-settings="openSettingsDialog"
        />
        <div v-else class="manual-tip tech-card">
          <div class="mt-title">手动填写：下一页写世界观</div>
          <div class="mt-desc">
            下一步会创建这个世界的草稿，并给出与「世界观详情」同款的七个分节表单（基本信息 / 地理 / 势力 / 规则 / 文化 / 历史 / 补充），
            填完可以直接进入初始化。若只想先占个位置，也可以只填世界名。
          </div>
          <div class="wiz-foot">
            <el-button type="primary" :loading="creating" @click="startManual">
              <el-icon><HIcon name="VideoPlay" /></el-icon>&nbsp;下一步：填写世界观
            </el-button>
          </div>
        </div>

        <div v-if="method === 'worldgen'" class="wiz-foot">
          <span class="foot-tip">生成完成后点「用这份世界观继续」，会回到本页第 2 步让你二次修改。</span>
        </div>
      </template>

      <!-- ========== 第 2 步：世界观设定（可二次修改）+ 世界名与世界简介 ========== -->
      <template v-else-if="step === 1">
        <!-- 世界名 / 世界简介：决定世界画廊卡片怎么显示 -->
        <div class="meta-card tech-card">
          <div class="meta-head">
            世界名与世界简介
            <span class="meta-tip">（世界画廊卡片上展示；AI 只给建议，最终以你改的为准）</span>
          </div>
          <div class="meta-row">
            <div class="meta-field">
              <div class="meta-label">
                世界名 <span class="req">*</span>
              </div>
              <el-input
                v-model="metaForm.name"
                :maxlength="WORLD_NAME_MAX"
                show-word-limit
                clearable
                placeholder="画廊显示的世界名，建议 6-14 字"
              />
            </div>
            <el-button class="meta-btn" :loading="metaLoading" :disabled="!projectId" @click="suggestMeta">
              <el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;AI 生成建议
            </el-button>
          </div>
          <div class="meta-field">
            <div class="meta-label">
              世界简介
            </div>
            <el-input
              v-model="metaForm.summary"
              type="textarea"
              :rows="3"
              :maxlength="WORLD_SUMMARY_MAX"
              show-word-limit
              placeholder="60-100 字，画廊卡片上的一两句话简介"
            />
          </div>
        </div>

        <!-- 与「手动添加 / 世界详情」同一份表单组件，AI 产物也回填到这里 -->
        <div class="form-card tech-card">
          <WorldSettingForm
            :model="worldForm"
            :project-id="projectId"
            title="世界观设定"
            desc="AI 生成的内容已回填到下面的分节，可逐节修改；保存后初始化会按这里的设定生成地点、角色、NPC、关系与世界时间。"
          />
        </div>

        <!-- 两个出口都**先落库**（用户要求）：想马上进去玩就进世界，否则继续走初始化向导 -->
        <div class="wiz-foot">
          <el-button :disabled="saving" @click="prevStep">上一步</el-button>
          <el-button :loading="saving" :disabled="saving" @click="saveAndNext(true)">
            <el-icon><HIcon name="VideoPlay" /></el-icon>&nbsp;保存并直接进入世界
          </el-button>
          <el-button type="primary" :loading="saving" @click="saveAndNext(false)">
            <el-icon><HIcon name="Check" /></el-icon>&nbsp;保存并继续（去初始化）
          </el-button>
        </div>
      </template>

      <!-- ========== 第 3 步：确认初始化 ========== -->
      <template v-else>
        <div class="ini-card tech-card">
          <div class="ini-head">
            {{ worldTitle }} · 初始化清单
            <span class="ini-tip">必需步骤全部完成才算「初始化完成」；可选步骤失败只算「降级」，不影响世界可用</span>
          </div>
          <div v-if="initLoading" v-loading="true" class="ini-loading" />
          <div v-else class="ini-list">
            <div v-for="s in initSteps" :key="s.code" class="ini-item">
              <span class="ini-idx">{{ s.index }}</span>
              <span class="ini-name">{{ s.label }}</span>
              <el-tag size="small" :type="s.required ? 'warning' : 'info'" effect="plain">
                {{ s.required ? '必需' : '可选' }}
              </el-tag>
              <span class="ini-state" :class="{ ok: s.satisfied }">{{ s.satisfied ? '已完成' : '待执行' }}</span>
            </div>
          </div>
          <div class="ini-note">
            初始化是<b>严格顺序</b>的：必须上一步完成（必需步骤）才能执行下一步；进入向导后逐步执行，也可以点「一键续跑剩余」。
          </div>
        </div>

        <div class="wiz-foot">
          <el-button @click="goSandbox">先进入世界（稍后初始化）</el-button>
          <el-button type="primary" @click="goInit">
            <el-icon><HIcon name="VideoPlay" /></el-icon>&nbsp;进入初始化向导
          </el-button>
        </div>
      </template>
    </div>

    <!-- 全局设置弹窗（项目空间外「设置」统一弹窗，零路由依赖） -->
    <GlobalSettingsDialog ref="settingsRef" />
  </AppShell>
</template>

<script setup>
/**
 * 新建世界（三步向导）。
 *
 * <p>重构背景：原来「手动添加 / 创意发散 / 文件解析」是三个互不相干的面板——手动添加自己建项目、
 * 创意发散自己建项目再跳世界详情、文件解析自己建项目再弹「是否初始化」，同一个"新建世界"有 3 套落库与跳转口径，
 * 用户根本不知道该走哪条；本次收口成三步：① 选创建方式 → ② 世界观设定（可改，含世界名/简介）→ ③ 确认初始化。</p>
 *
 * <p>三条路径都收口到<b>同一份</b> {@link WorldSettingForm} 与同一个初始化入口：
 * 手动=直接建草稿；创意发散=AI 生成后把分节回填表单（落库在保存时统一 confirm）；
 * 文件解析=解析建库后读回设定继续改。</p>
 */
import { ref, reactive, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppShell from '@/shared/layout/AppShell.vue'
import PageHeader from '@/shared/layout/PageHeader.vue'
import SettingsButton from '@/shared/components/SettingsButton.vue'
import GlobalSettingsDialog from '@/features/settings/views/GlobalSettingsDialog.vue'
import WorldSettingForm from '@/shared/components/WorldSettingForm.vue'
import WorldgenPanel from '../components/WorldgenPanel.vue'
import FileParsePanel from '../components/FileParsePanel.vue'
import { ensureWorldOpen } from '@/shared/worldSession'
import {
  createProject, fetchProject, fetchWorldSetting, saveWorldSetting,
  confirmWorldgenTask, fetchWorldInitStatus,
  suggestWorldMeta, saveWorldMeta, WORLD_NAME_MAX, WORLD_SUMMARY_MAX
} from '@/shared/api'

const route = useRoute()
const router = useRouter()

/** 三步定义（顺序即唯一允许的推进方向） */
const WIZ_STEPS = [
  { key: 'create', name: '创建方式', desc: '手动 / 创意发散 / 文件解析' },
  { key: 'world', name: '世界观设定', desc: '二次修改 + 世界名与简介' },
  { key: 'init', name: '开始初始化', desc: '按严格顺序生成世界运行内容' }
]

/** 三种创建方式（收口到同一份世界观表单） */
const METHODS = [
  { key: 'manual', name: '手动填写', icon: 'ic_public_edit', desc: '自己写世界观，最可控' },
  { key: 'worldgen', name: '创意发散', icon: 'ic_public_highlight', desc: '一段创意，AI 生成 6 维世界观' },
  { key: 'file', name: '文件解析', icon: 'ic_public_upload_filled', desc: '上传 txt / md，AI 分段建库' }
]

const step = ref(0)
const method = ref('manual')
const projectId = ref(null)
/** 创意发散任务 ID：落库要在「保存」时统一 confirm，避免 AI 产物覆盖用户手改 */
const pendingTaskId = ref(null)

const creating = ref(false)
const saving = ref(false)
const metaLoading = ref(false)
const initLoading = ref(false)
const initSteps = ref([])
const worldTitle = ref('')

const worldForm = reactive({
  name: '', genre: '', tone: 'standard', era: '', geography: '', factions: '',
  magicSystem: '', culture: '', history: '', freeText: ''
})
const metaForm = reactive({ name: '', summary: '' })

const headerTitle = computed(() => {
  const s = WIZ_STEPS[step.value]
  return `新建世界 · 第 ${step.value + 1} 步：${s.name}`
})
const headerDesc = computed(() => ({
  create: '先选一种创建方式：自己写、让 AI 生成、或上传已有世界观文件。三条路径最后都收口到同一份世界观表单与同一个初始化向导。',
  world: '改完世界观与世界名/简介再保存——初始化读的就是这里的设定，AI 生成的内容也回填在这里。',
  init: '世界观已保存。初始化按严格顺序生成世界运行所需内容（地点、角色、NPC、关系、地图、时间、知识库）。'
}[step.value]))

/** 当前面包屑（由路由 meta 标题去掉 · HolzynActor 后缀） */
const breadcrumb = computed(() => (route.meta?.title || '').replace(' · HolzynActor', ''))

// ===== 全局设置弹窗 =====
const settingsRef = ref(null)
function openSettingsDialog(tab = 'general') { settingsRef.value?.open(tab) }

/** 切换创建方式：已建过草稿时明确告知（避免用户以为切换会删掉草稿） */
async function selectMethod(key) {
  if (key === method.value) return
  if (projectId.value) {
    try {
      await ElMessageBox.confirm(
        '切换创建方式会放弃当前未保存的世界观内容；已经创建的草稿世界会保留在世界画廊，可稍后自行删除。',
        '切换创建方式', { confirmButtonText: '继续切换', cancelButtonText: '留在当前方式', type: 'warning' }
      )
    } catch (_) { return }
    resetDraft()
  }
  method.value = key
}

/** 清空草稿引用（不删库） */
function resetDraft() {
  projectId.value = null
  pendingTaskId.value = null
  worldTitle.value = ''
  Object.keys(worldForm).forEach((k) => { worldForm[k] = '' })
  // 程度有默认值（标准），清空后下拉不能变成空选项
  worldForm.tone = 'standard'
  metaForm.name = ''
  metaForm.summary = ''
}

/** 手动路径：先建草稿项目（有了 projectId 才能用「地点详情」与「AI 世界名建议」） */
async function startManual() {
  creating.value = true
  try {
    const p = await createProject({ name: '未命名世界', summary: '' })
    projectId.value = p.id
    worldTitle.value = p.name || '未命名世界'
    step.value = 1
  } catch (e) {
    ElMessage.error(e.message || '创建世界失败')
  } finally {
    creating.value = false
  }
}

/** 创意发散完成：把 6 维产物按同名键回填表单，等待用户二次修改 */
function onGenerated(payload) {
  if (!payload || !payload.projectId) return
  projectId.value = payload.projectId
  pendingTaskId.value = payload.taskId || null
  const secs = payload.sections || {}
  Object.keys(worldForm).forEach((k) => { if (secs[k] != null) worldForm[k] = String(secs[k]) })
  const suggested = (secs.name || payload.projectName || '').slice(0, WORLD_NAME_MAX)
  metaForm.name = suggested
  worldTitle.value = suggested || payload.projectName || '未命名世界'
  // 简介留给「AI 生成建议」，用户也可以直接手写
  metaForm.summary = ''
  step.value = 1
  ElMessage.success('世界观已生成，已回填到表单，可逐节修改后保存')
}

/** 文件解析完成：读回已落库的世界观继续改 */
async function onParsed(payload) {
  if (!payload || !payload.projectId) return
  projectId.value = payload.projectId
  worldTitle.value = payload.worldName || payload.projectName || '未命名世界'
  await loadSetting()
  step.value = 1
  ElMessage.success('文件已解析并建库，已载入世界观，可逐节修改后保存')
}

/** 读回项目与世界观的现存值（文件解析 / 回退重进时用） */
async function loadSetting() {
  if (!projectId.value) return
  try {
    const [p, w] = await Promise.all([
      fetchProject(projectId.value).catch(() => null),
      fetchWorldSetting(projectId.value).catch(() => null)
    ])
    if (w) Object.keys(worldForm).forEach((k) => { worldForm[k] = w[k] == null ? '' : String(w[k]) })
    // 老世界的 tone 是空的：下拉默认落在「标准」（后端口径也是空=标准），不能让下拉显示空
    if (!worldForm.tone) worldForm.tone = 'standard'
    metaForm.name = (p?.name || w?.name || '').slice(0, WORLD_NAME_MAX)
    metaForm.summary = p?.summary || ''
  } catch (_) { /* 读不到就让用户手填 */ }
}

/** AI 生成世界名与世界简介（不写库，回填表单供改） */
async function suggestMeta() {
  if (!projectId.value) return
  metaLoading.value = true
  try {
    const out = await suggestWorldMeta(projectId.value)
    if (out?.name) metaForm.name = String(out.name).slice(0, WORLD_NAME_MAX)
    if (out?.summary) metaForm.summary = String(out.summary).slice(0, WORLD_SUMMARY_MAX)
    ElMessage.success('已根据当前世界观生成建议，可直接修改')
  } catch (e) {
    ElMessage.error(e.message || 'AI 生成世界名与简介失败')
  } finally {
    metaLoading.value = false
  }
}

/** 保存世界观 + 世界名/简介 → 进入第 3 步 */
async function saveAndNext(enterWorld = false) {
  if (!projectId.value) return ElMessage.warning('还没有创建世界，请先回到第 1 步')
  if (!(metaForm.name || '').trim()) return ElMessage.warning('请填写世界名（世界画廊里显示的名字）')
  saving.value = true
  try {
    // 创意发散：先让后端把 6 维产物落库（保留地点合并逻辑），再用用户改后的表单覆盖
    if (pendingTaskId.value) {
      await confirmWorldgenTask(pendingTaskId.value)
      pendingTaskId.value = null
    }
    // 世界观写的是**世界库**：必须先把这个世界挂载起来，否则写入会落到未挂载/错误的数据源
    await ensureWorldOpen(projectId.value)
    const finalName = metaForm.name.trim().slice(0, WORLD_NAME_MAX)
    const payload = { ...worldForm, name: worldForm.name?.trim() || finalName }
    await saveWorldSetting(projectId.value, payload)
    await saveWorldMeta(projectId.value, { name: finalName, summary: metaForm.summary || '' })
    if (!worldForm.name) worldForm.name = finalName
    worldTitle.value = finalName
    ElMessage.success('世界观与世界名已保存')
    if (enterWorld) {
      // 「保存并直接进入世界」：同样先落库，再跳世界，跳过初始化
      router.push(`/world/${projectId.value}/sandbox`)
      return
    }
    step.value = 2
    await loadInitStatus()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

/** 读取初始化清单（第 3 步展示"接下来会生成什么"） */
async function loadInitStatus() {
  if (!projectId.value) return
  initLoading.value = true
  try {
    const s = await fetchWorldInitStatus(projectId.value)
    initSteps.value = s?.steps || []
  } catch (_) {
    initSteps.value = []
  } finally {
    initLoading.value = false
  }
}

/** 步骤回退（只向前回退，不跳过） */
function prevStep() {
  if (saving.value) return
  if (step.value > 0) step.value -= 1
}

/** 点击进度条回退 */
function jumpTo(i) {
  if (saving.value || i > step.value) return
  step.value = i
}

/** 进入初始化向导 */
function goInit() {
  if (!projectId.value) return
  router.replace(`/world/${projectId.value}/init`)
}

/** 先进入世界（稍后自己回来初始化） */
function goSandbox() {
  if (!projectId.value) return
  router.replace(`/world/${projectId.value}/sandbox`)
}
</script>

<style scoped>
/* 页面自身不再提供滚动（AppShell .shell-content 是唯一滚动容器），只做限宽与纵向节奏 */
.np-wizard {
  width: 100%;
  max-width: var(--page-w-content, 1200px);
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

/* ===== 三步进度 ===== */
.wiz-steps { display: flex; align-items: stretch; gap: 10px; padding: 12px 14px; }

.wiz-step {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-md, 10px);
  background: var(--bg-card, transparent);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.18s, background 0.18s;
}
.wiz-step:disabled { cursor: not-allowed; opacity: 0.65; }
.wiz-step.active { border-color: var(--brand-primary); background: var(--brand-soft, rgba(64, 158, 255, 0.08)); }
.wiz-step.done { border-color: var(--success, #67c23a); }
.ws-idx {
  width: 24px; height: 24px; flex-shrink: 0;
  display: inline-flex; align-items: center; justify-content: center;
  border-radius: 50%;
  background: var(--bg-muted, rgba(0, 0, 0, 0.06));
  color: var(--text-regular);
  font-size: 0.78rem; font-weight: 700;
}
.wiz-step.active .ws-idx { background: var(--brand-primary); color: #fff; }
.wiz-step.done .ws-idx { background: var(--success, #67c23a); color: #fff; }
.ws-body { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.ws-name { font-size: 0.90rem; font-weight: 600; color: var(--text-primary); }
.ws-desc { font-size: 0.78rem; color: var(--text-regular); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* ===== 创建方式卡片 ===== */
.method-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.method-card {
  display: flex; flex-direction: column; gap: 6px;
  padding: 16px 18px;
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-lg, 14px);
  background: var(--bg-card, transparent);
  cursor: pointer; text-align: left;
  transition: border-color 0.18s, box-shadow 0.18s, transform 0.18s;
}
.method-card:hover { border-color: var(--brand-primary); transform: translateY(-1px); }
.method-card.active { border-color: var(--brand-primary); box-shadow: 0 0 0 2px var(--brand-soft, rgba(64, 158, 255, 0.18)); }
.mc-icon { font-size: 1.2rem; color: var(--brand-primary); }
.mc-name { font-size: 0.96rem; font-weight: 700; color: var(--text-primary); }
.mc-desc { font-size: 0.84rem; color: var(--text-regular); line-height: 1.6; }

/* 手动路径提示卡 */
.manual-tip { padding: 16px 20px; display: flex; flex-direction: column; gap: 8px; }
.mt-title { font-size: 0.9rem; font-weight: 700; color: var(--text-primary); }
.mt-desc { font-size: 0.86rem; color: var(--text-regular); line-height: 1.75; }

/* ===== 第 2 步：世界名/简介 + 世界观表单 ===== */
.meta-card { padding: 16px 20px; display: flex; flex-direction: column; gap: 12px; }
.meta-head { font-size: 0.9rem; font-weight: 700; color: var(--text-primary); }
.meta-tip { font-size: 0.78rem; color: var(--text-regular); font-weight: 400; margin-left: 6px; }
.meta-row { display: flex; align-items: flex-end; gap: 12px; }
.meta-row .meta-field { flex: 1; min-width: 0; }
.meta-btn { flex-shrink: 0; }
.meta-field { display: flex; flex-direction: column; gap: 6px; }
.meta-label { font-size: 0.8rem; font-weight: 600; color: var(--text-primary); display: flex; align-items: center; gap: 6px; }
.meta-count { font-size: 0.78rem; color: var(--text-regular); font-weight: 400; margin-left: auto; }
.req { color: var(--danger, #f56c6c); }
.form-card { padding: 8px 12px 12px; }

/* ===== 第 3 步：初始化清单 ===== */
.ini-card { padding: 16px 20px; display: flex; flex-direction: column; gap: 12px; }
.ini-head { font-size: 0.9rem; font-weight: 700; color: var(--text-primary); }
.ini-tip { font-size: 0.78rem; color: var(--text-regular); font-weight: 400; margin-left: 6px; }
.ini-loading { height: 120px; }
.ini-list { display: flex; flex-direction: column; gap: 2px; }
.ini-item { display: flex; align-items: center; gap: 10px; padding: 8px 6px; border-bottom: 1px solid var(--border-light); }
.ini-item:last-child { border-bottom: none; }
.ini-idx { width: 20px; text-align: center; font-size: 0.80rem; color: var(--text-regular); }
.ini-name { font-size: 0.84rem; color: var(--text-primary); min-width: 120px; }
.ini-state { margin-left: auto; font-size: 0.80rem; color: var(--text-regular); }
.ini-state.ok { color: var(--success, #67c23a); }
.ini-note { font-size: 0.84rem; color: var(--text-regular); line-height: 1.7; }

/* ===== 底部操作条 ===== */
.wiz-foot { display: flex; align-items: center; justify-content: flex-end; gap: 10px; padding: 4px 2px 8px; }
/*
 * 主题里的默认按钮是浅底浅字（实测 #eef1f3 底 + 近白字），压在半透明内容层的深色区域上
 * 几乎看不见。次级操作统一给深字 + 白底，保证可读。
 */
.wiz-foot :deep(.el-button:not(.el-button--primary)) {
  color: #2f3841 !important;
  background: #fdfefe !important;
  border-color: #c6ccd2 !important;
}
/*
 * 这条提示直接坐在页面底纹上，而这一层是液态玻璃画布、越往下越暗，
 * 深色小字裸放会糊成一片（实测底部 rgb ≈74,80,88 时几乎不可见）→ 给近白药丸底。
 */
.foot-tip {
  font-size: 0.82rem; color: #3f4a56; margin-right: auto; line-height: 1.6;
  background: #fdfefe; border: 1px solid rgba(0, 0, 0, 0.06); border-radius: 8px; padding: 7px 12px;
}

@media (max-width: 900px) {
  .method-grid { grid-template-columns: 1fr; }
  .meta-row { flex-direction: column; align-items: stretch; }
  .wiz-steps { flex-direction: column; }
}

/* 用白实底覆盖上面的半透明面板背景：必须放在样式块末尾，否则被前面的 .method-card 规则盖掉 */
/*
 * 可读性兜底（实测截图）：液态玻璃主题的面板是"半透明白 + 动画背景画布"，
 * 卡片内容透出下方灰底后，13px 正文的对比度会掉到 3:1 以下，截图里几乎看不清。
 * 本页文字密度高，统一给近白实底，保证正文对比度 ≥ 7:1；不改全局令牌，避免影响其它页面。
 */
.wiz-steps,
.method-card,
.manual-tip,
.meta-card,
.form-card,
.ini-card {
  background: #fdfefe;
}

/*
 * 嵌入面板（创意发散 / 文件解析）同理：它们的卡片用的是全局 .tech-card 半透明底，
 * 在向导里同样会透出灰色背景画布，标题与说明文字发灰。这里只在本页兜底成近白实底。
 */
.np-wizard :deep(.tech-card),
.np-wizard :deep(.block-card),
.np-wizard :deep(.nodes-card),
.np-wizard :deep(.node-item),
.np-wizard :deep(.fail-item),
.np-wizard :deep(.preview-block),
.np-wizard :deep(.file-info-card),
.np-wizard :deep(.expand-bar) {
  background: #fdfefe;
}

/* 嵌入时表单不必再压到 720，跟着卡片走，减少右侧空白 */
.np-wizard :deep(.gen-form) {
  max-width: none;
}

/* 日志控制台：头部/正文都是半透明底，嵌在向导里会整块发灰，空态提示几乎看不见 */
.np-wizard :deep(.wf-console) {
  background: #fdfefe;
}
.np-wizard :deep(.wf-console-head) {
  background: #f4f7f7;
}
.np-wizard :deep(.wf-console-empty) {
  color: #5b6672;
}
</style>
