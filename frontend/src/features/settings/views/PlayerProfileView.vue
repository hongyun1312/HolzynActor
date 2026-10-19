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
    <PageHeader
      title="玩家档案"
      :desc="projectId
        ? '对谈模式以「玩家档案」身份与你对话：与特殊 NPC 档案同构（身份/背景/性格/说话风格/目标/能力）但无记忆、无状态、无自主行动；并可为本项目角色设定「与我的关系」（过往/身份关系/他对你的态度）与好感/信任/阶段基线。'
        : '全局（用户级）玩家档案：编辑你的 persona 六维（注入对谈上下文）。每角色的「与我的关系」与好感/信任/阶段需进入对应项目空间 → 对谈 → 玩家档案 配置。'"
    />

    <!-- 全局提示：项目专属关系设定在项目内 -->
    <div v-if="!projectId" class="set-card">
      <div class="set-card-title">与角色的关系</div>
      <div class="set-card-desc">
        每角色的「与我的关系」设定（过往/身份关系/他对你的态度）与好感/信任/阶段基线按角色配置，
        需在<b>项目内</b>编辑（进入任意项目 → 对谈 → 玩家档案）。已有
        <b>{{ Object.keys(relationSettings).length }}</b> 个角色的设定将按现状注入对谈上下文。
      </div>
    </div>

    <!-- 玩家 persona（全局，两态通用） -->
    <div class="set-card">
      <div class="set-card-head">
        <h3 class="set-card-title">玩家 persona</h3>
        <el-tag size="small" effect="plain">注入对谈上下文 · 无记忆/状态/行动</el-tag>
      </div>
      <div class="set-card-desc">对谈时角色会以这些信息认识你（「关于{你}」段）；与特殊 NPC 档案同构但裁剪——只留身份/背景/性格/说话风格/目标/能力。所有字段选填。</div>
      <el-form :model="form" label-position="top" class="pp-form">
        <el-form-item label="玩家名（角色在对话中如何称呼你）">
          <el-input v-model="form.name" placeholder="例如：阿岚" maxlength="64" show-word-limit clearable />
        </el-form-item>
        <div class="pp-grid-2">
          <el-form-item label="身份">
            <el-input v-model="form.persona.identity" placeholder="例如：旅行编辑" maxlength="255" clearable />
          </el-form-item>
          <el-form-item label="说话风格">
            <el-input v-model="form.persona.voice" placeholder="例如：慢条斯理、爱引经据典" maxlength="255" clearable />
          </el-form-item>
          <el-form-item label="性格">
            <el-input v-model="form.persona.personality" placeholder="例如：温和、慢热" maxlength="255" clearable />
          </el-form-item>
          <el-form-item label="目标">
            <el-input v-model="form.persona.goals" placeholder="例如：收集九州民间故事" maxlength="255" clearable />
          </el-form-item>
          <el-form-item label="能力">
            <el-input v-model="form.persona.ability" placeholder="例如：善写、善辨音" maxlength="255" clearable />
          </el-form-item>
          <el-form-item label="背景">
            <el-input v-model="form.persona.background" type="textarea" :rows="3" placeholder="你的经历与来处…" maxlength="500" show-word-limit clearable />
          </el-form-item>
        </div>
      </el-form>
      <div class="set-actions">
        <el-button type="primary" :loading="savingProfile" @click="saveProfile">保存玩家档案</el-button>
      </div>
    </div>

    <!-- 玩家-角色关系（项目内：设定 + 关系行） -->
    <template v-if="projectId">
      <div class="set-card">
        <div class="set-card-head">
          <h3 class="set-card-title">与角色的关系</h3>
          <el-tag size="small" effect="plain">按角色 · 项目内</el-tag>
        </div>
        <div class="set-card-desc">
          「与我的关系」设定（过往/身份关系/他对你的态度）注入对谈上下文的关系段；好感/信任/阶段/备注为
          关系基线（好感信任 0~100），沉淀（M6）前由你设定，沉淀后 AI 在其上演进。
        </div>
        <div v-if="characters.length === 0" class="set-tip">本项目暂无角色；对谈将按缺省关系（陌生 / 0 / 0）进行，可先到「NPC 角色」建档。</div>
        <el-collapse v-else v-model="openRels" class="pp-collapse">
          <el-collapse-item v-for="c in characters" :key="'npc:' + c.id" :name="'npc:' + c.id">
            <template #title>
              <span class="pp-char-title">
                <el-avatar :size="20" class="pp-char-avatar">{{ (c.name || '?').charAt(0) }}</el-avatar>
                <span class="pp-char-name">{{ c.name }}</span>
              </span>
              <el-tag v-if="relRowFor(c).stage" size="small" effect="plain" class="pp-char-stage">
                {{ relRowFor(c).stage }}
              </el-tag>
            </template>
            <div class="pp-rel-body">
              <div class="pp-rel-left">
                <el-form label-position="top" class="pp-form">
                  <el-form-item label="过往（你们之间发生过什么）">
                    <el-input v-model="relSettingFor(c).past" type="textarea" :rows="2" maxlength="300" placeholder="例如：幼时在青禾村相识" />
                  </el-form-item>
                  <el-form-item label="身份关系（你们是什么关系）">
                    <el-input v-model="relSettingFor(c).identity" type="textarea" :rows="2" maxlength="200" placeholder="例如：青梅竹马 / 旅伴 / 债主" />
                  </el-form-item>
                  <el-form-item label="他对你的态度（基线）">
                    <el-input v-model="relSettingFor(c).attitude" type="textarea" :rows="2" maxlength="200" placeholder="例如：亲近而克制，愿意为你破例" />
                  </el-form-item>
                </el-form>
              </div>
              <div class="pp-rel-right">
                <div class="pp-rel-row">
                  <span class="pp-rel-label">好感</span>
                  <el-slider v-model="relRowFor(c).affinity" :min="0" :max="100" show-input :show-input-controls="false" size="small" class="pp-rel-slider" />
                </div>
                <div class="pp-rel-row">
                  <span class="pp-rel-label">信任</span>
                  <el-slider v-model="relRowFor(c).trust" :min="0" :max="100" show-input :show-input-controls="false" size="small" class="pp-rel-slider" />
                </div>
                <div class="pp-rel-row">
                  <span class="pp-rel-label">关系阶段</span>
                  <el-select v-model="relRowFor(c).stage" size="small" style="width: 150px">
                    <el-option v-for="s in STAGES" :key="s" :label="s" :value="s" />
                  </el-select>
                </div>
                <div class="pp-rel-row">
                  <span class="pp-rel-label">备注</span>
                  <el-input v-model="relRowFor(c).note" size="small" maxlength="200" clearable placeholder="选填" />
                </div>
                <div v-if="relRowFor(c).stateSummary" class="pp-rel-summary">近况：{{ relRowFor(c).stateSummary }}</div>
                <div class="pp-rel-save">
                  <el-button size="small" type="primary" plain :loading="savingRel" @click="saveRelation(c)">保存该角色关系</el-button>
                  <span class="pp-rel-hint">设定存于玩家档案，关系基线存于本项目</span>
                </div>
              </div>
            </div>
          </el-collapse-item>
        </el-collapse>
      </div>
    </template>
  </div>
</template>

<script setup>
/**
 * 玩家档案页（M3，双态共用：全局 /settings/player-profile + 对谈界面 /project/:id/dialogue/player-profile）。
 * <p>职责：① 编辑玩家 persona 六维（身份/背景/性格/说话风格/目标/能力，与特殊 NPC 档案同构裁剪、
 * 无记忆/状态/行动）——注入对谈上下文「关于{玩家}」段；② 项目内按角色编辑「与我的关系」设定
 * （过往/身份关系/态度，存玩家档案）与好感/信任/阶段/备注基线（存本项目关系行）。</p>
 * <p>数据：/api/player-profile（用户级）+ /api/projects/{id}/player-profile/relations（项目级）。</p>
 * <p>P0-1：项目态入口从「设置域」移入「对谈」独立界面（玩家档案属对谈相关功能）；
 * 旧路由 /project/:id/settings/player-profile 仍保留供深链，两路由复用本组件。</p>
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { fetchCharacters, fetchPlayerProfile, savePlayerProfile, savePlayerRelationSetting, savePlayerRelation, fetchPlayerRelations } from '@/shared/api'
import PageHeader from '@/shared/layout/PageHeader.vue'

const route = useRoute()

/** 当前项目 ID：项目空间内存在；全局设置（首页）为 null */
const projectId = computed(() => (route.params.id ? Number(route.params.id) : null))

/** 关系阶段可选值 */
const STAGES = ['陌生', '相识', '相熟', '熟络', '亲密', '知己']

/** persona 六维键（与后端 PlayerProfileService.PERSONA_KEYS 对齐） */
const PERSONA_KEYS = ['identity', 'background', 'personality', 'voice', 'goals', 'ability']

const savingProfile = ref(false)
const savingRel = ref(false)
const openRels = ref([])

const characters = ref([])
/** persona 表单（玩家名 + 六维） */
const form = reactive({ name: '', persona: { identity: '', background: '', personality: '', voice: '', goals: '', ability: '' } })
/** 每角色关系设定（Map：characterRef → {past, identity, attitude}） */
const relSettingsMap = reactive({})
/** 每角色关系行（Map：characterRef → {affinity, trust, stage, note, stateSummary}） */
const relRowsMap = reactive({})

/** 兜底初始化某角色的设定/行对象（保证 v-model 不炸） */
function ensureRel(c) {
  const ref = 'npc:' + c.id
  if (!relSettingsMap[ref]) relSettingsMap[ref] = { past: '', identity: '', attitude: '' }
  if (!relRowsMap[ref]) relRowsMap[ref] = { affinity: 0, trust: 0, stage: '陌生', note: '', stateSummary: '' }
  return relRowsMap[ref]
}

// 渲染期防御：返回某角色的关系设定/关系行对象（缺失时返回共享空对象，绝不抛错）。
// 正常路径由 load() 的 ensureRel 保证存在；兜底仅用于「角色已就绪、关系行尚未返回」的极短窗口，
// 避免该窗口内 Vue 渲染 el-collapse 时访问未初始化对象而崩溃（曾导致导航后连环报错）。
const EMPTY_REL_SETTING = { past: '', identity: '', attitude: '' }
const EMPTY_REL_ROW = { affinity: 0, trust: 0, stage: '陌生', note: '', stateSummary: '' }
function relSettingFor(c) { return relSettingsMap['npc:' + c.id] || EMPTY_REL_SETTING }
function relRowFor(c) { return relRowsMap['npc:' + c.id] || EMPTY_REL_ROW }

async function load() {
  try {
    const p = await fetchPlayerProfile()
    if (p) {
      form.name = p.name || ''
      for (const k of PERSONA_KEYS) form.persona[k] = p.persona?.[k] || ''
      if (p.relationSettings) {
        for (const [ref, v] of Object.entries(p.relationSettings)) {
          relSettingsMap[ref] = { past: v?.past || '', identity: v?.identity || '', attitude: v?.attitude || '' }
        }
      }
    }
  } catch (e) {
    ElMessage.warning('玩家档案加载失败：' + (e?.message || '未知错误'))
  }
  if (!projectId.value) return
  try {
    characters.value = await fetchCharacters(projectId.value)
  } catch (_) { characters.value = [] }
  // 关键：角色一就绪就立即初始化其关系对象——必须先于「await 关系行」，
  // 否则等待关系行接口期间触发渲染（el-collapse 渲染每角色滑块）会访问未初始化对象而崩溃。
  for (const c of characters.value) ensureRel(c)
  try {
    const rows = await fetchPlayerRelations(projectId.value)
    for (const r of rows) {
      relRowsMap[r.characterRef] = { affinity: r.affinity ?? 0, trust: r.trust ?? 0, stage: r.stage || '陌生', note: r.note || '', stateSummary: r.stateSummary || '' }
    }
  } catch (_) { /* 关系行加载失败不阻断 */ }
}

/** 保存玩家 persona（全局） */
async function saveProfile() {
  savingProfile.value = true
  try {
    await savePlayerProfile({ name: form.name.trim() || undefined, persona: { ...form.persona } })
    ElMessage.success('玩家档案已保存')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '保存失败，请稍后重试')
  } finally {
    savingProfile.value = false
  }
}

/** 保存某角色关系（设定 + 行基线） */
async function saveRelation(c) {
  const ref = 'npc:' + c.id
  savingRel.value = true
  try {
    await savePlayerRelationSetting(ref, { ...relSettingsMap[ref] })
    await savePlayerRelation(projectId.value, ref, {
      affinity: relRowsMap[ref].affinity,
      trust: relRowsMap[ref].trust,
      stage: relRowsMap[ref].stage,
      note: relRowsMap[ref].note
    })
    ElMessage.success(`已保存与「${c.name}」的关系`)
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '保存失败，请稍后重试')
  } finally {
    savingRel.value = false
  }
}

onMounted(load)
</script>

<style scoped>
/* V2.1：卡片/标题/描述/动作区/提示走全局设置页规范（.set-card/.set-card-head/.set-card-title/.set-card-desc/.set-actions/.set-tip），
   此处仅保留 persona 双栏栅格与关系行的本页特有样式。 */
.pp-form { margin-top: 6px; }
.pp-grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 0 20px; }
.pp-collapse { border-top: 1px solid var(--border-l1); }
.pp-char-title { display: inline-flex; align-items: center; gap: 6px; }
.pp-char-avatar { font-size: 0.6rem; background: var(--brand-gradient, linear-gradient(135deg, #1d5cff, #7b4dff)); }
.pp-char-name { font-weight: 600; color: var(--text-primary); }
.pp-char-stage { margin-left: 10px; }
.pp-rel-body { display: grid; grid-template-columns: 1.2fr 1fr; gap: 20px; padding: 4px 8px 8px; }
.pp-rel-left { min-width: 0; }
.pp-rel-right { display: flex; flex-direction: column; gap: 8px; min-width: 0; padding-top: 2px; }
.pp-rel-row { display: flex; align-items: center; gap: 10px; }
.pp-rel-label { flex-shrink: 0; width: 62px; font-size: 0.76rem; color: var(--text-secondary); }
.pp-rel-slider { flex: 1; min-width: 0; }
.pp-rel-summary { font-size: 0.74rem; color: var(--text-tertiary); background: var(--bg-layer-2); border-radius: 8px; padding: 6px 10px; line-height: 1.6; }
.pp-rel-save { display: flex; align-items: center; gap: 10px; margin-top: 6px; }
.pp-rel-hint { font-size: 0.7rem; color: var(--text-placeholder, var(--text-tertiary)); }
/* V2.0 批次二：断点 760 → 1024（全站三档；<1024 本就是「单栏兜底档」） */
@media (max-width: 1024px) {
  .pp-grid-2, .pp-rel-body { grid-template-columns: 1fr; }
}
</style>
