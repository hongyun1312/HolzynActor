<!--
  Copyright (c) 2026 HONGYUN
  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
  SPDX-License-Identifier: Apache-2.0
-->
<!--
  首次设置向导（新用户引导，v1.1.0-beta.2 重构：启动动画 → 连接模型 → 我的形象）。
  <p>三段式新流程（完全替换旧三步向导）：</p>
  <ul>
    <li><b>启动动画</b>（占位版，仅首次显示一次）：品牌 Logo + slogan「让世界像真人一样生活」，
        约 2.5 秒自动进入下一步，也可点击跳过（后期再精修动画）。</li>
    <li><b>连接模型</b>：用户级模型接口配置（复用 /api/model-apis；已配置则只读摘要 + 跳设置）。
        可跳过——未配置时进入「我的形象」后 AI 补充生成按钮会置灰提示先配置。</li>
    <li><b>我的形象</b>：对接原「初始化时填写个人信息表单」，重构布局独立成页——
        基本信息（昵称/头像/签名）+ NPC 眼中的你（身份/职业/喜好/禁忌/个人档案）+ 玩家 persona 六维
        （身份/背景/性格/说话风格/目标/能力）可编辑区 + 「AI 补充生成」：写一段自我介绍 →
        后端 AI 生成六维 → 回填表单预览 → 确认保存。保存同时写本地账户与玩家档案，
        身份/个人档案与 persona 的 identity/background 自动同步。</li>
  </ul>
  <p>完成后：标记向导完成 → 置位「新手引导待启动」→ 进入世界画廊，自动启动 7 步交互式引导
  （画廊 → 示例世界浮生小镇 · 青屿镇 → 世界内），可跳过、可重看。</p>
  <p>数据：useAccountStore（本地账户），保存走 /api/local-account；模型配置走 /api/model-apis
  （projectId 空 = 用户级默认）；玩家档案走 /api/player-profile；AI 补充生成走
  POST /api/player-profile/generate（新接口）。</p>
  <p><b>布局契约（开局配置页优化：顶部 ≤ 卡片 1/4）</b>：顶部区 = 品牌 + 阶段标题 + 紧凑分段步骤器
  + 一句话副标题 + 完成度。实测 1280×720 下 92~95px（卡片 523px）≈ 17.5%；改前是 311px / 632px = 49%
  （Logo 1.5rem + 居中 1.5rem 大标题 + 两行说明 + 97px 的 el-steps 步骤条，滚动区只剩 169px）。
  长解释一律下移为表单内 .fr-note 可展开块（原文保留、默认收起）；「我的形象」20+ 字段收敛为
  4 个 el-collapse 分区，默认只展开「基本信息」。改这里时不要恢复居中大标题 / 步骤条 description /
  平铺长表单，否则输入区又会被挤回半屏。</p>
-->
<template>
  <div class="firstrun-page glass-scene" :class="{ 'no-cloudtrain': !cloudTrainEnabled }">
    <!-- 云间列车动态背景（全站统一；可在「设置 → 主题与外观」关闭或调分辨率） -->
    <CloudTrainBackground v-if="cloudTrainEnabled" :overrides="cloudTrainOverrides" />
    <!--
      无边框窗口：本页不走 AppShell（全屏向导），所以自己提供窗口标题栏能力——
      顶部 52px 拖拽条（可拖动窗口/双击最大化）+ 右上角自绘三键。
      不给的话首启向导期间窗口既不能拖动也不能最小化（无系统标题栏）。
    -->
    <div class="fr-window-chrome">
      <div class="fr-window-drag" aria-hidden="true"></div>
      <WindowControls standalone />
    </div>
    <!-- ===== 阶段 0：启动动画（占位版，仅首次显示） ===== -->
    <div v-if="stage === 'splash'" class="fr-splash" @click="finishSplash">
      <div class="fr-splash-inner">
        <div class="fr-splash-logo">Holzyn<span class="fr-splash-logo-sub">Actor</span></div>
        <div class="fr-splash-ring" />
        <div class="fr-splash-title">让世界像真人一样生活</div>
        <div class="fr-splash-sub">每个角色都有自己的记忆、情绪与人生</div>
        <div class="fr-splash-skip">点击任意处进入</div>
      </div>
    </div>

    <!-- ===== 阶段 1 / 2：向导卡片（连接模型 → 我的形象） ===== -->
    <!-- 玻璃卡片：固定高度 + 弹窗内滚动，两阶段弹窗大小完全一致 -->
    <div v-else-if="stage === 'api' || stage === 'persona'" class="fr-card glass-panel">
      <!--
        顶部区（改前实测占卡片 49%，改后 ≤ 1/4）：第一行「品牌 + 当前阶段标题」+ 紧凑分段步骤器，
        第二行一句话副标题 + 完成度。原来的长段说明与步骤条 description 全部下移：
        长解释进 .fr-note（默认收起，原文保留）、分区说明进各折叠分区，信息不丢失但不再挤占输入区。
      -->
      <header class="fr-top">
        <div class="fr-top-row">
          <div class="fr-brand">
            <span class="fr-logo">Holzyn<span class="fr-logo-sub">Actor</span></span>
            <span class="fr-brand-sep" aria-hidden="true">/</span>
            <h1 class="fr-title">{{ stageTitle }}</h1>
          </div>
          <CompactStepper
            :items="stageItems"
            :active="stageIndex"
            clickable
            label="设置步骤"
            @select="goStage"
          />
        </div>
        <div class="fr-top-row fr-top-row--sub">
          <p class="fr-sub">{{ stageSub }}</p>
          <span
            v-if="stage === 'persona'"
            class="fr-count"
            :title="`已填写 ${filledCount} / ${totalCount} 个字段（全部选填）`"
          >已填 {{ filledCount }}/{{ totalCount }}</span>
          <span v-else-if="apiConfigured" class="fr-count">{{ apiCount }} 个接口已配置</span>
        </div>
      </header>

      <!-- 表单主体：唯一滚动区域（弹窗内滚动，页面本身不滚动） -->
      <div class="fr-body">
      <!-- ===== 阶段 1：连接模型（API 配置） ===== -->
      <div v-show="stage === 'api'" class="fr-step">
        <!-- 长解释下移到这里：默认收起、需要时展开（原文保留，信息不丢） -->
        <section class="fr-note" :class="{ 'is-open': noteOpen.api }">
          <button
            type="button"
            class="fr-note-head"
            :aria-expanded="noteOpen.api"
            @click="toggleNote('api')"
          >
            <span class="fr-note-title">为什么必须先配模型？</span>
            <span class="fr-note-chev" aria-hidden="true"></span>
          </button>
          <div v-show="noteOpen.api" class="fr-note-body">
            <p>
              世界的推进、角色的决策与对话全部依赖模型调用。这里配置的是一个<b>用户级</b>模型接口
              （所有项目共享），之后可在「设置 → API 配置」随时修改、新增多个并测试。
            </p>
            <p>不配模型时，世界会退回机械的作息循环——角色只会按表起床睡觉，非常僵硬、非常不真实。</p>
          </div>
        </section>

        <div class="fr-section">
          <div class="fr-section-title">配置模型接口</div>

          <!-- 已配置：只读摘要 + 去设置 -->
          <div v-if="apiConfigured" class="fr-api-done">
            <span class="fr-api-done-icon"><el-icon><HIcon name="Check" /></el-icon></span>
            <span class="fr-api-done-text">已配置 {{ apiCount }} 个模型接口：{{ apiSummary }}</span>
            <el-button link type="primary" @click="goSettingsApis">去设置管理</el-button>
          </div>

          <!-- 未配置：紧凑表单 -->
          <el-form v-else :model="apiForm" label-position="top" class="fr-form" @submit.prevent>
            <div class="fr-grid-2">
              <el-form-item label="名称">
                <el-input v-model="apiForm.name" placeholder="如：DeepSeek 主用" maxlength="50" clearable />
              </el-form-item>
              <el-form-item label="模型名（可空）">
                <el-input v-model="apiForm.model" placeholder="如 deepseek-chat" maxlength="100" clearable />
              </el-form-item>
            </div>
            <el-form-item label="Base URL（OpenAI 兼容协议）">
              <el-input v-model="apiForm.baseUrl" placeholder="如：https://api.deepseek.com/v1" maxlength="255" clearable />
            </el-form-item>
            <el-form-item label="API Key（加密存储）">
              <el-input v-model="apiForm.apiKey" type="password" show-password placeholder="填写你的 API Key" maxlength="512" clearable />
            </el-form-item>
            <div class="fr-api-ops">
              <el-button :loading="testing" @click="testApi">
                <el-icon><HIcon name="Connection" /></el-icon>&nbsp;测试连接
              </el-button>
              <el-button type="primary" :loading="savingApi" @click="saveApi">保存模型配置</el-button>
            </div>
          </el-form>
        </div>
      </div>

      <!-- ===== 阶段 2：我的形象（4 个折叠分区；默认只展开「基本信息」，避免 20+ 字段一次糊满） ===== -->
      <el-form v-show="stage === 'persona'" :model="form" label-position="top" class="fr-form" @submit.prevent>
        <el-collapse v-model="openSecs" class="fr-collapse">
          <el-collapse-item name="base">
            <template #title>
              <span class="fr-sec-title">基本信息</span>
              <span class="fr-sec-meta">{{ secCount.base.done }}/{{ secCount.base.total }}</span>
            </template>
            <div class="fr-grid-2">
              <el-form-item label="昵称（显示名）">
                <el-input v-model="form.nickname" placeholder="例如：林晚" maxlength="64" show-word-limit clearable />
              </el-form-item>
              <el-form-item label="头像">
                <div class="avatar-line">
                  <el-avatar :size="40" :src="form.avatarUrl" class="fr-avatar">
                    {{ (form.nickname || 'H').charAt(0) }}
                  </el-avatar>
                  <el-input v-model="form.avatarUrl" placeholder="头像图片地址（可留空）" clearable />
                </div>
              </el-form-item>
            </div>
            <el-form-item label="个性签名">
              <el-input v-model="form.signature" placeholder="一句话介绍自己（选填）" maxlength="255" show-word-limit clearable />
            </el-form-item>
          </el-collapse-item>

          <el-collapse-item name="npc">
            <template #title>
              <span class="fr-sec-title">NPC 眼中的你</span>
              <span class="fr-sec-meta">{{ secCount.npc.done }}/{{ secCount.npc.total }}</span>
            </template>
            <p class="fr-sub-desc">角色会把这些当作「对你的了解」：影响他们怎么称呼你、跟你聊什么、避开什么。</p>
            <div class="fr-grid-2">
              <el-form-item label="身份">
                <el-input v-model="form.identity" placeholder="例如：大学生 / 旅行者 / 编辑" maxlength="255" clearable />
              </el-form-item>
              <el-form-item label="职业">
                <el-input v-model="form.occupation" placeholder="例如：自由撰稿人" maxlength="255" clearable />
              </el-form-item>
              <el-form-item label="喜好">
                <el-input v-model="form.hobbies" placeholder="例如：咖啡、悬疑小说、深夜散步" maxlength="512" clearable />
              </el-form-item>
              <el-form-item label="禁忌（角色应避免冒犯）">
                <el-input v-model="form.taboos" placeholder="例如：不喜欢被问年龄" maxlength="512" clearable />
              </el-form-item>
            </div>
            <el-form-item label="个人档案（自由描述）">
              <el-input
                v-model="form.profileText"
                type="textarea"
                :rows="3"
                placeholder="你的背景、经历、性格、近期状态……角色会把它当作「对你的了解」来对话（选填）"
                clearable
              />
            </el-form-item>
          </el-collapse-item>

          <!-- 玩家 persona 六维（对谈模式） -->
          <el-collapse-item name="persona">
            <template #title>
              <span class="fr-sec-title">玩家 persona（对谈）</span>
              <span class="fr-sec-meta">{{ secCount.persona.done }}/{{ secCount.persona.total }}</span>
            </template>
            <p class="fr-sub-desc">对谈（静止世界聊天）以「玩家档案」身份与你对话：以下六维会注入对谈上下文，可手填，也可用下方「AI 补充生成」。</p>
            <div class="fr-grid-2">
              <el-form-item label="身份">
                <el-input v-model="persona.identity" placeholder="例如：旅行编辑" maxlength="255" clearable />
              </el-form-item>
              <el-form-item label="说话风格">
                <el-input v-model="persona.voice" placeholder="例如：慢条斯理、爱引经据典" maxlength="255" clearable />
              </el-form-item>
              <el-form-item label="性格">
                <el-input v-model="persona.personality" placeholder="例如：温和、慢热" maxlength="255" clearable />
              </el-form-item>
              <el-form-item label="目标">
                <el-input v-model="persona.goals" placeholder="例如：收集九州民间故事" maxlength="255" clearable />
              </el-form-item>
              <el-form-item label="能力">
                <el-input v-model="persona.ability" placeholder="例如：善写、善辨音" maxlength="255" clearable />
              </el-form-item>
              <el-form-item label="背景">
                <el-input v-model="persona.background" type="textarea" :rows="3" placeholder="你的经历与来处…" maxlength="500" show-word-limit clearable />
              </el-form-item>
            </div>
          </el-collapse-item>

          <!-- AI 补充生成（独立分区，展开后原地生成并回填上面的分区） -->
          <el-collapse-item name="ai">
            <template #title>
              <span class="fr-sec-title"><el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;AI 补充生成</span>
              <el-tag v-if="!apiConfigured" size="small" type="info" effect="plain">需先配置模型接口</el-tag>
            </template>
            <div class="fr-ai-body" :class="{ 'is-off': !apiConfigured }">
              <p class="fr-ai-desc">
                写一段你想成为的角色（或真实的自己），AI 会把它扩写为 persona 六维并回填到上面的分区，你确认后再保存。
              </p>
              <el-input
                v-model="selfIntro"
                type="textarea"
                :rows="3"
                maxlength="2000"
                show-word-limit
                placeholder="例如：我是旅行编辑，喜欢收集九州各地的民间故事，性格温和慢热，说话慢条斯理…"
              />
              <div class="fr-ai-ops">
                <el-button
                  type="primary"
                  plain
                  :disabled="!apiConfigured"
                  :loading="generating"
                  @click="aiGenerate"
                >
                  <el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;AI 补充生成
                </el-button>
                <el-button
                  v-if="generated"
                  size="small"
                  text
                  @click="clearGenerated"
                >
                  清空生成结果
                </el-button>
                <span class="fr-ai-hint">生成后请核对，再点「保存并进入世界画廊」</span>
              </div>
            </div>
          </el-collapse-item>
        </el-collapse>
      </el-form>

      </div><!-- /.fr-body -->

      <!-- 底部操作 -->
      <div class="fr-actions">
        <el-button v-if="stage === 'persona'" class="fr-back" text @click="stage = 'api'">上一步</el-button>
        <el-button v-else class="fr-skip" text @click="toPersona">跳过，直接设置形象</el-button>
        <span class="fr-spacer"></span>
        <el-button v-if="stage === 'api'" type="primary" class="fr-next" @click="toPersona">下一步</el-button>
        <template v-else>
          <el-button class="fr-skip" text @click="skip">以后再说</el-button>
          <el-button type="primary" class="fr-save" :loading="saving" @click="finish">
            保存并进入世界画廊
          </el-button>
        </template>
      </div>
      <p class="fr-tip">
        提示：本地数据只保存在你的电脑上；保存后我们会带你在世界画廊里快速逛逛示例世界。
        所有内容都可在「设置」中随时修改。
      </p>
    </div>
  </div>
</template>

<script setup>
/**
 * 首次设置向导（新用户引导，v1.1.0-beta.2 重构）。
 * <p>阶段机：splash（启动动画，仅首次）→ api（连接模型，可跳过）→ persona（我的形象，含 AI 补充生成）
 * → 保存并进入世界画廊（置位新手引导待启动标记，由 FirstRunGuide 接续 7 步引导）。</p>
 * <p>旧「开始使用」信息卡（时钟/成本/对谈/AI 助手）由新引导流程取代：完成本向导后
 * 进入世界画廊自动启动引导，引导内逐一步骤讲解这些能力。</p>
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAccountStore } from '@/shared/store'
import { useTheme } from '@/shared/theme'
import { savePlayerProfile, fetchPlayerProfile, fetchModelApis, createModelApi, testModelApi, generatePlayerProfile } from '@/shared/api'
import { armGuide } from '@/shared/onboarding/firstRunGuide'
import CloudTrainBackground from '@/shared/components/CloudTrainBackground.vue'
import CompactStepper from '@/shared/components/CompactStepper.vue'
import WindowControls from '@/shared/layout/WindowControls.vue'
import { useGlassScene } from '@/shared/composables/useGlassScene'

const router = useRouter()
const route = useRoute()
const account = useAccountStore()
const themeApi = useTheme()

/** 云间列车液态玻璃场景：给 body 挂玻璃浮层令牌（弹窗/消息等 Teleport 到 body 的部件） */
useGlassScene()

/** 云间列车背景是否渲染 + 渲染参数（与全站设置一致：设置页可开关/调分辨率） */
const cloudTrainEnabled = computed(() => themeApi.theme.value.cloudTrain?.enabled !== false)
const cloudTrainOverrides = computed(() => {
  const r = Number(themeApi.theme.value.cloudTrain?.resolution)
  return Number.isFinite(r) && r > 0 ? { resolution: r } : {}
})

const saving = ref(false)
const generating = ref(false)
const generated = ref(false)
/** 当前阶段：splash=启动动画 / api=连接模型 / persona=我的形象；'' = 初始化中（不渲染，避免闪帧） */
const stage = ref('')

/** 启动动画「本机已看过」标记（仅首次显示；刷新后不再重复） */
const SPLASH_SEEN_KEY = 'holzyn.splash.seen.v1'

const form = reactive({
  nickname: '',
  avatarUrl: '',
  signature: '',
  identity: '',
  occupation: '',
  hobbies: '',
  taboos: '',
  profileText: ''
})

/** 玩家 persona 六维（对谈模式；与账户「身份/个人档案」自动同步 identity/background） */
const persona = reactive({ identity: '', background: '', personality: '', voice: '', goals: '', ability: '' })

/** AI 补充生成的自我介绍输入 */
const selfIntro = ref('')

// ==================== 顶部区（紧凑页面头） ====================
/**
 * 两阶段步骤定义（紧凑分段器数据源）。
 * <p>改前这里是 `el-steps align-center` + 每步 description，光步骤条就 97px 且描述折成两行；
 * 现在只留标题，解释文案走 desc（仅作原生悬停提示，不占高度）。</p>
 */
const stageItems = [
  { key: 'api', title: '连接模型', desc: '配置用户级模型接口（可跳过）' },
  { key: 'persona', title: '我的形象', desc: '让 NPC 认识真实的你' }
]

/** 当前阶段索引（0 起，与分段器一致） */
const stageIndex = computed(() => (stage.value === 'persona' ? 1 : 0))
/** 当前阶段标题（左上行内标题，替代改前 1.5rem 的居中大标题） */
const stageTitle = computed(() => (stage.value === 'persona' ? '我的形象' : '连接模型'))
/** 当前阶段一句话副标题（严格一行：改前是 2 行居中说明，占掉半个输入区） */
const stageSub = computed(() => (stage.value === 'persona'
  ? '让 NPC 记住真实的你——全部选填，可写一段自我介绍让 AI 补全'
  : '配置一个用户级模型接口（所有项目共享），之后可在「设置 → API 配置」修改'))

/**
 * 折叠分区展开项（我的形象阶段）。
 * <p>只默认展开「基本信息」：改前 4 大块 20+ 字段平铺，1280×720 下要靠滚动才能看到头像/签名之外的字段。</p>
 */
const openSecs = ref(['base'])

/** 长解释的展开态（默认收起；原文保留在 .fr-note-body 内） */
const noteOpen = reactive({ api: false })
function toggleNote(key) {
  noteOpen[key] = !noteOpen[key]
}

/** 切到指定阶段（分段器点击回跳/前进） */
function goStage(i) {
  stage.value = i === 0 ? 'api' : 'persona'
}

/** 统计一组字段里已填的个数（去空格计非空，与保存逻辑口径一致） */
function countFilled(obj, keys) {
  return keys.filter((k) => String(obj[k] ?? '').trim()).length
}

/** 各分区已填/总数（分区标题右侧展示，让「选填」也有完成感） */
const secCount = computed(() => ({
  base: { done: countFilled(form, ['nickname', 'avatarUrl', 'signature']), total: 3 },
  npc: { done: countFilled(form, ['identity', 'occupation', 'hobbies', 'taboos', 'profileText']), total: 5 },
  persona: {
    done: countFilled(persona, ['identity', 'background', 'personality', 'voice', 'goals', 'ability']),
    total: 6
  }
}))
/** 表单字段总数（3 + 5 + 6） */
const totalCount = 14
/** 已填总数（顶部「已填 n/14」） */
const filledCount = computed(() => secCount.value.base.done + secCount.value.npc.done + secCount.value.persona.done)

// ==================== 连接模型（API 配置） ====================
const apiForm = reactive({ name: '', baseUrl: '', apiKey: '', model: '' })
const apiList = ref([])
const savingApi = ref(false)
const testing = ref(false)

/** 是否已配置用户级模型接口（任意一个即视为已配置） */
const apiConfigured = computed(() => apiList.value.length > 0)
const apiCount = computed(() => apiList.value.length)
const apiSummary = computed(() =>
  apiList.value.map(a => a.name || a.model || a.baseUrl).filter(Boolean).join('、')
)

/** 加载用户级 API 列表（判定已配置态） */
async function loadApis() {
  try {
    apiList.value = (await fetchModelApis(null)) || []
  } catch (_) {
    apiList.value = []
  }
}

/** 未保存前连通性测试（Key 不入库） */
async function testApi() {
  if (!apiForm.baseUrl.trim()) {
    ElMessage.warning('请先填写 Base URL')
    return
  }
  testing.value = true
  try {
    const r = await testModelApi({
      baseUrl: apiForm.baseUrl.trim(),
      apiKey: apiForm.apiKey.trim(),
      model: apiForm.model.trim() || undefined
    })
    if (r.connected) ElMessage.success(`连接成功（${r.method}，耗时 ${r.latencyMs}ms）`)
    else ElMessage.error(r.message || '连接失败')
  } catch (e) {
    ElMessage.error(e?.message || '测试失败')
  } finally {
    testing.value = false
  }
}

/** 保存用户级模型配置（保存后进入已配置态） */
async function saveApi() {
  if (!apiForm.baseUrl.trim()) {
    ElMessage.warning('请填写 Base URL')
    return
  }
  if (!apiForm.apiKey.trim()) {
    ElMessage.warning('请填写 API Key')
    return
  }
  savingApi.value = true
  try {
    await createModelApi({
      name: apiForm.name.trim() || '我的模型',
      baseUrl: apiForm.baseUrl.trim(),
      apiKey: apiForm.apiKey.trim(),
      model: apiForm.model.trim() || undefined,
      supportsStream: true,
      purpose: 'chat'
    }, null)
    ElMessage.success('模型配置已保存')
    await loadApis()
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    savingApi.value = false
  }
}

/** 已配置态 → 跳全局设置 API 页（该页对未完成向导用户也放行） */
function goSettingsApis() {
  router.push('/settings/apis')
}

// ==================== 阶段跳转 ====================

/** 跳过启动动画（点击任意处 / 自动到时） */
function finishSplash() {
  try { localStorage.setItem(SPLASH_SEEN_KEY, '1') } catch (_) { /* 隐私模式静默 */ }
  stage.value = 'api'
}

/** API → 我的形象 */
function toPersona() {
  stage.value = 'persona'
}

// ==================== AI 补充生成 ====================

/**
 * AI 补充生成 persona 六维：写自我介绍 → 后端生成六维 → 回填表单预览。
 * 生成结果同时回填到「NPC 眼中的你」的 身份/个人档案（与 persona identity/background 自动同步）。
 */
async function aiGenerate() {
  if (!selfIntro.value.trim()) {
    ElMessage.warning('请先写一段自我介绍')
    return
  }
  generating.value = true
  try {
    const res = await generatePlayerProfile(selfIntro.value.trim())
    const p = (res && res.persona) || {}
    if (p.identity != null) persona.identity = p.identity
    if (p.background != null) persona.background = p.background
    if (p.personality != null) persona.personality = p.personality
    if (p.voice != null) persona.voice = p.voice
    if (p.goals != null) persona.goals = p.goals
    if (p.ability != null) persona.ability = p.ability
    // 身份/个人档案 与 persona identity/background 自动同步
    if (p.identity) form.identity = p.identity
    if (p.background) form.profileText = p.background
    generated.value = true
    ElMessage.success('已生成 persona 六维，请核对后保存')
  } catch (e) {
    ElMessage.error(e?.message || '生成失败，请稍后重试')
  } finally {
    generating.value = false
  }
}

/** 清空生成结果（仅清 persona 六维与同步字段） */
function clearGenerated() {
  persona.identity = persona.background = persona.personality = persona.voice = persona.goals = persona.ability = ''
  generated.value = false
  ElMessage.info('已清空 persona 六维')
}

// ==================== 完成与跳转 ====================

/** 回跳目标（进向导前所在页面，默认画廊） */
function redirectTo() {
  const target = route.query.redirect
  return typeof target === 'string' && target.startsWith('/') ? target : '/'
}

/** 初始化：若已有账户内容则回填 + 加载模型配置状态 + 启动动画仅首次 */
async function init() {
  await account.loadAccount(true)
  const a = account.account
  if (a) {
    form.nickname = a.nickname || ''
    form.avatarUrl = a.avatarUrl || ''
    form.signature = a.signature || ''
    form.identity = a.identity || ''
    form.occupation = a.occupation || ''
    form.hobbies = a.hobbies || ''
    form.taboos = a.taboos || ''
    form.profileText = a.profileText || ''
  }
  try {
    const p = await fetchPlayerProfile()
    if (p) {
      persona.identity = p.persona?.identity || ''
      persona.background = p.persona?.background || ''
      persona.personality = p.persona?.personality || ''
      persona.voice = p.persona?.voice || ''
      persona.goals = p.persona?.goals || ''
      persona.ability = p.persona?.ability || ''
    }
  } catch (_) { /* 玩家档案缺失/失败不阻断 */ }
  await loadApis()
  // 启动动画：仅当「未完成向导」且「本机从未看过」时显示（刷新后跳过动画）
  const seen = safeGet(SPLASH_SEEN_KEY) === '1'
  stage.value = (!account.onboarded && !seen) ? 'splash' : 'api'
  if (stage.value === 'splash') {
    // 启动动画自动进入下一步：由 2.5s 缩短为 1.5s（开场更快，仍可点击跳过）
    setTimeout(finishSplash, 1500)
  }
}

/** localStorage 读取（损坏安全） */
function safeGet(key) {
  try { return localStorage.getItem(key) } catch (_) { return null }
}

/**
 * 同步创建/更新玩家档案（首次引导即创建对谈玩家档案）。
 * <p>映射：玩家名=昵称；身份=账户身份；背景=个人档案（自由长文本）。
 * 身份/背景与账户字段自动同步（Q9 确认）。失败 fail-open（不阻断向导完成）。</p>
 */
async function syncPlayerProfile() {
  const identity = (persona.identity || form.identity || '').trim()
  const background = (persona.background || form.profileText || '').trim()
  try {
    await savePlayerProfile({
      name: (form.nickname || '').trim() || undefined,
      persona: {
        // 六维按后端契约「空串清空」：未填的维度显式置空，不残留旧值
        identity: identity || '',
        background: background || '',
        personality: persona.personality.trim() || '',
        voice: persona.voice.trim() || '',
        goals: persona.goals.trim() || '',
        ability: persona.ability.trim() || ''
      }
    })
  } catch (_) {
    // 玩家档案保存失败不阻塞进入（可在设置→玩家档案补）
  }
}

/** 保存账户（onboarded=1）+ 同步玩家档案 */
async function persistAccount() {
  // 身份/个人档案 与 persona identity/background 自动同步（取任一非空）
  const identity = (persona.identity || form.identity || '').trim()
  const background = (persona.background || form.profileText || '').trim()
  if (identity) form.identity = identity
  if (background) form.profileText = background
  await account.save({ ...form, onboarded: 1 })
  await syncPlayerProfile()
}

/** 完成向导：保存并进入世界画廊（置位引导待启动，由 FirstRunGuide 接续 7 步引导） */
async function finish() {
  saving.value = true
  try {
    await persistAccount()
    ElMessage.success('账户设置完成，开始逛逛你的世界吧')
    armGuide()
    router.push('/')
  } catch (e) {
    ElMessage.error(e?.message || '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

/** 以后再说：仅标记完成，不保存（仍进入世界画廊 + 引导，引导内可再补） */
async function skip() {
  saving.value = true
  try {
    await account.completeOnboarding()
  } catch (_) {
    // 标记失败不阻塞进入（本地运行场景后端应始终可用）
  } finally {
    saving.value = false
    armGuide()
    router.push(redirectTo())
  }
}

onMounted(init)
</script>

<style scoped>
.firstrun-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 16px;
  /* 云间列车画布铺底（z-index:-1，见 CloudTrainBackground）；此处只叠主题自适应的可读性压暗层
     （--glass-scrim：浅色=白纱 / 深色=夜空暗纱，见 global.css .glass-scene） */
  background:
    radial-gradient(1000px 500px at 12% -10%, rgba(90, 140, 255, 0.12), transparent 60%),
    radial-gradient(800px 460px at 108% 112%, rgba(130, 90, 255, 0.1), transparent 60%),
    var(--glass-scrim, rgba(5, 9, 14, 0.42));
}
/* 关闭云间列车背景：引导页背景退化为纯色（跟随深浅主题），玻璃材质保留 */
.firstrun-page.no-cloudtrain {
  background: var(--bg-page);
}

/* ===== 无边框窗口的自绘标题栏（本页不走 AppShell，需自带拖拽区与窗口三键） =====
   层级取 --z-window（90），高于本页的启动动画层（其 z = --z-window − 1），
   否则启动动画期间窗口三键会被盖住点不到。 */
.fr-window-chrome {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  height: var(--shell-topbar-h, 52px);
  display: flex;
  align-items: stretch;
  justify-content: flex-end;
  z-index: var(--z-window, 90);
}
.fr-window-drag {
  flex: 1;
  -webkit-app-region: drag;
  app-region: drag;
}

/* ===== 启动动画（占位版：品牌字 + 光晕环 + 淡入，后期再精修；背景由云间列车承担） ===== */
.fr-splash {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  user-select: none;
  background:
    radial-gradient(1000px 600px at 50% 30%, rgba(90, 140, 255, 0.14), transparent 60%),
    radial-gradient(800px 500px at 80% 90%, rgba(130, 90, 255, 0.12), transparent 60%),
    var(--glass-scrim, rgba(5, 9, 14, 0.35));
  z-index: calc(var(--z-window, 90) - 1);
}
.fr-splash-inner { text-align: center; animation: frSplashIn 1s var(--dsw-ease, ease) both; }
@keyframes frSplashIn {
  from { opacity: 0; transform: scale(0.94); }
  to { opacity: 1; transform: scale(1); }
}
.fr-splash-logo {
  font-size: 3rem;
  font-weight: 700;
  letter-spacing: 1px;
  color: var(--brand-primary, #1d5cff);
  animation: frSplashFloat 3s ease-in-out infinite;
}
.fr-splash-logo-sub { font-size: 1.4rem; font-weight: 600; color: var(--text-secondary); margin-left: 6px; }
@keyframes frSplashFloat {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-6px); }
}
/* 光晕环（占位装饰） */
.fr-splash-ring {
  width: 96px; height: 96px;
  margin: 26px auto 20px;
  border-radius: 50%;
  border: 3px solid color-mix(in srgb, var(--brand-primary, #1d5cff) 30%, transparent);
  border-top-color: var(--brand-primary, #1d5cff);
  animation: frSplashSpin 1.2s linear infinite;
}
@keyframes frSplashSpin { to { transform: rotate(360deg); } }
.fr-splash-title { font-size: 1.4rem; font-weight: 700; color: var(--text-primary); }
.fr-splash-sub { font-size: 0.85rem; color: var(--text-secondary); margin-top: 8px; }
.fr-splash-skip { font-size: 0.72rem; color: var(--text-placeholder, var(--text-tertiary)); margin-top: 26px; }

/* ===== 向导卡片（液态玻璃 + 内容自适应高度 + 弹窗内滚动） =====
   宽度 880：改前 720 在 1920 宽屏下两侧各空约 600px，表单却仍被挤在小框里；
   高度：改前写死 min(680, 100vh−88)，折叠后会在底部留下大片空白，故改「内容自适应 + 上限」。 */
.fr-card {
  width: 100%;
  max-width: min(880px, calc(100vw - 32px));
  /* 液态玻璃材质（背景/模糊/描边/高光）由 .glass-panel 提供 */
  border-radius: 20px;
  padding: 18px 30px 16px;
  display: flex;
  flex-direction: column;
  /* 最小高度保证「已配置模型」这种短内容态下卡片不塌成一条（此时顶部 95px ≈ 卡片 24%，
     是全页最差比例；表单实际填写态为 17~19%） */
  min-height: 400px;
  max-height: calc(100vh - 88px);
}
/* 表单主体：唯一滚动区域（内容不足时按内容高度，不硬撑满卡片） */
.fr-body {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding-right: 6px;
  margin-right: -6px;
  overscroll-behavior: contain;
}
.fr-body::-webkit-scrollbar { width: 6px; }
.fr-body::-webkit-scrollbar-thumb { background: var(--scrollbar-thumb, rgba(255, 255, 255, 0.24)); border-radius: 3px; }
.fr-body::-webkit-scrollbar-track { background: transparent; }

/* ===== 顶部区（品牌 + 阶段标题 + 紧凑分段器 / 一句话副标题 + 完成度）=====
   改前：Logo(1.5rem) + 居中大标题(1.5rem) + 两行说明 + 97px 步骤条 = 311px，占卡片 49%（1280×720）。
   改后：两行 ≈ 70px（≤ 卡片 1/4），长说明移入表单内 .fr-note，标题右置分段器一眼可见进度。 */
.fr-top {
  flex: 0 0 auto;
  padding-bottom: 9px;
  border-bottom: 1px solid var(--border-l1);
  margin-bottom: 12px;
}
.fr-top-row { display: flex; align-items: center; justify-content: space-between; gap: 14px; min-width: 0; }
.fr-brand { display: flex; align-items: baseline; gap: 7px; min-width: 0; }
.fr-logo { font-size: 1rem; font-weight: 700; letter-spacing: 0.3px; color: var(--brand-primary, #1d5cff); white-space: nowrap; }
.fr-logo-sub { font-size: 0.76rem; font-weight: 600; color: var(--text-secondary); margin-left: 3px; }
.fr-brand-sep { font-size: 0.8rem; color: var(--text-placeholder, var(--text-tertiary)); }
.fr-title { font-size: 1.05rem; font-weight: 700; margin: 0; color: var(--text-primary); white-space: nowrap; }
.fr-top-row--sub { margin-top: 5px; }
.fr-sub {
  margin: 0;
  min-width: 0;
  font-size: 0.78rem;
  line-height: 1.5;
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fr-count {
  flex: 0 0 auto;
  font-size: 0.72rem;
  color: var(--text-tertiary);
  font-variant-numeric: tabular-nums;
  padding: 1px 8px;
  border: 1px solid var(--border-l1);
  border-radius: 999px;
}

/* ===== 长解释（默认收起；原文完整保留，信息不丢）===== */
.fr-note {
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-md, 10px);
  background: var(--accent-softer);
  margin: 0 0 12px;
  overflow: hidden;
}
.fr-note-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  padding: 7px 12px;
  border: 0;
  background: transparent;
  cursor: pointer;
  font-size: 0.78rem;
  color: var(--text-secondary);
  text-align: left;
}
.fr-note-head:hover { color: var(--accent-text); }
.fr-note-title { font-weight: 600; }
/* 折叠箭头用 CSS 雪佛龙（HIcon 的 ArrowDown/Up 已被映射成抽屉图标，见 AGENTS 8.3.9） */
.fr-note-chev {
  flex: 0 0 auto;
  width: 7px;
  height: 7px;
  margin-right: 2px;
  border-right: 1.5px solid currentColor;
  border-bottom: 1.5px solid currentColor;
  transform: rotate(-45deg);
  transition: transform 0.2s;
}
.fr-note.is-open .fr-note-chev { transform: rotate(45deg); }
.fr-note-body { padding: 0 12px 9px; font-size: 0.78rem; line-height: 1.7; color: var(--text-secondary); }
.fr-note-body p { margin: 0 0 6px; }
.fr-note-body p:last-child { margin-bottom: 0; }

/* ===== 我的形象：折叠分区（沿用 el-collapse，只把尺寸/描边收敛成紧凑规格）===== */
.fr-collapse {
  border: 0;
  --el-collapse-border-color: var(--border-l1);
  --el-collapse-header-height: 36px;
  --el-collapse-header-bg-color: transparent;
  --el-collapse-content-bg-color: transparent;
  --el-collapse-header-font-size: 0.88rem;
  --el-collapse-content-font-size: 0.82rem;
}
.fr-collapse :deep(.el-collapse-item__header) { font-weight: 600; color: var(--text-primary); padding: 0 2px; }
.fr-collapse :deep(.el-collapse-item__arrow) { color: var(--text-tertiary); margin-left: 8px; }
.fr-collapse :deep(.el-collapse-item__title) { display: flex; align-items: center; gap: 8px; min-width: 0; }
.fr-collapse :deep(.el-collapse-item__wrap) { border-bottom: 0; }
.fr-collapse :deep(.el-collapse-item__content) { padding: 8px 2px 6px; line-height: 1.6; }
.fr-sec-title { display: inline-flex; align-items: center; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fr-sec-meta { margin-left: auto; font-size: 0.7rem; font-weight: 400; color: var(--text-tertiary); font-variant-numeric: tabular-nums; }

.fr-form { text-align: left; }
.fr-step { text-align: left; }
/* 表单密度：改前 label + 输入框 + 24px 下边距在 720 高下只能放下两行 */
.fr-card :deep(.el-form-item) { margin-bottom: 12px; }
.fr-card :deep(.el-form-item__label) { padding-bottom: 2px; font-size: 0.8rem; line-height: 1.4; }
.fr-section { margin-bottom: 8px; }
.fr-section-title { font-size: 0.9rem; font-weight: 600; color: var(--text-primary); margin: 12px 0 8px; padding-left: 10px; border-left: 3px solid var(--brand-primary, #1d5cff); }
.fr-sub-desc { font-size: 0.78rem; color: var(--text-secondary); line-height: 1.7; margin: 0 0 10px; }
.fr-grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
.avatar-line { display: flex; align-items: center; gap: 12px; width: 100%; }
.fr-avatar { flex-shrink: 0; background: var(--brand-gradient, linear-gradient(135deg, #1d5cff, #7b4dff)); }

/* 第 1 步：模型配置 */
.fr-api-done {
  display: flex;
  align-items: center;
  gap: 10px;
  background: var(--bg-layer-2);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-md);
  padding: 12px 16px;
  font-size: 0.85rem;
  color: var(--text-regular);
}
.fr-api-done-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px; height: 22px;
  border-radius: 50%;
  background: var(--state-success);
  color: #fff;
}
.fr-api-done-text { flex: 1; }
.fr-api-ops { display: flex; gap: 10px; margin-top: 2px; }

/* AI 补充生成（已提升为独立折叠分区，故内部不再套一层带边框的卡片） */
.fr-ai-body { padding: 2px 0 0; }
.fr-ai-body.is-off { opacity: 0.75; }
.fr-ai-desc { font-size: 0.76rem; color: var(--text-secondary); line-height: 1.7; margin: 0 0 8px; }
.fr-ai-ops { display: flex; align-items: center; gap: 10px; margin-top: 10px; flex-wrap: wrap; }
.fr-ai-hint { font-size: 0.7rem; color: var(--text-placeholder, var(--text-tertiary)); }

/* 底部操作（紧凑：改前 margin-top 24 + padding-top 18 又吃掉 42px） */
.fr-actions { display: flex; justify-content: flex-end; align-items: center; gap: 12px; margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--border-light, var(--border-l2)); flex-shrink: 0; }
.fr-spacer { flex: 1; }
.fr-skip { color: var(--text-secondary); }
.fr-save { min-width: 150px; }
.fr-tip { font-size: 0.72rem; color: var(--text-placeholder, var(--text-tertiary)); margin: 8px 0 0; text-align: center; flex-shrink: 0; }

/* V2.0 批次二：断点 640 → 1024（全站三档；<1024 为单栏兜底档） */
@media (max-width: 1024px) {
  .fr-grid-2 { grid-template-columns: 1fr; }
  .fr-card { padding: 16px 16px 14px; max-width: calc(100vw - 24px); }
  /* 窄屏：副标题允许折行（宽屏严格单行，靠这里兜底而不是在宽屏就折行） */
  .fr-sub { white-space: normal; }
  .fr-top-row { flex-wrap: wrap; }
}
</style>
