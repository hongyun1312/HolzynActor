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
<template>
  <div class="firstrun-page">
    <div class="fr-card">
      <!-- 头部：品牌 + 标题 -->
      <div class="fr-header">
        <div class="fr-logo">Holzyn<span class="fr-logo-sub">Actor</span></div>
        <h1 class="fr-title">欢迎来到你的世界</h1>
        <p class="fr-desc">
          三步完成首次设置：你的身份 → 连接模型 → 认识这个世界。所有字段可选填，随时可在「设置」中修改。
        </p>
      </div>

      <!-- 步骤条（P0-5：首次向导升级为三步向导） -->
      <el-steps :active="step" align-center finish-status="success" class="fr-steps">
        <el-step title="你的身份" description="本地账户与玩家档案" />
        <el-step title="连接模型" description="让世界像真人一样生活" />
        <el-step title="开始使用" description="时钟 · 成本 · 新功能" />
      </el-steps>

      <!-- ===== 第 1 步：身份（本地账户 + 面向 NPC 的档案 + 玩家档案说明） ===== -->
      <el-form v-show="step === 0" :model="form" label-position="top" class="fr-form" @submit.prevent>
        <div class="fr-section">
          <div class="fr-section-title">基本信息</div>
          <div class="fr-grid-2">
            <el-form-item label="昵称（显示名）">
              <el-input v-model="form.nickname" placeholder="例如：林晚" maxlength="64" show-word-limit clearable />
            </el-form-item>
            <el-form-item label="头像">
              <div class="avatar-line">
                <el-avatar :size="44" :src="form.avatarUrl" class="fr-avatar">
                  {{ (form.nickname || 'H').charAt(0) }}
                </el-avatar>
                <el-input v-model="form.avatarUrl" placeholder="头像图片地址（可留空）" clearable />
              </div>
            </el-form-item>
          </div>
          <el-form-item label="个性签名">
            <el-input v-model="form.signature" placeholder="一句话介绍自己（选填）" maxlength="255" show-word-limit clearable />
          </el-form-item>
        </div>

        <div class="fr-section">
          <div class="fr-section-title">NPC 眼中的你（影响角色对你的了解与回应）</div>
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
              :rows="4"
              placeholder="你的背景、经历、性格、近期状态……角色会把它当作「对你的了解」来对话（选填）"
              clearable
            />
          </el-form-item>
        </div>

        <div class="fr-section">
          <div class="fr-section-title">玩家档案（对谈模式）</div>
          <p class="fr-sub-desc">
            对谈（静止世界聊天）以「玩家档案」身份与你对话：身份与背景将自动带入，
            性格/说话风格/目标/能力可在「设置 → 玩家档案」继续完善；所有字段选填。
          </p>
        </div>
      </el-form>

      <!-- ===== 第 2 步：连接模型（P0-5：首次向导补「模型配置 + 心智」） ===== -->
      <div v-show="step === 1" class="fr-step">
        <div class="fr-section">
          <div class="fr-section-title">配置模型接口（强烈推荐）</div>
          <p class="fr-sub-desc">
            世界的推进、角色的决策与对话<b>全部依赖模型调用</b>；程序化只是兜底——不配模型时世界会
            退回机械的作息循环，非常僵硬、非常不真实。这里配置一个<b>用户级</b>模型接口（所有项目共享），
            之后随时可在「设置 → API 配置」新增多个并测试。
          </p>

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
            <p class="fr-tip">也可以直接「下一步」跳过——进入画廊后顶部会常驻提醒，随时可在「设置 → API 配置」补齐。</p>
          </el-form>
        </div>
      </div>

      <!-- ===== 第 3 步：开始使用（时钟 / 成本 / 新功能速览：对谈 + AI 助手 + 教程） ===== -->
      <div v-show="step === 2" class="fr-step">
        <div class="fr-section">
          <div class="fr-section-title">这个世界怎么玩（三件事先记住）</div>
          <div class="fr-start-cards">
            <div class="fr-start-card">
              <el-icon :size="20" class="fr-start-icon"><HIcon name="Clock" /></el-icon>
              <div class="fr-start-body">
                <div class="fr-start-title">时钟：世界默认暂停</div>
                <p>新建世界后时钟是<b>暂停</b>的。进入「世界地图」点「开始推进」世界才开始运转；暂停即冻结，恢复从冻结处接续、不补离线。</p>
              </div>
            </div>
            <div class="fr-start-card">
              <el-icon :size="20" class="fr-start-icon"><HIcon name="DataAnalysis" /></el-icon>
              <div class="fr-start-body">
                <div class="fr-start-title">成本：AI 调用消耗你的额度</div>
                <p>世界运转依赖模型调用，会消耗你配置的 API 额度。调用的多少不是本项目的约束——只要世界在真实地运转，就值得。用量可在「设置 → AI 用量」查看。</p>
              </div>
            </div>
            <div class="fr-start-card">
              <el-icon :size="20" class="fr-start-icon"><HIcon name="ChatDotRound" /></el-icon>
              <div class="fr-start-body">
                <div class="fr-start-title">对谈：和角色私聊（世界静止）</div>
                <p>进入任意项目后，点击左上角世界名右侧的「浮生 ⇄ 对谈」切换按钮，切到独立的<b>对谈</b>界面——世界冻结、零推进，只和角色聊天。</p>
              </div>
            </div>
            <div class="fr-start-card">
              <el-icon :size="20" class="fr-start-icon"><HIcon name="MagicStick" /></el-icon>
              <div class="fr-start-body">
                <div class="fr-start-title">AI 助手：右下角浮窗随时可问</div>
                <p>画廊与项目空间右下角的浮窗（快捷键 <code>Ctrl+Shift+A</code>）是<b>项目级 AI 助手</b>——讲解世界观、查看决策链路、受限干预世界。</p>
              </div>
            </div>
          </div>
          <div class="fr-step-actions">
            <el-button plain @click="goDemoDialogue">
              <el-icon><HIcon name="ChatDotRound" /></el-icon>&nbsp;体验对谈（演示项目）
            </el-button>
            <el-button plain @click="openTutorial">
              <el-icon><HIcon name="Reading" /></el-icon>&nbsp;先看新手教程
            </el-button>
          </div>
        </div>
      </div>

      <!-- 底部操作 -->
      <div class="fr-actions">
        <el-button v-if="step === 0" class="fr-skip" text @click="skip">以后再说</el-button>
        <el-button v-else class="fr-back" text @click="step--">上一步</el-button>
        <el-button v-if="step < 2" type="primary" class="fr-next" @click="step++">下一步</el-button>
        <el-button v-else type="primary" class="fr-save" :loading="saving" @click="finish">
          保存并进入画廊
        </el-button>
      </div>
      <p class="fr-tip">
        提示：本地数据只保存在你的电脑上；以上所有内容都可在「设置」中随时修改。
        第一次用可以先看「新手教程」——六章清单，每章都带直达入口。
      </p>
    </div>
  </div>
</template>

<script setup>
/**
 * 首次设置向导（本地个人账户 + 模型配置 + 新功能心智，P0-5 三步化）。
 * <p>第 1 步：本地账户（昵称/头像/签名 + 面向 NPC 的结构化档案与个人档案 + 玩家档案说明），
 * 所有字段选填；「保存并进入画廊」保存并标记向导完成。</p>
 * <p>第 2 步：用户级模型接口配置（复用 /api/model-apis 系列：新建 + 未保存前连通性测试；
 * 已配置则只读摘要 + 跳「设置 → API 配置」）。心智：「程序化只是兜底，配好模型世界才会像真人一样生活」。</p>
 * <p>第 3 步：开始使用——时钟（世界默认暂停）/ 成本（AI 调用消耗 API 额度）/ 新功能速览
 * （对谈独立界面 + 右下角 AI 助手 + 新手教程），带演示项目对谈与教程直达入口。</p>
 * <p>数据：useAccountStore（本地账户 store），保存走 /api/local-account；
 * 模型配置走 /api/model-apis（projectId 空 = 用户级默认）。</p>
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAccountStore } from '@/shared/store'
import { savePlayerProfile, fetchModelApis, createModelApi, testModelApi } from '@/shared/api'

const router = useRouter()
const route = useRoute()
const account = useAccountStore()

const saving = ref(false)
/** 当前步骤：0=身份 / 1=连接模型 / 2=开始使用 */
const step = ref(0)

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

// ==================== 第 2 步：模型配置 ====================
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

// ==================== 完成与跳转 ====================

/** 回跳目标（进向导前所在页面，默认画廊） */
function redirectTo() {
  const target = route.query.redirect
  return typeof target === 'string' && target.startsWith('/') ? target : '/'
}

/** 初始化：若已有账户内容则回填 + 加载模型配置状态 */
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
  await loadApis()
}

/**
 * 同步创建/更新玩家档案（M3：首次引导即创建对谈玩家档案）。
 * <p>映射：玩家名=昵称；身份=账户身份；背景=个人档案（自由长文本）。性格/说话风格/目标/能力
 * 留空，可在「设置 → 玩家档案」继续完善。失败 fail-open（不阻断向导完成）。</p>
 */
async function syncPlayerProfile() {
  try {
    await savePlayerProfile({
      name: (form.nickname || '').trim() || undefined,
      persona: {
        identity: form.identity || '',
        background: form.profileText || ''
      }
    })
  } catch (_) {
    // 玩家档案保存失败不阻塞进入（可在设置→玩家档案补）
  }
}

/** 保存账户（onboarded=1）+ 同步玩家档案 */
async function persistAccount() {
  await account.save({ ...form, onboarded: 1 })
  await syncPlayerProfile()
}

/** 完成向导：保存并进入画廊（或回跳原目标） */
async function finish() {
  saving.value = true
  try {
    await persistAccount()
    ElMessage.success('账户设置完成')
    router.push(redirectTo())
  } catch (e) {
    ElMessage.error(e?.message || '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

/**
 * 先看新手上手教程：**先落盘账户设置**再跳教程。
 * <p>为什么不直接跳：向导的 completion 标记决定下次启动是否还拦截到本页，
 * 若跳过保存直接离开，用户会被反复拉回向导页——那比不做教程更糟。</p>
 */
async function openTutorial() {
  saving.value = true
  try {
    await persistAccount()
  } catch (e) {
    ElMessage.warning('账户信息未保存成功，教程结束后可在「设置 → 通用设置」补充')
  } finally {
    saving.value = false
    router.push('/tutorial')
  }
}

/** 体验对谈（演示项目 id=1）：先落盘账户设置，再进对谈界面 */
async function goDemoDialogue() {
  saving.value = true
  try {
    await persistAccount()
    router.push('/project/1/dialogue')
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
    router.push(redirectTo())
  } finally {
    saving.value = false
  }
}

/** 以后再说：仅标记完成，不保存 */
async function skip() {
  saving.value = true
  try {
    await account.completeOnboarding()
    router.push(redirectTo())
  } catch (e) {
    // 标记失败不阻塞进入（本地运行场景后端应始终可用）
    router.push(redirectTo())
  } finally {
    saving.value = false
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
  background:
    radial-gradient(1200px 600px at 10% -10%, rgba(64, 128, 255, 0.18), transparent 60%),
    radial-gradient(900px 500px at 110% 110%, rgba(120, 80, 255, 0.15), transparent 60%),
    var(--bg-page, #f2f5f9);
}
.fr-card {
  width: 100%;
  /* V2.0 批次二：780 硬编码 → 表单档令牌（向导是单栏表单页，与账户页同档） */
  max-width: var(--page-w-form, 720px);
  background: var(--bg-layer-1);
  border-radius: 20px;
  box-shadow: 0 16px 48px rgba(30, 60, 120, 0.12);
  padding: 36px 44px 28px;
}
.fr-header { text-align: center; margin-bottom: 8px; }
/* V2.0：800 → 700（分片只保留 400/700；原写法会被浏览器就近解析到 900 字重，多下一个完整字重） */
.fr-logo { font-size: 1.5rem; font-weight: 700; letter-spacing: 0.5px; color: var(--brand-primary, #1d5cff); }
.fr-logo-sub { font-size: 0.9rem; font-weight: 600; color: var(--text-secondary); margin-left: 4px; }
.fr-title { font-size: 1.5rem; font-weight: 700; margin: 14px 0 8px; color: var(--text-primary); }
.fr-desc { font-size: 0.88rem; color: var(--text-secondary); line-height: 1.7; max-width: 560px; margin: 0 auto 20px; }

/* 步骤条 */
.fr-steps { max-width: 640px; margin: 0 auto 6px; }

.fr-form { text-align: left; }
.fr-step { text-align: left; }
.fr-section { margin-bottom: 8px; }
.fr-section-title { font-size: 0.9rem; font-weight: 600; color: var(--text-primary); margin: 18px 0 10px; padding-left: 10px; border-left: 3px solid var(--brand-primary, #1d5cff); }
.fr-sub-desc { font-size: 0.78rem; color: var(--text-secondary); line-height: 1.8; margin: 0 0 12px; }
.fr-grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 0 20px; }
.avatar-line { display: flex; align-items: center; gap: 12px; width: 100%; }
.fr-avatar { flex-shrink: 0; background: var(--brand-gradient, linear-gradient(135deg, #1d5cff, #7b4dff)); }

/* 第 2 步：模型配置 */
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

/* 第 3 步：开始使用信息卡 */
.fr-start-cards { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.fr-start-card {
  display: flex;
  gap: 12px;
  background: var(--bg-layer-2);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-md);
  padding: 14px 16px;
}
.fr-start-icon {
  flex-shrink: 0;
  margin-top: 2px;
  color: var(--brand-primary, #1d5cff);
}
.fr-start-body { flex: 1; }
.fr-start-title { font-size: 0.88rem; font-weight: 600; color: var(--text-primary); margin-bottom: 4px; }
.fr-start-card p { font-size: 0.78rem; color: var(--text-secondary); line-height: 1.7; margin: 0; }
.fr-start-card code { background: var(--bg-layer-3); border: 1px solid var(--border-l2); border-radius: 4px; padding: 0 4px; font-size: 0.75rem; }
.fr-step-actions { display: flex; gap: 10px; margin-top: 16px; }

.fr-actions { display: flex; justify-content: flex-end; align-items: center; gap: 12px; margin-top: 24px; padding-top: 18px; border-top: 1px solid var(--border-light, var(--border-l2)); }
.fr-skip { color: var(--text-secondary); }
.fr-save { min-width: 140px; }
.fr-tip { font-size: 0.75rem; color: var(--text-placeholder, var(--text-tertiary)); margin-top: 14px; text-align: center; }

/* V2.0 批次二：断点 640 → 1024（全站三档；<1024 为单栏兜底档） */
@media (max-width: 1024px) {
  .fr-grid-2, .fr-start-cards { grid-template-columns: 1fr; }
  .fr-card { padding: 24px 20px 20px; }
}
</style>
