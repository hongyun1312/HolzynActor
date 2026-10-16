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
  <el-dialog
    :model-value="visible"
    title="沉淀确认"
    width="560px"
    append-to-body
    @update:model-value="v => $emit('update:visible', v)"
  >
    <div v-if="loading" class="dcc-loading">正在分析对话，抽取关系变化与记忆要点…</div>
    <template v-else>
      <p class="dcc-tip">
        将把本次对话<b>沉淀</b>为：角色对你的关系变化（好感/信任/阶段/状态摘要）+ 角色记住的
        与你的活动（NPC 记忆）。玩家侧不写任何记忆，也不会写世界事件/编年史（零 OOC）。
        世界保持冻结，沉淀只影响后续对谈。
      </p>
      <div v-if="!card || !card.characters || !card.characters.length" class="dcc-empty">
        AI 判定本次对话暂无值得沉淀的变化，可继续聊一聊再试。
      </div>
      <div v-else class="dcc-cards">
        <div v-for="c in card.characters" :key="c.characterRef" class="dcc-card">
          <div class="dcc-head">
            <span class="dcc-name">{{ c.characterName || c.characterRef }}</span>
            <span class="dcc-rel">
              好感 {{ c.currentAffinity ?? 0 }} → <b>{{ c.proposedAffinity ?? 0 }}</b> ·
              信任 {{ c.currentTrust ?? 0 }} → <b>{{ c.proposedTrust ?? 0 }}</b>
            </span>
          </div>
          <div class="dcc-stage">
            关系阶段：
            <span class="dcc-stage-new">{{ c.stage || c.currentStage || '保持现状' }}</span>
            <template v-if="c.stage && c.stage !== c.currentStage">
              <span class="dcc-stage-arrow">（由「{{ c.currentStage }}」）</span>
            </template>
          </div>
          <div v-if="c.stateSummary" class="dcc-line"><span class="dcc-k">状态摘要</span>{{ c.stateSummary }}</div>
          <div v-if="c.note" class="dcc-line"><span class="dcc-k">备注</span>{{ c.note }}</div>
          <div v-if="c.memoryPoints && c.memoryPoints.length" class="dcc-line">
            <span class="dcc-k">NPC 记忆</span>
            <ul class="dcc-mem">
              <li v-for="(p, i) in c.memoryPoints" :key="i">{{ p }}</li>
            </ul>
          </div>
        </div>
      </div>
    </template>
    <template #footer>
      <el-button :disabled="loading" @click="$emit('cancel')">取消</el-button>
      <el-button
        type="primary"
        :loading="loading"
        :disabled="!card || !card.characters || !card.characters.length"
        @click="$emit('confirm')"
      >确认沉淀</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 对谈沉淀确认卡片（M6 设计 §11.2 DialogueConsolidateButton 的确认卡片）。
 * <p>职责：展示「沉淀将写入什么」——每位对谈角色的关系 delta（当前 → 沉淀后）与
 * NPC 记忆要点；确认后由 DialogueView 调 apply 落库。只做展示与事件转发。</p>
 */
defineProps({
  /** 弹窗可见性（v-model） */
  visible: { type: Boolean, default: false },
  /** preview 加载中（AI 抽取） */
  loading: { type: Boolean, default: false },
  /** 确认卡片数据（DialogueConsolidationVO） */
  card: { type: Object, default: null }
})
defineEmits(['update:visible', 'confirm', 'cancel'])
</script>

<style scoped>
.dcc-loading { padding: 24px 8px; text-align: center; color: var(--text-secondary); font-size: 0.84rem; }
.dcc-tip { font-size: 0.78rem; color: var(--text-secondary); line-height: 1.7; margin: 0 0 12px; }
.dcc-empty { padding: 20px 8px; text-align: center; color: var(--text-tertiary); font-size: 0.84rem; }
.dcc-cards { display: flex; flex-direction: column; gap: 10px; max-height: 46vh; overflow-y: auto; }
.dcc-card {
  border: 1px solid var(--border-l2); border-radius: 10px;
  background: var(--bg-layer-2); padding: 10px 12px;
}
.dcc-head { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; flex-wrap: wrap; }
.dcc-name { font-weight: 700; color: var(--text-primary); font-size: 0.9rem; }
.dcc-rel { font-size: 0.78rem; color: var(--text-secondary); }
.dcc-rel b { color: var(--state-primary); }
.dcc-stage { font-size: 0.78rem; color: var(--text-primary); margin-top: 6px; }
.dcc-stage-new { color: var(--state-primary); font-weight: 600; }
.dcc-stage-arrow { color: var(--text-tertiary); font-size: 0.72rem; }
.dcc-line { font-size: 0.78rem; color: var(--text-secondary); margin-top: 6px; line-height: 1.6; }
.dcc-k { display: inline-block; color: var(--text-tertiary); margin-right: 6px; font-size: 0.72rem; }
.dcc-mem { margin: 4px 0 0; padding-left: 18px; }
.dcc-mem li { font-size: 0.76rem; color: var(--text-primary); line-height: 1.6; }
</style>
