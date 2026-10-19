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
<!--
  本地账户填写表单（与首次引导「我的形象」页字段一致 · 单一事实来源）。
  <p>三组字段：① 基本信息（昵称/头像/个性签名）；② NPC 眼中的你（身份/职业/喜好/禁忌/个人档案）；
  ③ 玩家 persona 六维（身份/说话风格/性格/目标/能力/背景）+ AI 补充生成。</p>
  <p>职责：加载账户与玩家档案回填 → 编辑 → AI 补充生成 persona 六维（需已配置模型接口）→
  保存本地账户 + 同步玩家档案（身份/背景与 persona identity/background 自动同步，同首次引导逻辑）。</p>
  <p>用法：设置页「通用设置 → 本地账户」与 /account 页共用；保存成功 emit('saved') 由宿主决定跳转/停留。</p>
-->
<template>
  <el-form :model="form" label-position="top" class="la-form" @submit.prevent>
    <!-- ① 基本信息 -->
    <div class="la-section">
      <div class="la-section-title">基本信息</div>
      <div class="la-grid-2">
        <el-form-item label="昵称（显示名）">
          <el-input v-model="form.nickname" placeholder="例如：林晚" maxlength="64" show-word-limit clearable />
        </el-form-item>
        <el-form-item label="头像">
          <div class="avatar-line">
            <el-avatar :size="44" :src="form.avatarUrl" class="la-avatar">
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

    <!-- ② NPC 眼中的你 -->
    <div class="la-section">
      <div class="la-section-title">NPC 眼中的你（影响角色对你的了解与回应）</div>
      <div class="la-grid-2">
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

    <!-- ③ 玩家 persona 六维（对谈模式） -->
    <div class="la-section">
      <div class="la-section-title">玩家 persona（对谈时角色如何认识你）</div>
      <p class="la-sub-desc">
        对谈（静止世界聊天）以「玩家档案」身份与你对话：以下六维会注入对谈上下文。
        可以手填，也可以用下方「AI 补充生成」写一段自我介绍让 AI 帮你补全。
      </p>
      <div class="la-grid-2">
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

      <!-- AI 补充生成 -->
      <div class="la-ai-card" :class="{ 'is-off': !apiConfigured }">
        <div class="la-ai-head">
          <div class="la-ai-title">
            <el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;AI 补充生成
          </div>
          <el-tag v-if="!apiConfigured" size="small" type="info" effect="plain">需先配置模型接口</el-tag>
        </div>
        <p class="la-ai-desc">
          写一段你想成为的角色（或真实的自己），AI 会把它扩写为 persona 六维并回填到上面的表单，你确认后再保存。
        </p>
        <el-input
          v-model="selfIntro"
          type="textarea"
          :rows="3"
          maxlength="2000"
          show-word-limit
          placeholder="例如：我是旅行编辑，喜欢收集九州各地的民间故事，性格温和慢热，说话慢条斯理…"
        />
        <div class="la-ai-ops">
          <el-button
            type="primary"
            plain
            :disabled="!apiConfigured"
            :loading="generating"
            @click="aiGenerate"
          >
            <el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;AI 补充生成
          </el-button>
          <el-button v-if="generated" size="small" text @click="clearGenerated">清空生成结果</el-button>
          <span class="la-ai-hint">生成后请核对，再点「保存」</span>
        </div>
      </div>
    </div>

    <!-- 保存 -->
    <div class="la-actions">
      <el-button type="primary" :loading="saving" @click="save">保存本地账户</el-button>
    </div>
  </el-form>
</template>

<script setup>
/**
 * 本地账户表单（单一事实来源：与首次引导「我的形象」字段/映射完全一致）。
 * <p>保存：写本地账户（onboarded=1）+ 同步玩家档案（name=昵称，persona 六维按后端契约空串清空；
 * 身份/背景取 persona 优先、账户身份/个人档案兜底）。</p>
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useAccountStore } from '@/shared/store'
import { fetchPlayerProfile, savePlayerProfile, fetchModelApis, generatePlayerProfile } from '@/shared/api'

const emit = defineEmits(['saved'])

const account = useAccountStore()
const saving = ref(false)
const generating = ref(false)
const generated = ref(false)
/** AI 补充生成的自我介绍输入 */
const selfIntro = ref('')

/** 账户字段（①+②） */
const form = reactive({
  nickname: '', avatarUrl: '', signature: '',
  identity: '', occupation: '', hobbies: '', taboos: '', profileText: ''
})
/** 玩家 persona 六维（③；与账户「身份/个人档案」自动同步 identity/background） */
const persona = reactive({ identity: '', background: '', personality: '', voice: '', goals: '', ability: '' })

/** 模型接口是否已配置（AI 补充生成开关条件） */
const apiList = ref([])
const apiConfigured = computed(() => apiList.value.length > 0)

/** 加载账户 + 玩家档案 + 模型配置态 */
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
  try {
    apiList.value = (await fetchModelApis(null)) || []
  } catch (_) {
    apiList.value = []
  }
}

/** AI 补充生成 persona 六维（回填表单预览，不落库） */
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

/** 保存本地账户 + 同步玩家档案 */
async function save() {
  saving.value = true
  try {
    // 身份/个人档案 与 persona identity/background 自动同步（取任一非空）
    const identity = (persona.identity || form.identity || '').trim()
    const background = (persona.background || form.profileText || '').trim()
    if (identity) form.identity = identity
    if (background) form.profileText = background
    await account.save({ ...form, onboarded: 1 })
    try {
      await savePlayerProfile({
        name: (form.nickname || '').trim() || undefined,
        persona: {
          identity: identity || '',
          background: background || '',
          personality: persona.personality.trim() || '',
          voice: persona.voice.trim() || '',
          goals: persona.goals.trim() || '',
          ability: persona.ability.trim() || ''
        }
      })
    } catch (_) {
      // 玩家档案保存失败不阻断（可在设置→玩家档案补）
    }
    ElMessage.success('账户设置已保存')
    emit('saved')
  } catch (e) {
    ElMessage.error(e?.message || '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

onMounted(init)
</script>

<style scoped>
.la-form { text-align: left; }
.la-section { margin-bottom: 8px; }
.la-section-title {
  font-size: 0.9rem; font-weight: 600; color: var(--text-primary);
  margin: 18px 0 10px; padding-left: 10px; border-left: 3px solid var(--brand-primary, #1d5cff);
}
.la-sub-desc { font-size: 0.78rem; color: var(--text-secondary); line-height: 1.8; margin: 0 0 12px; }
.la-grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 0 20px; }
.avatar-line { display: flex; align-items: center; gap: 12px; width: 100%; }
.la-avatar { flex-shrink: 0; background: var(--brand-gradient, linear-gradient(135deg, #1d5cff, #7b4dff)); }

/* AI 补充生成卡片 */
.la-ai-card {
  margin-top: 10px;
  padding: 14px 16px;
  border: 1px solid var(--border-light);
  border-radius: var(--radius-lg);
  background: linear-gradient(180deg, color-mix(in srgb, var(--brand-primary, #1d5cff) 5%, transparent), transparent);
}
.la-ai-card.is-off { opacity: 0.8; }
.la-ai-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.la-ai-title { display: inline-flex; align-items: center; font-size: 0.88rem; font-weight: 700; color: var(--brand-primary, #1d5cff); }
.la-ai-desc { font-size: 0.76rem; color: var(--text-secondary); line-height: 1.7; margin: 8px 0 10px; }
.la-ai-ops { display: flex; align-items: center; gap: 10px; margin-top: 10px; flex-wrap: wrap; }
.la-ai-hint { font-size: 0.7rem; color: var(--text-placeholder, var(--text-tertiary)); }

/* 保存动作区 */
.la-actions { display: flex; justify-content: flex-end; margin-top: 8px; padding-top: 14px; border-top: 1px solid var(--border-light, var(--border-l2)); }

/* V2.0 批次二：断点 640 → 1024（全站三档；<1024 为单栏兜底档） */
@media (max-width: 1024px) {
  .la-grid-2 { grid-template-columns: 1fr; }
}
</style>
