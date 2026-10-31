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
  <!--
    世界初始化向导（重构版）。
    三区布局：① 顶部总览（总进度 + 结论）② 左侧竖向步骤条（10 步，严格顺序） ③ 右侧当前步面板（内容/生成/日志）。
    严格顺序：只有第 N 步之前的所有「必需」步骤都完成，本步的「执行」按钮才可点（后端还有一道硬门禁）。
  -->
  <AppShell
    mode="global"
    brand-label="HolzynActor"
    :breadcrumb="complete ? '初始化完成' : '世界初始化'"
    :show-sidebar="false"
    :show-back="true"
    :back-to="`/world/${projectId}/sandbox`"
    back-title="返回世界"
  >
    <template #topbar-actions>
      <el-radio-group v-model="rebuild" size="small" :disabled="!!running">
        <el-radio-button :value="false">跳过已生成</el-radio-button>
        <el-radio-button :value="true">全量重建</el-radio-button>
      </el-radio-group>
      <el-button size="small" :loading="running === '__all__'" :disabled="!!running" @click="runRemaining">
        <el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;一键续跑剩余
      </el-button>
      <el-button type="primary" size="small" @click="goWorld">进入世界</el-button>
    </template>

    <div class="content-col init-view">
      <PageHeader
        size="sm"
        :title="`${worldTitle} · 世界初始化`"
        desc="10 步严格顺序初始化：只有上一步必需内容生成完毕，下一步才允许执行。每步单独执行、可重跑、可选步可跳过。"
      >
        <template #actions>
          <el-button size="small" :loading="statusLoading" @click="loadAll">
            <el-icon><HIcon name="Refresh" /></el-icon>&nbsp;刷新状态
          </el-button>
        </template>
      </PageHeader>

      <!-- ① 总览：总进度 + 结论 -->
      <section class="overview tech-card">
        <div class="ov-head">
          <div class="ov-title">
            <span class="ov-name">初始化进度</span>
            <el-tag v-if="status" size="small" :type="overallTagType" effect="plain">{{ overallLabel }}</el-tag>
          </div>
          <span class="ov-count">必需 {{ status?.requiredDoneCount ?? 0 }}/{{ status?.requiredTotalCount ?? 0 }} · 全部 {{ status?.doneCount ?? 0 }}/{{ status?.totalCount ?? 10 }}</span>
        </div>
        <el-progress
          :percentage="percent"
          :status="complete ? (degraded ? 'warning' : 'success') : (failedCount ? 'exception' : '')"
          :stroke-width="10"
        />
        <div class="ov-msg">
          <span class="ov-msg-text">{{ status?.message || (statusLoading ? '正在读取初始化状态…' : '尚未读取到初始化状态') }}</span>
          <span v-if="currentStep" class="ov-next">下一步：{{ currentStep.label }}</span>
        </div>
      </section>

      <!-- ② + ③ 步骤条 + 当前步面板 -->
      <div class="init-body">
        <aside class="step-rail tech-card">
          <div class="rail-head">
            <span class="rail-title">初始化步骤</span>
            <span class="rail-tip">点选查看 · 严格顺序</span>
          </div>
          <button
            v-for="s in steps" :key="s.code"
            type="button" class="step-item"
            :class="{ 'is-active': selectedCode === s.code, 'is-done': isDone(s), 'is-failed': s.status === 'FAILED', 'is-running': running === s.code, 'is-locked': !canRun(s) && !isDone(s) }"
            @click="selectedCode = s.code"
          >
            <span class="step-idx">{{ s.index }}</span>
            <span class="step-body">
              <span class="step-label">
                {{ s.label }}
                <em class="step-req" :class="{ 'is-optional': s.optional }">{{ s.required ? '必需' : '可选' }}</em>
              </span>
              <span class="step-state">{{ stateText(s) }}</span>
            </span>
            <span class="step-dot" :class="dotClass(s)" />
          </button>
        </aside>

        <section class="step-panel">
          <!-- 当前步详情 + 操作 -->
          <div class="panel-card tech-card">
            <div class="panel-head">
              <div class="panel-title">
                <span class="panel-idx">第 {{ selected.index }} 步</span>
                <h3>{{ selected.label }}</h3>
                <el-tag size="small" :type="selected.required ? 'warning' : 'info'" effect="plain">{{ selected.required ? '必需步骤' : '可选步骤' }}</el-tag>
                <el-tag size="small" :type="statusTagType(selected)" effect="plain">{{ stateText(selected) }}</el-tag>
              </div>
              <div class="panel-metrics">
                <span v-if="selected.itemCount">产出 <b>{{ selected.itemCount }}</b></span>
                <span v-if="selected.attemptCount">尝试 <b>{{ selected.attemptCount }}</b> 次</span>
                <span v-if="selected.finishedAt">完成于 {{ fmtTime(selected.finishedAt) }}</span>
              </div>
            </div>
            <p class="panel-desc">{{ stepHint(selected.code) }}</p>
            <div v-if="selected.message" class="panel-msg" :class="{ 'is-error': selected.status === 'FAILED' }">
              {{ selected.message }}
            </div>

            <div class="panel-actions">
              <el-button
                type="primary"
                :loading="running === selected.code"
                :disabled="!!running || !canRun(selected)"
                @click="runSelected"
              >
                <el-icon><HIcon name="VideoPlay" /></el-icon>&nbsp;{{ isDone(selected) ? '重跑本步' : '执行本步' }}
              </el-button>
              <el-button
                v-if="selected.optional && !isDone(selected)"
                :disabled="!!running || !canRun(selected)"
                @click="skipSelected"
              >
                跳过此步
              </el-button>
              <el-button v-if="selected.code === 'characters'" :disabled="!!running" @click="$router.push(`/world/${projectId}/characters`)">
                查看角色卡
              </el-button>
              <el-button v-if="selected.code === 'relations'" :disabled="!!running" @click="$router.push(`/world/${projectId}/topology`)">
                查看关系拓扑
              </el-button>
              <span class="panel-gate">{{ gateTip(selected) }}</span>
            </div>
          </div>

          <!-- 世界观设定步：内置世界设定表单 + 世界名/简介（该页覆盖"世界内容"的全部生成入口） -->
          <div v-if="selected.code === 'worldview'" class="panel-card tech-card">
            <WorldSettingForm
              :model="worldForm"
              :project-id="projectId"
              title="世界观设定"
              desc="初始化的地基：后续地点/角色卡/关系/世界时间都从这里抽取。改了记得保存。"
            />
            <div class="meta-block">
              <div class="meta-head">
                <span class="meta-title">画廊展示：世界名与世界简介</span>
                <el-button size="small" :loading="metaLoading" :disabled="!!running" @click="suggestMeta">
                  <el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;AI 生成建议
                </el-button>
              </div>
              <div class="meta-grid">
                <div class="meta-cell">
                  <div class="meta-label">世界名（2-{{ WORLD_NAME_MAX }} 字）</div>
                  <el-input v-model="metaForm.name" :maxlength="WORLD_NAME_MAX" show-word-limit placeholder="世界画廊卡片标题" />
                </div>
                <div class="meta-cell">
                  <div class="meta-label">世界简介（≤{{ WORLD_SUMMARY_MAX }} 字）</div>
                  <el-input v-model="metaForm.summary" type="textarea" :rows="3" :maxlength="WORLD_SUMMARY_MAX" show-word-limit placeholder="一句话说清「这是什么世界 + 最独特的冲突」" />
                </div>
              </div>
              <div class="meta-tip">AI 建议口径：名字 6-14 字、简介 60-100 字；回填后可自行改写，保存取你改后的值。</div>
            </div>
            <div class="panel-actions">
              <el-button type="primary" :loading="savingWorld" @click="saveWorld">
                <el-icon><HIcon name="Check" /></el-icon>&nbsp;保存世界观与世界名
              </el-button>
              <span class="panel-gate">保存后再点上方「执行本步」校验世界观是否满足初始化条件</span>
            </div>
          </div>

          <!-- 执行日志 -->
          <div class="console-card tech-card">
            <div class="console-head">
              <span>执行日志</span>
              <span class="console-tip">后端 SSE 实时输出（含每步 AI 调用与失败原因）</span>
            </div>
            <WorkflowConsole ref="consoleRef" />
          </div>
        </section>
      </div>
    </div>
  </AppShell>
</template>

<script setup>
/**
 * 世界初始化向导页（重构：一键黑盒 → 单步可控向导）。
 *
 * <p><b>为什么要重构成向导</b>：改前是「进页自动跑 8 步全自动」，任何一步 AI 失败都被静默吞掉、
 * 用户只看到最后一个"初始化完成"标签——但世界其实是空的（地点 0、角色 0、时间未设）。
 * 现在：状态由后端实时数据校验（不信任"点过开始按钮"），每一步都能单独执行/重跑/跳过，
 * 严格顺序由后端硬门禁保证（前置必需步骤没完成会直接 400「请先完成第 K 步」），
 * 前端把不可执行的按钮置灰并提示卡在哪一步。</p>
 *
 * <p><b>页面自给自足</b>：第 1 步「世界观设定」内嵌共享的世界设定表单（与新建世界向导同一份组件），
 * 以及画廊展示用的世界名/简介（AI 生成建议 + 手改 + 落库），所以"世界内容"的全部生成入口都在这一页。</p>
 *
 * <p>进入方式：新建世界向导完成后跳转、画廊卡片"继续初始化"、仪表盘初始化入口、世界详情页横幅。</p>
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  fetchProject, fetchWorldSetting, saveWorldSetting,
  fetchWorldInitStatus, runInitStepStream, skipInitStep, initWorkflowStream,
  suggestWorldMeta, saveWorldMeta,
  WORLD_NAME_MAX, WORLD_SUMMARY_MAX
} from '@/shared/api'
import AppShell from '@/shared/layout/AppShell.vue'
import PageHeader from '@/shared/layout/PageHeader.vue'
import WorldSettingForm from '@/shared/components/WorldSettingForm.vue'
import WorkflowConsole from '@/features/gallery/components/WorkflowConsole.vue'

const route = useRoute()
const router = useRouter()
const projectId = Number(route.params.id)

/** 各步"这一步到底生成什么"的说明（后端 VO 不带 desc，前端按编码给文案） */
const STEP_HINTS = {
  worldview: '校验世界观设定是否达标：名称非空 + 至少 3 个分节（地理/势力/规则/文化/历史/补充）有内容。本步不调用 AI；不达标时下方表单可直接补写并保存。',
  locations: 'AI 从「地理设定」中提取世界观地点（城市/遗迹/地标…），去重后写入地点表。地点是后续地图、角色驻留、行动判定的底座。',
  characters: 'AI 按世界观为已有角色生成角色卡（性格/目标/说话风格/知识面…），角色卡是对话与决策的输入。',
  npcs: 'AI 建立字段字典并生成 5 个普通 NPC（路人/守卫/商贩…），用于让世界"有人"。',
  relations: 'AI 依据角色卡生成角色之间的关系拓扑（亲疏/敌对/血缘），关系是群聊与剧情冲突的来源。',
  family: '可选：补全开局家族谱系、婚姻与亲子关系、基因数据（需要角色卡已生成）。',
  world_map: '可选：按世界观生成世界地图与角色住所（区域/道路/建筑/驻留点）。生成耗时较长。',
  world_time: 'AI 推断故事开局的年/月/日/时，作为世界时间的起点（唯一权威 = 世界时钟表）。',
  macro: '可选：生成宏观社会模板（人口/经济/社会结构的粗粒度基线），供仪表盘与统计使用。',
  knowledge: '可选：把世界观与角色卡向量化写入知识库，供 AI 检索。未配置向量服务时可跳过，不影响世界运行。'
}

const steps = ref([])
const status = ref(null)
const statusLoading = ref(false)
const running = ref('')      // 正在执行的步骤编码（'__all__' 表示一键续跑）
const rebuild = ref(false)
const selectedCode = ref('worldview')
const consoleRef = ref(null)
/** 是否已完成首次"自动选中未完成步骤"（有它才不会把用户的手动选择覆盖掉） */
let selectionInited = false

const worldForm = reactive({ name: '', genre: '', tone: 'standard', era: '', geography: '', factions: '', magicSystem: '', culture: '', history: '', freeText: '' })
const metaForm = reactive({ name: '', summary: '' })
const project = ref(null)
const savingWorld = ref(false)
const metaLoading = ref(false)

/** 当前选中步骤（状态未加载时用步骤编码兜底） */
const selected = computed(() => steps.value.find((s) => s.code === selectedCode.value)
  || steps.value[0]
  || { code: 'worldview', index: 1, label: '世界观设定', required: true, optional: false, status: 'PENDING', satisfied: false, itemCount: 0, attemptCount: 0, message: '', finishedAt: null })

const complete = computed(() => !!status.value?.complete)
const degraded = computed(() => !!status.value?.degraded)
const failedCount = computed(() => steps.value.filter((s) => s.status === 'FAILED').length)
const worldTitle = computed(() => status.value?.worldName || project.value?.name || '世界')

/** 总进度百分比（必需步骤权重更高：必需全完成即 100%，避免可选步让人以为没跑完） */
const percent = computed(() => {
  const s = status.value
  if (!s || !s.requiredTotalCount) return 0
  if (s.complete) return 100
  return Math.min(99, Math.round((s.requiredDoneCount / s.requiredTotalCount) * 100))
})

const overallLabel = computed(() => {
  const s = status.value
  if (!s) return '读取中'
  if (s.complete) return s.degraded ? '已完成（降级）' : '已完成'
  return s.initialized ? '进行中' : '未开始'
})

const overallTagType = computed(() => {
  const s = status.value
  if (!s) return 'info'
  if (s.complete) return s.degraded ? 'warning' : 'success'
  return s.initialized ? 'primary' : 'info'
})

/** 第一个未满足的必需步骤（总览里显示"下一步"） */
const currentStep = computed(() => steps.value.find((s) => s.required && !isDone(s)) || null)

/** 步骤是否已完成（后端把"数据已存在"抬成 DONE，故 DONE/SKIPPED 都算完成） */
function isDone(step) {
  return step.status === 'DONE' || step.status === 'SKIPPED' || step.satisfied === true
}

/** 严格顺序闸门：之前所有必需步骤都完成才允许执行本步 */
function canRun(step) {
  if (!step) return false
  return steps.value
    .filter((s) => s.index < step.index && s.required)
    .every((s) => isDone(s))
}

/** 闸门提示文案 */
function gateTip(step) {
  if (running.value) return '正在执行中，请等待本步结束'
  if (canRun(step)) {
    if (isDone(step)) return '已完成；重跑会重新生成该步数据（选「全量重建」会覆盖既有内容）'
    return step.optional ? '可选步骤：也可以跳过' : '前置必需步骤已完成，可以执行'
  }
  const block = steps.value.find((s) => s.index < step.index && s.required && !isDone(s))
  return block ? `需先完成第 ${block.index} 步「${block.label}」` : ''
}

function stateText(step) {
  if (!step) return ''
  if (running.value === step.code) return '执行中…'
  if (step.status === 'RUNNING') return '执行中（上次未收尾）'
  if (step.status === 'FAILED') return '失败'
  if (step.status === 'SKIPPED') return '已跳过'
  if (isDone(step)) return '已完成'
  return canRun(step) ? '待执行' : '等待前置步骤'
}

function statusTagType(step) {
  if (!step) return 'info'
  if (step.status === 'FAILED') return 'danger'
  if (step.status === 'RUNNING') return 'primary'
  if (step.status === 'SKIPPED') return 'warning'
  if (isDone(step)) return 'success'
  return 'info'
}

function dotClass(step) {
  if (step.status === 'FAILED') return 'is-failed'
  if (step.status === 'SKIPPED') return 'is-skipped'
  if (isDone(step)) return 'is-done'
  return canRun(step) ? 'is-ready' : 'is-locked'
}

function fmtTime(v) {
  if (!v) return ''
  return String(v).replace('T', ' ').slice(0, 16)
}

function stepHint(code) {
  return STEP_HINTS[code] || ''
}

function now() {
  return new Date().toTimeString().slice(0, 8)
}

/** 读取状态 + 世界观 + 项目（不自动跑任何 AI：进页只读状态） */
async function loadAll() {
  statusLoading.value = true
  try {
    const s = await fetchWorldInitStatus(projectId)
    status.value = s
    steps.value = s.steps || []
    // 首次加载自动选中"第一个未完成的必需步骤"；之后保持用户当前选择
    if (!selectionInited) {
      const next = steps.value.find((x) => x.required && !isDone(x))
      selectedCode.value = next ? next.code : (steps.value[steps.value.length - 1]?.code || 'worldview')
      selectionInited = true
    }
  } catch (e) {
    ElMessage.error(e.message || '读取初始化状态失败')
  } finally {
    statusLoading.value = false
  }
}

/** 读取项目与世界设定（表单回填） */
async function loadWorldSetting() {
  try {
    project.value = await fetchProject(projectId)
  } catch (_) { /* 忽略：项目信息只用于标题兜底 */ }
  try {
    const w = await fetchWorldSetting(projectId)
    if (w) {
      Object.assign(worldForm, {
        name: w.name || '', genre: w.genre || '', tone: w.tone || 'standard', era: w.era || '',
        geography: w.geography || '', factions: w.factions || '',
        magicSystem: w.magicSystem || '', culture: w.culture || '',
        history: w.history || '', freeText: w.freeText || ''
      })
      if (!metaForm.name) metaForm.name = w.name || ''
    }
  } catch (_) { /* 无世界观时保持空表单 */ }
  if (!metaForm.name && project.value?.name) metaForm.name = project.value.name
  if (!metaForm.summary && project.value?.summary) metaForm.summary = project.value.summary
}

/** 保存世界观 + 世界名/简介（一次点击把两处都写掉，避免"改了名字没落库"） */
async function saveWorld() {
  if (!worldForm.name || !worldForm.name.trim()) {
    ElMessage.warning('请先填写世界观名称')
    return
  }
  savingWorld.value = true
  try {
    await saveWorldSetting(projectId, {
      name: worldForm.name.trim(),
      genre: worldForm.genre || null,
      tone: worldForm.tone || 'standard',
      era: worldForm.era || null,
      geography: worldForm.geography || null,
      factions: worldForm.factions || null,
      magicSystem: worldForm.magicSystem || null,
      culture: worldForm.culture || null,
      history: worldForm.history || null,
      freeText: worldForm.freeText || null
    })
    if (metaForm.name && metaForm.name.trim()) {
      await saveWorldMeta(projectId, { name: metaForm.name.trim(), summary: metaForm.summary || null })
    }
    ElMessage.success('世界观已保存')
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    savingWorld.value = false
  }
}

/** AI 生成世界名与简介建议（仅回填，不落库） */
async function suggestMeta() {
  metaLoading.value = true
  try {
    const out = await suggestWorldMeta(projectId)
    if (out?.name) metaForm.name = out.name
    if (out?.summary) metaForm.summary = out.summary
    ElMessage.success('已生成建议，可直接改写后保存')
  } catch (e) {
    ElMessage.error(e.message || '生成失败，请手动填写')
  } finally {
    metaLoading.value = false
  }
}

/** 执行当前选中步骤（含重跑：已完成时用 rebuild=true） */
async function runSelected() {
  const step = selected.value
  if (!step || running.value) return
  const useRebuild = isDone(step) ? true : rebuild.value
  await execStep(step, useRebuild)
}

/** 单步执行（SSE） */
async function execStep(step, useRebuild) {
  running.value = step.code
  consoleRef.value?.clear()
  consoleRef.value?.setRunning(true)
  consoleRef.value?.push({ level: 'info', time: now(), message: `[初始化] 开始执行第 ${step.index} 步「${step.label}」${useRebuild ? '（重建）' : ''}` })
  try {
    await runInitStepStream(projectId, step.code, {
      onLog: (log) => consoleRef.value?.push({ level: log.level || 'info', time: log.time || now(), message: log.message }),
      onStage: (st) => consoleRef.value?.push({ level: 'info', time: now(), message: `────────── 阶段 ${st.index}/${st.total}：${st.name} ──────────` })
    }, useRebuild)
    consoleRef.value?.push({ level: 'success', time: now(), message: `[初始化] 第 ${step.index} 步「${step.label}」完成` })
    ElMessage.success(`第 ${step.index} 步「${step.label}」已完成`)
  } catch (e) {
    consoleRef.value?.push({ level: 'error', time: now(), message: `[初始化] 失败：${e.message || '执行失败'}` })
    ElMessage.error(e.message || '本步执行失败')
  } finally {
    running.value = ''
    consoleRef.value?.setRunning(false)
    await loadAll()
  }
}

/** 跳过当前可选步骤 */
async function skipSelected() {
  const step = selected.value
  if (!step) return
  try {
    status.value = await skipInitStep(projectId, step.code)
    steps.value = status.value.steps || []
    ElMessage.success(`已跳过「${step.label}」`)
  } catch (e) {
    ElMessage.error(e.message || '跳过失败')
  }
}

/** 一键续跑剩余（后端按严格顺序跑，遇到必需步骤失败即中止） */
async function runRemaining() {
  if (running.value) return
  running.value = '__all__'
  consoleRef.value?.clear()
  consoleRef.value?.setRunning(true)
  consoleRef.value?.push({ level: 'info', time: now(), message: `[初始化] 一键续跑剩余的初始化步骤（${rebuild.value ? '全量重建' : '跳过已生成'}）` })
  try {
    await initWorkflowStream(projectId, rebuild.value, {
      onLog: (log) => consoleRef.value?.push({ level: log.level || 'info', time: log.time || now(), message: log.message }),
      onStage: (st) => consoleRef.value?.push({ level: 'info', time: now(), message: `────────── 阶段 ${st.index}/${st.total}：${st.name} ──────────` })
    })
    ElMessage.success('续跑结束，请查看各步状态')
  } catch (e) {
    consoleRef.value?.push({ level: 'error', time: now(), message: `[初始化] 中断：${e.message || '执行失败'}` })
    ElMessage.error(e.message || '续跑失败')
  } finally {
    running.value = ''
    consoleRef.value?.setRunning(false)
    await loadAll()
  }
}

function goWorld() {
  router.push(`/world/${projectId}/sandbox`)
}

onMounted(async () => {
  await Promise.all([loadAll(), loadWorldSetting()])
})
</script>

<style scoped>
.init-view { display: flex; flex-direction: column; gap: 12px; width: 100%; max-width: var(--page-w-content, 1200px); margin: 0 auto; }

/*
 * 可读性兜底（实测截图）：玻璃主题面板半透明，正文透出动画背景后对比度不足，
 * 13px 说明文字几乎看不清。本页是"逐步骤操作 + 读状态"的高密度文字页，统一给近白实底；不动全局令牌。
 */
.overview,
.step-rail,
.panel-card,
.console-card {
  background: #fdfefe;
}

/* ===== 总览 ===== */
.overview { padding: 14px 18px; display: flex; flex-direction: column; gap: 10px; }
.ov-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.ov-title { display: flex; align-items: center; gap: 8px; }
.ov-name { font-weight: 700; color: var(--text-primary); font-size: 0.95rem; }
.ov-count { margin-left: auto; font-size: 0.78rem; color: var(--text-regular); }
.ov-msg { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; font-size: 0.82rem; }
.ov-msg-text { color: var(--text-regular, var(--text-primary)); }
.ov-next { color: var(--brand-primary, #1d5cff); font-weight: 600; }

/* ===== 主体两栏 ===== */
.init-body { display: grid; grid-template-columns: 300px minmax(0, 1fr); gap: 12px; align-items: start; }

/* 左：竖向步骤条 */
.step-rail { padding: 12px 10px; display: flex; flex-direction: column; gap: 4px; position: sticky; top: 0; }
.rail-head { display: flex; align-items: baseline; gap: 8px; padding: 2px 6px 8px; }
.rail-title { font-weight: 700; color: var(--text-primary); font-size: 0.88rem; }
.rail-tip { font-size: 0.72rem; color: var(--text-regular); }
.step-item {
  display: flex; align-items: center; gap: 10px; width: 100%; text-align: left;
  padding: 8px 10px; border: 1px solid transparent; border-radius: var(--radius-md, 10px);
  background: transparent; cursor: pointer; transition: background .15s ease, border-color .15s ease;
}
.step-item:hover { background: var(--bg-hover, rgba(0, 0, 0, .04)); }
.step-item.is-active { background: var(--bg-active, rgba(29, 92, 255, .08)); border-color: var(--brand-primary, #1d5cff); }
.step-idx {
  flex-shrink: 0; width: 22px; height: 22px; border-radius: 50%;
  display: grid; place-items: center; font-size: 0.74rem; font-weight: 700;
  background: var(--bg-layer-2, rgba(0, 0, 0, .06)); color: var(--text-regular);
}
.step-item.is-done .step-idx { background: var(--el-color-success, #67c23a); color: #fff; }
.step-item.is-failed .step-idx { background: var(--el-color-danger, #f56c6c); color: #fff; }
.step-body { display: flex; flex-direction: column; gap: 2px; min-width: 0; flex: 1; }
.step-label { font-size: 0.84rem; color: var(--text-primary); display: flex; align-items: center; gap: 6px; }
/* 「必需」用琥珀色而不是红色：红色在这里容易被读成"出错了"，它表达的只是"不能跳过" */
.step-req { font-style: normal; font-size: 0.70rem; font-weight: 600; color: var(--el-color-warning, #b88230); background: rgba(230, 162, 60, .16); padding: 0 5px; border-radius: 4px; }
.step-req.is-optional { color: var(--text-regular); background: var(--bg-layer-2, rgba(0, 0, 0, .06)); }
.step-state { font-size: 0.72rem; color: var(--text-regular); }
.step-item.is-locked .step-label { color: var(--text-regular); }
.step-dot { flex-shrink: 0; width: 7px; height: 7px; border-radius: 50%; background: var(--border-l2, #ccd3dd); }
.step-dot.is-done { background: var(--el-color-success, #67c23a); }
.step-dot.is-failed { background: var(--el-color-danger, #f56c6c); }
.step-dot.is-skipped { background: var(--el-color-warning, #e6a23c); }
.step-dot.is-ready { background: var(--brand-primary, #1d5cff); }

/* 右：当前步面板 + 日志 */
.step-panel { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
.panel-card { padding: 16px 18px; display: flex; flex-direction: column; gap: 12px; }
.panel-head { display: flex; align-items: flex-start; gap: 10px; flex-wrap: wrap; }
.panel-title { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; min-width: 0; }
.panel-title h3 { margin: 0; font-size: 1rem; color: var(--text-primary); }
.panel-idx { font-size: 0.76rem; color: var(--text-regular); }
.panel-metrics { margin-left: auto; display: flex; gap: 12px; font-size: 0.74rem; color: var(--text-regular); flex-wrap: wrap; }
.panel-metrics b { color: var(--brand-primary, #1d5cff); }
.panel-desc { margin: 0; font-size: 0.82rem; line-height: 1.65; color: var(--text-regular, var(--text-regular)); }
.panel-msg {
  font-size: 0.8rem; line-height: 1.6; color: var(--text-regular, var(--text-primary));
  background: var(--bg-layer-1, rgba(0, 0, 0, .03)); border-left: 3px solid var(--border-l2, #ccd3dd);
  padding: 8px 10px; border-radius: 6px;
}
.panel-msg.is-error { color: var(--el-color-danger, #f56c6c); border-left-color: var(--el-color-danger, #f56c6c); background: rgba(245, 108, 108, .08); }
.panel-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.panel-gate { font-size: 0.74rem; color: var(--text-regular); }

/* 世界名/简介块 */
.meta-block { border-top: 1px dashed var(--border-l1, #e4e8ef); padding-top: 12px; display: flex; flex-direction: column; gap: 10px; }
.meta-head { display: flex; align-items: center; gap: 10px; }
.meta-title { font-weight: 700; font-size: 0.86rem; color: var(--text-primary); }
.meta-grid { display: grid; grid-template-columns: minmax(220px, 320px) minmax(0, 1fr); gap: 14px; }
.meta-cell { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.meta-label { font-size: 0.78rem; color: var(--text-regular); font-weight: 600; }
.meta-tip { font-size: 0.73rem; color: var(--text-regular); }

/* 日志控制台（WorkflowConsole 自身 height:100%，这里给确定高度，页面整体滚动） */
.console-card { padding: 12px 14px; display: flex; flex-direction: column; gap: 8px; }
.console-head { display: flex; align-items: baseline; gap: 10px; font-weight: 700; font-size: 0.86rem; color: var(--text-primary); }
.console-tip { font-size: 0.72rem; font-weight: 400; color: var(--text-regular); }
.console-card :deep(.wf-console) { height: 300px; }

@media (max-width: 1080px) {
  .init-body { grid-template-columns: 1fr; }
  .step-rail { position: static; }
  .meta-grid { grid-template-columns: 1fr; }
}
</style>
