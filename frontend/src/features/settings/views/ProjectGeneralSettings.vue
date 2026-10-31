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
  <div class="settings-stack">
    <!-- 统一页头（项目/全局两态说明不同；内嵌设置弹窗时由弹窗标题与 Tab 标签承担，不再重复） -->
    <PageHeader
      v-if="!embedded"
      title="通用设置"
      :desc="projectId
        ? '项目级设置：基本信息、世界时钟、导入导出与危险区操作；用户级偏好（账户、主题外观）在此一并可达。'
        : '全局（用户级）设置：本地账户、主题与外观、群聊每轮回复上限。项目专属设置请进入对应项目空间 → 设置。'"
    />
    <!-- 全局设置提示：仅首页/未进入项目时显示（项目专属设置在项目空间内；内嵌弹窗时由弹窗顶部说明承担） -->
    <div v-if="!projectId && !embedded" class="set-card">
      <div class="set-card-title">全局设置（首页）</div>
      <div class="set-card-desc">此处为「用户级」全局设置：本地账户、主题与外观、群聊每轮回复上限（全局）。
        项目专属设置（基本信息 / 世界时钟 / 导入导出 / 危险区）请进入对应项目空间 → 设置 中配置。</div>
    </div>

    <!-- ⓪ 本地账户（本地单用户；按钮唤起独立填写弹窗——字段与新用户引导「我的形象」页一致：基本信息 + NPC 眼中的你 + 玩家 persona 六维 + AI 补充生成） -->
    <div class="set-card">
      <div class="set-card-title">本地账户</div>
      <div class="set-card-desc">本地个人账户信息（昵称 / 头像 / 个性签名 + 「NPC 眼中的你」：身份 / 职业 / 喜好 / 禁忌 / 个人档案 + 对谈「玩家 persona」六维）。
        这些信息会注入 NPC 对话上下文，让角色基于对你的了解做出定制化回应；所有字段选填，与新用户引导「我的形象」页填写一致。</div>
      <el-button type="primary" plain @click="openAccountEditor"><el-icon><HIcon name="User" /></el-icon>&nbsp;编辑本地账户</el-button>
    </div>

    <!-- ⓪.5 主题与外观（双主题分层 + 可换肤主色 + 世界题材联动） -->
    <div class="set-card">
      <div class="set-card-title">主题与外观</div>
      <div class="set-card-desc" v-if="projectId">深浅主题（可跟随系统）、个性化主色，以及按本项目世界观题材自动换肤（恋爱→粉、科幻→蓝青、奇幻→紫…）。</div>
      <div class="set-card-desc" v-else>深浅主题（可跟随系统）、个性化主色（全局默认）；世界题材联动在进入项目时按题材自动应用。</div>
      <el-form label-width="140px" class="set-form">
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
            <span class="set-hint">当前主色：<b class="accent-text">{{ projectOverride || theme.accent }}</b></span>
          </div>
        </el-form-item>
        <el-form-item label="世界题材换肤">
          <el-switch v-model="theme.genreFollow" @change="onGenreFollow" />
          <span class="set-hint" v-if="worldGenre">当前题材：<b>{{ worldGenre }}</b>{{ theme.genreFollow ? '（已自动应用题材主色）' : '（未启用）' }}</span>
          <span class="set-hint" v-else-if="projectId">本项目暂无世界观题材</span>
          <span class="set-hint" v-else>进入项目后按世界观题材自动换肤</span>
        </el-form-item>
        <el-form-item v-if="projectId" label="本项目主色">
          <div class="theme-swatches">
            <el-color-picker v-model="projectOverrideModel" size="small" @change="onProjectOverride" />
            <el-button v-if="projectOverride" size="small" text type="danger" @click="onResetProjectOverride">重置为默认/题材色</el-button>
            <span class="set-hint">仅影响本项目的界面主色（覆盖题材联动）</span>
          </div>
        </el-form-item>
        <el-form-item label="云间列车背景">
          <el-switch v-model="cloudTrainEnabled" @change="onCloudTrainToggle" />
          <span class="set-hint">{{ cloudTrainEnabled
            ? '全站动态背景：浅色=落日云间 / 深色=夜空'
            : '已关闭：背景为纯色（跟随深浅主题），组件仍保持液态玻璃材质' }}</span>
        </el-form-item>
        <el-form-item v-if="cloudTrainEnabled" label="背景渲染分辨率">
          <el-slider
            v-model="cloudTrainResolution"
            :min="0.25"
            :max="1"
            :step="0.05"
            show-input
            :show-input-controls="false"
            style="width: 260px"
            @change="onCloudTrainResolution"
          />
          <span class="set-hint">{{ Math.round(cloudTrainResolution * 100) }}%（调低可降低 GPU 负担；GPU 吃力时渲染器还会自动降低噪点细节）</span>
        </el-form-item>
      </el-form>
    </div>

    <!-- ① 基本信息（仅项目空间） -->
    <div v-if="projectId" class="set-card">
      <div class="set-card-title">基本信息</div>
      <el-form :model="infoForm" label-width="140px" class="set-form">
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

    <!-- ② 世界时钟（仅项目空间）
         V2.2 重构：原先「持续运行」与「模拟状态」两个开关都描述"世界是否在推进"，语义重叠、用户无法分辨谁说了算。
         收敛为单一开关「世界运行」——开=常驻自转，关=完全停止（引擎不再推进本项目）；
         「暂停」只在世界地图页顶栏作为随手冻结的快捷按钮（从属于世界运行），设置页不再重复放一个暂停开关。 -->
    <div v-if="projectId" class="set-card">
      <div class="set-card-title">世界时钟</div>
      <div class="set-card-desc">「世界运行」是唯一的世界开关：开启后世界按「时间流速」自动运转（即使不打开页面也在推进）；关闭后引擎完全停止推进本项目。想临时冻结一下，用世界地图顶栏的「⏸ 暂停」。锚点校准折叠在「高级设置」中，一般无需修改。</div>

      <!-- 世界运行状态条：唯一权威状态（运行中 / 已暂停 / 已停止） -->
      <div class="clock-status-bar" :class="'is-' + clockStatus.kind">
        <span class="clock-status-dot" aria-hidden="true" />
        <b class="clock-status-text">{{ clockStatus.text }}</b>
        <span class="clock-status-desc">{{ clockStatus.desc }}</span>
      </div>

      <el-form label-width="140px" class="set-form">
        <!-- 主开关：世界运行（常驻自转），立即生效 -->
        <el-form-item label="世界运行">
          <el-switch v-model="clockForm.worldRunning" :loading="savingClock" @change="onWorldRunningChange" />
          <span class="set-hint">
            {{ clockForm.worldRunning ? '开启：关闭页面世界也会按流速自动推进' : '关闭：引擎完全不推进本项目，需重新开启' }}
          </span>
        </el-form-item>

        <!-- 时间流速：立即生效 -->
        <el-form-item label="时间流速">
          <el-input-number v-model="clockForm.rate" :min="1" :max="240" style="width: 140px" @change="onRateChange" />
          <span class="set-hint">×{{ clockForm.rate }}：1 真实小时推进 {{ clockForm.rate }} 个游戏小时（默认 24 = 1 真实小时推进 1 游戏日）</span>
        </el-form-item>

        <!-- 高级设置：锚点校准（需点保存生效） -->
        <el-collapse class="clock-adv" v-model="clockAdvOpen">
          <el-collapse-item title="高级设置：世界时间锚点" name="adv">
            <div class="set-tip clock-adv-tip">一般无需修改。锚点用于校准「真实时刻 ↔ 世界时间」的对应关系：修改「起始游戏时刻」会把世界时间直接拨到指定时刻。</div>
            <el-form-item label="锚点真实时刻">
              <el-date-picker v-model="clockForm.worldStartAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="锚点时间（世界时间起算点）" style="width: 240px" />
            </el-form-item>
            <el-form-item label="起始游戏时刻">
              <el-input-number v-model="clockForm.worldStartGameHour" :min="0" style="width: 140px" />
              <span class="set-hint">锚点对应的游戏起始小时数</span>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="savingClock" @click="saveClock">保存锚点设置</el-button>
            </el-form-item>
          </el-collapse-item>
        </el-collapse>
      </el-form>
    </div>

    <!-- ②.5 智能体运行模式（，仅项目空间） -->
    <div v-if="projectId" class="set-card">
      <div class="set-card-title">智能体运行模式</div>
      <div class="set-card-desc">项目级世界引擎运行模式三档，控制「该项目下的 AI 投入」：
        <b>极简</b>=系统自动能力降级/关闭（目标/日计划/反思机制化，预算压到最低，省钱优先）；
        <b>普通（默认）</b>=现状 + 机制化日计划（普通 NPC 机制，特殊 NPC 正常 AI）；
        <b>极致</b>=全量 AI（普通 NPC 也 AI 生成当日计划，预算拉满、反思优先）。特殊 NPC 仍尊重各自的脑力配置（不强制覆盖）。
        <br /><b>M5 AI 强度</b>为独立四档维度（与运行模式正交可叠加：运行模式管引擎节奏，AI 强度管智能体 AI 使用密度）：
        <b>纯规则</b>=零 AI（宏观层全机制化）；<b>轻 AI</b>=预算减半；<b>标准（默认）</b>=阈值 AI 叙事 + 常规 AI；
        <b>全 AI</b>=宏观事件全部 AI 叙事、叙事拉满。微观层（实时决策/记忆提炼等）全量接线列入 M5.5 阶段。</div>
      <el-form label-width="140px" class="set-form">
        <el-form-item label="运行模式">
          <el-select v-model="engineForm.runMode" style="width: 220px" :loading="engineLoading">
            <el-option v-for="m in RUN_MODES" :key="m.value" :label="m.label" :value="m.value" />
          </el-select>
          <span class="set-hint" v-if="engineForm.runModeLabel">当前：<b>{{ engineForm.runModeLabel }}</b></span>
        </el-form-item>
        <el-form-item label="AI 强度（M5）">
          <el-select v-model="engineForm.aiIntensity" style="width: 220px" :loading="engineLoading">
            <el-option v-for="l in AI_INTENSITY_LEVELS" :key="l.value" :label="l.label" :value="l.value" />
          </el-select>
          <span class="set-hint" v-if="engineForm.aiIntensityLabel">当前：<b>{{ engineForm.aiIntensityLabel }}</b></span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="savingEngine" @click="saveEngine">保存运行模式 / AI 强度</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- ③ 群聊配置 -->
    <div class="set-card">
      <div class="set-card-title">群聊配置</div>
      <div class="set-card-desc">群聊每轮回复上限（1~20）。</div>
      <el-form label-width="140px" class="set-form">
        <el-form-item label="每轮回复上限">
          <el-input-number v-model="groupReplies" :min="1" :max="20" style="width: 140px" />
          <el-button class="set-hint-btn" type="primary" text @click="saveGroupReplies">保存</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- ④ 世界备份（仅项目空间）：v1.1.2 起导出/导入在世界画廊里做（导出要求世界已关闭） -->
    <div v-if="projectId" class="set-card">
      <div class="set-card-title">世界备份（导出 / 导入）</div>
      <div class="set-card-desc">
        世界以「世界目录」为单位打包成 <code>.holzyn</code>（世界库 + 向量库 + 地图图块 + 清单）。
        导出要求世界已关闭——请先「返回画廊」再用卡片菜单里的「导出世界」；导入同在该页（会把包作为一个<b>新世界</b>加入，不影响已有世界）。
      </div>
      <div class="io-ops">
        <el-button type="primary" @click="goToLibrary"><el-icon><HIcon name="Back" /></el-icon>&nbsp;返回世界画廊</el-button>
      </div>
    </div>

    <!-- ⑤ 危险区（仅项目空间） -->
    <div v-if="projectId" class="set-card is-danger">
      <div class="set-card-title">危险区</div>
      <div class="set-card-desc">删除项目为软删（不可恢复），删除后该项目的世界观/角色/对话/知识/记忆等数据将不可访问。</div>
      <el-button type="danger" plain @click="doDeleteProject"><el-icon><HIcon name="Delete" /></el-icon>&nbsp;删除项目</el-button>
    </div>

    <!-- 本地账户填写弹窗（独立弹窗，避免内嵌表单挤占设置页版式；字段与首次引导「我的形象」页一致）。
         尺寸与其它设置弹窗统一：.settings-dialog（同宽同高、正文独立滚动、头脚常驻）；
         append-to-body 让它逃出设置弹窗正文的 overflow 裁剪；align-center 保证小屏垂直居中。 -->
    <el-dialog
      v-model="accountDialog"
      title="编辑本地账户"
      width="900px"
      align-center
      append-to-body
      :close-on-click-modal="false"
      class="h-dialog settings-dialog account-editor-dialog"
      destroy-on-close
    >
      <div class="ac-dialog-desc">与首次引导「我的形象」页填写一致：基本信息 + NPC 眼中的你 + 玩家 persona 六维 + AI 补充生成。保存后即时生效。</div>
      <LocalAccountForm @saved="onAccountSaved" />
      <template #footer>
        <el-button @click="accountDialog = false">取消</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 设置-通用设置。
 * <p>职责：项目/全局双模式通用设置——
 * 项目模式（/world/:id/settings/general）：本地账户 + 主题与外观 + 基本信息 + 世界时钟 + 群聊配置 + 导入/导出 + 危险区；
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
  fetchWorldEngineConfig, updateWorldEngineConfig
} from '@/shared/api'
import { useTheme, THEME_PRESETS } from '@/shared/theme'
import { leaveToLibrary } from '@/shared/worldSession'
import PageHeader from '@/shared/layout/PageHeader.vue'
import LocalAccountForm from '@/shared/components/LocalAccountForm.vue'

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

// ===== 云间列车液态玻璃背景（全站偏好：是否渲染 + 渲染分辨率） =====
const cloudTrainEnabled = ref(theme.value.cloudTrain?.enabled !== false)
const cloudTrainResolution = ref(theme.value.cloudTrain?.resolution ?? 0.25)

/** 开关云间列车背景（关闭后玻璃材质保留、背景为纯色） */
function onCloudTrainToggle() {
  themeApi.setCloudTrainEnabled(cloudTrainEnabled.value)
}

/** 设置背景渲染分辨率（0.25~1） */
function onCloudTrainResolution(v) {
  themeApi.setCloudTrainResolution(v)
}

/** 本地账户表单保存完成（弹窗内已提示成功并回填最新值 → 关闭弹窗） */
function onAccountSaved() {
  accountDialog.value = false
}

/** 打开本地账户填写弹窗 */
function openAccountEditor() {
  accountDialog.value = true
}

const infoForm = reactive({ name: '', code: '', summary: '' })
const savingInfo = ref(false)

const clockForm = reactive({ rate: 24, worldRunning: true, paused: false, worldStartAt: null, worldStartGameHour: 0 })
const savingClock = ref(false)
/** 「高级设置：世界时间锚点」折叠是否展开（默认收起，避免高级校准干扰常规操作） */
const clockAdvOpen = ref([])

/**
 * 世界时钟唯一权威状态（V2.2 收敛「持续运行/模拟状态」双开关的重叠语义）。
 * worldRunning=0 → 已停止（引擎完全不推进）；运行中且 paused → 已暂停（在世界地图页顶栏临时冻结的）；
 * 否则运行中。「暂停」不再有独立开关——世界运行关闭与暂停效果相同，暂停仅作为地图页的快捷冻结保留。
 */
const clockStatus = computed(() => {
  if (!clockForm.worldRunning) {
    return { kind: 'stopped', text: '已停止', desc: '世界运行已关闭：引擎完全不推进本项目' }
  }
  if (clockForm.paused) {
    return { kind: 'paused', text: '已暂停', desc: '世界时间已暂停（在世界地图页顶栏点「▶ 开始推进」恢复）' }
  }
  return { kind: 'running', text: '运行中', desc: '世界正在按流速自动推进（关闭页面也在运行）' }
})

// ===== 智能体运行模式（：minimal/standard/ultimate 三档，actor_world_engine.run_mode） =====
const RUN_MODES = [
  { value: 'minimal', label: '极简（省钱优先）' },
  { value: 'standard', label: '普通（标准）' },
  { value: 'ultimate', label: '极致（全量 AI）' }
]
// ===== AI 强度（独立四档维度，与 runMode 正交：runMode=引擎节奏，AI 强度=智能体 AI 使用密度） =====
const AI_INTENSITY_LEVELS = [
  { value: 'programmatic', label: '纯规则（零 AI）' },
  { value: 'light', label: '轻 AI（预算减半）' },
  { value: 'full', label: '标准（默认）' },
  { value: 'max', label: '全 AI（叙事拉满）' }
]
const engineForm = reactive({ runMode: 'standard', runModeLabel: '', aiIntensity: 'full', aiIntensityLabel: '' })
const engineLoading = ref(false)
const savingEngine = ref(false)

/** 加载世界引擎运行模式 + AI 强度 */
async function loadEngineConfig() {
  if (!projectId.value) return
  engineLoading.value = true
  try {
    const c = await fetchWorldEngineConfig(projectId.value)
    engineForm.runMode = c.runMode || 'standard'
    engineForm.runModeLabel = c.runModeLabel || ''
    engineForm.aiIntensity = c.aiIntensity || 'full'
    engineForm.aiIntensityLabel = c.aiIntensityLabel || ''
  } catch (_) { /* 引擎行懒创建，读取失败保持默认 */ }
  finally { engineLoading.value = false }
}

/** 保存运行模式 / AI 强度 */
async function saveEngine() {
  if (!projectId.value) return
  savingEngine.value = true
  try {
    const c = await updateWorldEngineConfig(projectId.value, {
      runMode: engineForm.runMode,
      aiIntensity: engineForm.aiIntensity
    })
    engineForm.runMode = c.runMode
    engineForm.runModeLabel = c.runModeLabel
    engineForm.aiIntensity = c.aiIntensity
    engineForm.aiIntensityLabel = c.aiIntensityLabel
    ElMessage.success(`运行模式「${c.runModeLabel}」· AI 强度「${c.aiIntensityLabel}」已保存`)
  } catch (e) { ElMessage.error(e.message || '保存失败') } finally { savingEngine.value = false }
}

const groupReplies = ref(5)

/** 本地账户填写弹窗（独立弹窗，字段与首次引导「我的形象」页一致） */
const accountDialog = ref(false)

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
      if (c) syncClockForm(c)
    } catch (_) { /* 忽略 */ }
    // 当前项目世界观题材（主题联动展示）
    try {
      const w = await fetchWorldSetting(projectId.value)
      worldGenre.value = w?.genre || null
    } catch (_) { /* 忽略 */ }
    await loadEngineConfig()
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

/** 把世界时钟 VO 同步回表单（加载 / 保存 / 失败回滚共用） */
function syncClockForm(c) {
  if (!c) return
  clockForm.rate = c.rate || 24
  clockForm.worldRunning = c.worldRunning === 1
  clockForm.paused = !!c.paused
  clockForm.worldStartAt = c.worldStartAt
  clockForm.worldStartGameHour = c.worldStartGameHour || 0
}

/** 重新拉取时钟（失败回滚为服务端实际状态） */
async function reloadClock() {
  try { syncClockForm(await fetchWorldClock(projectId.value)) } catch (_) { /* 忽略 */ }
}

/**
 * 世界运行主开关：立即生效（无需点保存）。
 * 关闭 = 引擎完全不推进本项目；开启 = 常驻自转（同时把暂停一并恢复，避免开启后仍卡在暂停态）。
 */
async function onWorldRunningChange(v) {
  savingClock.value = true
  try {
    const c = await updateWorldClock(projectId.value, { worldRunning: v ? 1 : 0, paused: v ? false : undefined })
    syncClockForm(c)
    ElMessage.success(v ? '世界运行已开启：世界常驻自转' : '世界运行已关闭：引擎不再推进本项目')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
    await reloadClock()
  } finally { savingClock.value = false }
}

/** 时间流速：立即生效 */
async function onRateChange(v) {
  if (v == null) return
  savingClock.value = true
  try {
    const c = await updateWorldClock(projectId.value, { rate: Number(v) })
    syncClockForm(c)
    ElMessage.success(`时间流速已设为 ×${Number(v)}`)
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
    await reloadClock()
  } finally { savingClock.value = false }
}

/** 保存锚点设置（高级校准；同时回传全部时钟字段做回声容忍，未变更字段不触发校准） */
async function saveClock() {
  if (!projectId.value) return
  savingClock.value = true
  try {
    const c = await updateWorldClock(projectId.value, {
      rate: clockForm.rate,
      paused: clockForm.paused,
      worldRunning: clockForm.worldRunning ? 1 : 0,
      worldStartAt: clockForm.worldStartAt || undefined,
      worldStartGameHour: clockForm.worldStartGameHour
    })
    syncClockForm(c)
    ElMessage.success('世界时钟锚点已保存')
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

/**
 * 回到世界画廊（导出/导入都在那里做：导出要求世界已关闭，所以本页不能直接导）。
 *
 * <p>走 {@link leaveToLibrary}：它会先 {@code POST /close} 压盘当前世界再跳，
 * 直接从设置页 `router.push('/')` 会让世界保持挂载——世界画廊里那张卡片会显示"运行中"，
 * 且用户再点"继续"时后端会回 409 WORLD_BUSY。</p>
 */
function goToLibrary() {
  leaveToLibrary(router)
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
/* V2.1：卡片/标题/描述/表单/提示一律走全局设置页规范（.settings-stack/.set-card 等），
   此处仅保留本页特有的控件样式。 */
/* 主题色板 */
.theme-swatches { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.swatch {
  width: 24px; height: 24px; border-radius: 999px; border: 2px solid transparent;
  cursor: pointer; padding: 0; transition: transform .15s var(--dsw-ease), box-shadow .15s var(--dsw-ease);
  box-shadow: var(--shadow-lv1);
}
.swatch:hover { transform: scale(1.15); }
.swatch.active { border-color: var(--text-primary); box-shadow: 0 0 0 2px var(--bg-layer-1), var(--shadow-lv2); }
.io-ops { display: flex; gap: var(--space-3, 12px); }
.set-hint-btn { margin-left: 10px; }
/* 世界时钟：状态条（V2.2 唯一权威状态：运行中 / 已暂停 / 已停止） */
.clock-status-bar {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  padding: 9px 14px; margin-bottom: 10px;
  border-radius: var(--radius-md);
  border: 1px solid var(--border-l1);
  background: var(--bg-layer-2);
}
.clock-status-dot { width: 9px; height: 9px; border-radius: 50%; flex-shrink: 0; }
.clock-status-bar.is-running { border-color: color-mix(in srgb, var(--state-success) 40%, transparent); }
.clock-status-bar.is-running .clock-status-dot { background: var(--state-success); box-shadow: 0 0 6px var(--state-success); }
.clock-status-bar.is-paused { border-color: color-mix(in srgb, var(--el-color-warning) 45%, transparent); }
.clock-status-bar.is-paused .clock-status-dot { background: var(--el-color-warning); }
.clock-status-bar.is-stopped { border-color: color-mix(in srgb, var(--text-tertiary) 35%, transparent); }
.clock-status-bar.is-stopped .clock-status-dot { background: var(--text-tertiary); }
.clock-status-text { font-size: 0.9rem; font-weight: 700; color: var(--text-primary); }
.clock-status-desc { font-size: 0.78rem; color: var(--text-secondary); }
/* 高级锚点折叠：去默认描边，头部与常规行控件同高 */
.clock-adv { border: none; margin-top: 2px; }
.clock-adv :deep(.el-collapse-item__header) {
  height: auto; line-height: 1.5; padding: 6px 0;
  font-size: var(--density-font-sm, 0.82rem);
  font-weight: 600; color: var(--text-secondary);
  border-bottom: 1px dashed var(--border-l1);
}
.clock-adv :deep(.el-collapse-item__arrow) { color: var(--text-tertiary); }
.clock-adv-tip { display: block; margin-bottom: 12px; }
/* 本地账户填写弹窗：顶部说明小字 */
.ac-dialog-desc { font-size: 0.82rem; color: var(--text-secondary); line-height: 1.7; margin-bottom: 4px; }
</style>
