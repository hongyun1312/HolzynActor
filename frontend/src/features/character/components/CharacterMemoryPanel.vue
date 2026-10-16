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

  角色详情 · 「记忆」标签页面板（V2.0 §13.4 巨型组件拆分第二步，模板与样式为原样搬迁）。

  面板自带 5 组接口与 3 个对话框，输入只有两个（角色 + 项目 id）；加载时机见 <script> 顶部注释。
-->

<template>
  <div v-if="!character" class="main-empty">
    <EmptyState size="inline" icon="ic_public_contacts" title="从左侧选择一个角色查看详情" />
  </div>
  <template v-else>
    <div class="memory-ops">
      <span class="memory-hint">对话后 AI 自动抽取（M1 三重机制：重要度 1 丢 / 2 进工作记忆草稿 / 3+ 直进长期记忆；update/invalidate 版本链演进；target=world 世界信息路由到「项目记忆」页）</span>
      <div class="memory-ops-right">
        <el-button size="small" text type="info" @click="openInjectionPreview" title="分场景注入组合预览（M5：各段文本 + 命中记忆得分 + token 预算）">注入预览</el-button>
        <el-button size="small" text type="warning" @click="doMaintenance" title="统一维护（L1 TTL/巩固扫描 + 归档超龄/保留/二次清理，M5）">统一维护</el-button>
        <el-button size="small" text type="primary" :loading="extracting" @click="doExtractMemories">手动抽取</el-button>
      </div>
    </div>
    <div v-loading="memoryLoading" class="memory-list">
      <div v-if="memories.length === 0" class="side-empty">暂无记忆，对话几轮后自动生成</div>
      <div v-for="m in memories" :key="m.id" class="memory-item" :class="memoryClass(m)">
        <div class="memory-head">
          <el-tag size="small" :type="typeTag(m)" effect="plain">
            {{ typeLabel(m) }}
          </el-tag>
          <el-tag size="small" :type="impTag(m.importance)" effect="light">重要度 {{ m.importance }}</el-tag>
          <el-button class="memory-op" size="small" text type="primary" @click="openEditMemoryDialog(m)" title="编辑该记忆（版本+1，旧版自动归档）">
            <el-icon><HIcon name="Edit" /></el-icon>
          </el-button>
          <el-button class="memory-op" size="small" text type="warning" @click="doArchiveMemory(m)" title="归档该记忆（写入归档冷存储，不再参与注入）">
            <el-icon><HIcon name="Document" /></el-icon>
          </el-button>
          <el-button class="memory-del" size="small" text type="danger" @click="doDeleteMemory(m)" title="删除该记忆">
            <el-icon><HIcon name="Delete" /></el-icon>
          </el-button>
        </div>
        <div class="memory-content">{{ m.content }}</div>
      </div>
    </div>
    <!-- M3 L3 核心记忆（恒在上下文：静态基线只读 + 动态核心块 CRUD） -->
    <div class="core-memory-sec">
      <div class="core-memory-head">
        <span class="core-memory-title">核心记忆（L3 恒在）</span>
        <el-button size="small" text type="primary" :loading="coreMemoryLoading" @click="loadCoreMemories">刷新</el-button>
        <el-button size="small" type="primary" plain @click="openCoreBlockDialog()">
          <el-icon><HIcon name="Plus" /></el-icon>&nbsp;新建核心块
        </el-button>
      </div>
      <div v-loading="coreMemoryLoading" class="core-memory-body">
        <div v-if="coreBaselineText" class="core-baseline">
          <div class="core-baseline-title">静态基线（来自角色档案/脑力/目标/关系，恒在注入）</div>
          <div class="core-baseline-text">{{ coreBaselineText }}</div>
        </div>
        <div v-if="coreBlocks.length === 0 && !coreBaselineText" class="side-empty">
          暂无核心记忆。身份信息由静态基线渲染；可由「新建核心块」手动添加，或对话/反思时由 AI 自动演进（core_memory_update）
        </div>
        <div v-for="b in coreBlocks" :key="b.id" class="core-block-item">
          <div class="core-block-head">
            <el-tag size="small" :type="coreBlockTag(b)" effect="plain">{{ b.blockKey.startsWith('custom:') ? b.blockName || b.blockKey : (coreBlockKeyLabel(b.blockKey)) }}</el-tag>
            <el-tag size="small" effect="light">重要度 {{ b.importance }}</el-tag>
            <el-tag size="small" effect="plain" :type="b.editableByAgent === 0 ? 'info' : 'warning'">
              {{ b.editableByAgent === 0 ? '仅用户可改' : 'agent 可自编辑' }}
            </el-tag>
            <span class="core-block-version">v{{ b.version }}</span>
            <div class="core-block-ops">
              <el-button size="small" text type="primary" @click="openCoreBlockDialog(b)">编辑</el-button>
              <el-button size="small" text type="warning" @click="doArchiveCoreBlock(b)">归档</el-button>
              <el-button size="small" text type="danger" @click="doDeleteCoreBlock(b)">删除</el-button>
            </div>
          </div>
          <div class="core-block-content">{{ b.content }}</div>
        </div>
      </div>
    </div>
    <!-- M2 L1 工作记忆（热缓冲：草稿/任务/上下文/感知，会话结束后巩固进长期记忆） -->
    <div class="working-memory-sec">
      <div class="working-memory-title">工作记忆（L1 热缓冲）</div>
      <div v-loading="workingMemoryLoading" class="working-memory-list">
        <div v-if="workingMemories.length === 0" class="side-empty">暂无进行中的工作项（对话抽取草稿、行动关注点、感知快照将在会话结束后巩固为长期记忆）</div>
        <div v-for="w in workingMemories" :key="w.id" class="working-memory-item">
          <div class="working-memory-head">
            <el-tag size="small" :type="workingTypeTag(w)" effect="plain">{{ workingTypeLabel(w) }}</el-tag>
            <el-tag v-if="w.importanceHint" size="small" effect="light">建议重要度 {{ w.importanceHint }}</el-tag>
            <span class="working-memory-time">{{ fmtTime(w.createdAt) }}</span>
          </div>
          <div class="working-memory-content">{{ w.content }}</div>
        </div>
      </div>
    </div>
    <!-- M5 记忆治理健康度（§11.3：归档量 / 版本链 / 分层分布——角色维度健康度摘要） -->
    <div class="working-memory-sec">
      <div class="working-memory-title">记忆治理健康度（M5）</div>
      <div v-loading="healthLoading" class="memory-health-grid">
        <div class="health-item">
          <div class="health-label">归档量</div>
          <div class="health-value mono">{{ memoryHealth?.archive?.total ?? 0 }}</div>
          <div class="health-sub">按原因 {{ reasonSummary(memoryHealth?.archive?.byReason) }} · 还原 {{ memoryHealth?.archive?.restored ?? 0 }}</div>
        </div>
        <div class="health-item">
          <div class="health-label">版本链数</div>
          <div class="health-value mono">{{ memoryHealth?.versionChain?.totalVersionChainCount ?? 0 }}</div>
          <div class="health-sub">L2 {{ memoryHealth?.versionChain?.l2VersionChainCount ?? 0 }} · L3 {{ memoryHealth?.versionChain?.l3CoreBlockChainCount ?? 0 }}</div>
        </div>
        <div class="health-item">
          <div class="health-label">分层 L1/L2/L3</div>
          <div class="health-value mono">{{ memoryHealth?.tier?.l1?.activeCount ?? 0 }} / {{ memoryHealth?.tier?.l2?.total ?? 0 }} / {{ memoryHealth?.tier?.l3CoreBlocks ?? 0 }}</div>
          <div class="health-sub">L2：语义 {{ memoryHealth?.tier?.l2?.semantic ?? 0 }} · 经历 {{ memoryHealth?.tier?.l2?.episodic ?? 0 }} · 反思 {{ memoryHealth?.tier?.l2?.reflection ?? 0 }} · 计划 {{ memoryHealth?.tier?.l2?.plan ?? 0 }} · 摘要 {{ memoryHealth?.tier?.l2?.summary ?? 0 }}</div>
        </div>
      </div>
    </div>
    <!-- O1 记忆内核扩展：L1 短期模糊（艾宾浩斯渐进遗忘 + 睡眠清洗状态，只读展示 + 手动触发） -->
    <div class="working-memory-sec">
      <div class="working-memory-title">
        L1 短期模糊（O1：艾宾浩斯遗忘曲线 · 睡眠清洗）
        <el-button size="small" text type="primary" :loading="fuzzLoading" @click="loadFuzzStates">刷新</el-button>
      </div>
      <div v-loading="fuzzLoading" class="working-memory-list">
        <div v-if="fuzzStates.length === 0" class="side-empty">
          暂无短期模糊记忆。角色经历类记忆写入 L1 short_term 后，将随时间按艾宾浩斯曲线渐进模糊，睡眠时按重要度分档（蒸馏 L2 / 琐事合并 / 模糊跳升）
        </div>
        <div v-for="f in fuzzStates" :key="f.id" class="working-memory-item">
          <div class="working-memory-head">
            <el-tag size="small" :type="fuzzLevelTag(f.fuzzLevel)" effect="light">模糊 {{ Math.round(f.fuzzLevel * 100) }}%</el-tag>
            <el-tag size="small" effect="plain" :type="fuzzStatusTag(f.status)">{{ fuzzStatusLabel(f.status) }}</el-tag>
            <el-tag v-if="f.retrievalCount" size="small" effect="plain" type="info">检索 {{ f.retrievalCount }} 次</el-tag>
            <span v-if="f.distilledMemoryId" class="working-memory-time mono">已蒸馏 → L2 #{{ f.distilledMemoryId }}</span>
            <span class="working-memory-time mono">{{ gameSecondText(f.nextFuzzGameSecond) }} 复查</span>
            <el-button class="memory-op" size="small" text type="primary"
                       :loading="fuzzTriggerId === f.memoryWorkingId"
                       @click="doTriggerFuzz(f)" title="立即模糊一次（无视艾宾浩斯间隔）">模糊</el-button>
          </div>
          <div class="working-memory-content">{{ f.currentContent }}</div>
          <div v-if="f.originalContent !== f.currentContent" class="fuzz-original">
            <span class="fuzz-original-label">原版：</span>{{ f.originalContent }}
          </div>
        </div>
      </div>
    </div>
    <!-- O2 决策与协调：前瞻推演 / 冲突检测协商 / state delta（只读回看，P1-1/P1-2/P1-3） -->
    <div class="working-memory-sec">
      <div class="working-memory-title">
        决策与协调（O2：前瞻推演 · 冲突协商 · 状态增量）
        <el-button size="small" text type="primary" :loading="dcLoading" @click="loadDecisionCoord">刷新</el-button>
      </div>
      <div v-loading="dcLoading" class="dc-wrap">
        <div class="dc-empty" v-if="dcForesight.length === 0 && dcConflicts.length === 0 && dcDeltas.length === 0">
          暂无 O2 记录。角色遇到资源/关系/位置类刺激或紧急事件时产生前瞻推演；行动目标与他人冲突时产生冲突协商；每次行动执行后产生状态增量
        </div>
        <!-- 前瞻推演 -->
        <div v-if="dcForesight.length" class="dc-sec">
          <div class="dc-sec-title">前瞻推演（P1-1）</div>
          <div v-for="f in dcForesight" :key="f.id" class="dc-item">
            <div class="working-memory-head">
              <el-tag size="small" :type="f.success ? 'success' : 'warning'" effect="plain">{{ f.success ? '有候选' : '无候选' }}</el-tag>
              <span class="dc-reason">{{ f.triggerReason }}</span>
              <span class="working-memory-time mono">{{ gameSecondText(f.gameSecond) }}</span>
            </div>
            <div class="working-memory-content">{{ f.finalAction }}</div>
            <div v-if="f.candidatesJson && f.candidatesJson !== '[]'" class="dc-cands">{{ prettyCandidates(f.candidatesJson) }}</div>
          </div>
        </div>
        <!-- 冲突协商 -->
        <div v-if="dcConflicts.length" class="dc-sec">
          <div class="dc-sec-title">冲突协商（P1-3）</div>
          <div v-for="c in dcConflicts" :key="c.id" class="dc-item">
            <div class="working-memory-head">
              <el-tag size="small" :type="dcResolutionTag(c.resolution)" effect="plain">{{ dcResolutionLabel(c.resolution) }}</el-tag>
              <el-tag size="small" effect="plain" type="info">{{ c.conflictType }}</el-tag>
              <span class="dc-reason">争「{{ c.resourceName }}」 vs {{ c.opponentName }}</span>
              <span class="working-memory-time mono">{{ gameSecondText(c.gameSecond) }}</span>
            </div>
            <div class="working-memory-content">{{ c.actionAfter }}</div>
          </div>
        </div>
        <!-- state delta -->
        <div v-if="dcDeltas.length" class="dc-sec">
          <div class="dc-sec-title">状态增量（P1-2）</div>
          <div v-for="d in dcDeltas" :key="d.id" class="dc-item">
            <div class="working-memory-head">
              <span class="dc-reason">plan #{{ d.decisionId }}</span>
              <el-tag v-for="f in (d.changedFields || [])" :key="f" size="small" effect="plain" type="info">{{ f }}</el-tag>
              <span class="working-memory-time mono">{{ gameSecondText(d.gameSecond) }}</span>
            </div>
            <div class="working-memory-content">{{ dcActionOf(d.changesJson) }}</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 核心块新增/编辑对话框（M3：块键/显示名/内容/重要度/agent 可编辑标识） -->
    <el-dialog v-model="coreBlockDialog.visible" :title="coreBlockDialog.editId ? '编辑核心块' : '新建核心块'" width="560px" top="8vh">
      <el-form :model="coreBlockDialog.form" label-width="110px">
        <el-form-item v-if="!coreBlockDialog.editId" label="块键" required>
          <el-select v-model="coreBlockDialog.form.blockKey" filterable allow-create default-first-option style="width: 100%"
            placeholder="选择标准键或输入 custom: 自定义键">
            <el-option label="identity · 身份" value="identity" />
            <el-option label="personality · 性格" value="personality" />
            <el-option label="beliefs · 核心信念" value="beliefs" />
            <el-option label="values · 价值观" value="values" />
            <el-option label="goals · 长期目标" value="goals" />
            <el-option label="relations · 关键关系" value="relations" />
          </el-select>
        </el-form-item>
        <el-form-item v-else label="块键">
          <el-tag effect="plain">{{ coreBlockKeyLabel(coreBlockDialog.form.blockKey) }}</el-tag>
        </el-form-item>
        <el-form-item label="块显示名">
          <el-input v-model="coreBlockDialog.form.blockName" maxlength="100" placeholder="如 核心信念（选填，缺省用块键/标准名）" />
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input v-model="coreBlockDialog.form.content" type="textarea" :rows="5" maxlength="400" show-word-limit
            placeholder="核心块内容（≤400 字，恒在注入上下文；如：守护家园高于一切）" />
        </el-form-item>
        <el-form-item label="重要度">
          <el-rate v-model="coreBlockDialog.form.importance" :max="5" />
          <!-- ⚠️ 这里原先是 class="gp-hint"——那个类定义在 CharacterView 的 scoped style 里（目标/计划页的提示）。
               搬迁后本组件是独立作用域，scoped 样式不跨组件生效，提示就悄悄失去了字号/颜色。
               死类审计（frontend/scripts/audit-dead-classes.py 的「疑似死类」一档）抓到了它；
               改为本组件自己的类名并就近定义。 -->
          <span class="core-block-hint">决定恒在注入顺序（高重要度优先注入）</span>
        </el-form-item>
        <el-form-item label="agent 自编辑">
          <el-switch v-model="coreBlockDialog.form.editableByAgent" :active-value="1" :inactive-value="0"
            active-text="允许 AI 自编辑" inactive-text="仅用户可改（只读块）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="coreBlockDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="savingCoreBlock" @click="saveCoreBlock">保存</el-button>
      </template>
    </el-dialog>

    <!-- M4 L2 记忆编辑对话框（版本链更新：新条 version+1 + 旧条软删 + 旧条自动归档 superseded） -->
    <el-dialog v-model="editMemoryDialog.visible" title="编辑记忆（版本递增）" width="560px">
      <div class="edit-memory-hint">更新后自动生成新版本（version+1），旧版本写入归档（原因=被覆盖），读取以最新版本为准。</div>
      <el-form label-width="70px">
        <el-form-item label="内容" required>
          <el-input v-model="editMemoryDialog.form.content" type="textarea" :rows="6" show-word-limit
            placeholder="记忆内容（更新后旧版自动归档 superseded）" />
        </el-form-item>
        <el-form-item label="重要度">
          <el-rate v-model="editMemoryDialog.form.importance" :max="5" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editMemoryDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="editMemoryDialog.saving" @click="saveEditMemory">保存</el-button>
      </template>
    </el-dialog>

    <!-- M5 注入预览对话框（分场景注入组合：各段文本 + 命中记忆得分 + token 预算） -->
    <el-dialog v-model="injectionDialog.visible" title="记忆注入预览（M5 分场景注入）" width="720px">
      <div class="ip-controls">
        <el-select v-model="injectionDialog.scenario" size="small" style="width: 150px" @change="loadInjectionPreview">
          <el-option v-for="s in injectionScenarios" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
        <el-input v-model="injectionDialog.query" size="small" placeholder="检索 query（决策/感知情境文本，可空）" style="width: 220px" clearable @keyup.enter="loadInjectionPreview" />
        <el-button size="small" type="primary" :loading="injectionDialog.loading" @click="loadInjectionPreview">预览</el-button>
        <span v-if="injectionDialog.data" class="ip-total dim">合计 {{ injectionDialog.data.totalTokens }} token · 场景 {{ injectionDialog.scenario }}</span>
      </div>
      <div v-if="injectionDialog.data" class="ip-body">
        <div v-if="injectionDialog.data.segments.length === 0" class="side-empty">无内容——该角色当前无可注入记忆（各段均空）</div>
        <div v-for="seg in injectionDialog.data.segments" :key="seg.key" class="ip-seg">
          <div class="ip-seg-head">
            <span class="mono ip-key">{{ seg.key }}</span>
            <span class="ip-title">{{ seg.title }}</span>
            <span class="dim mono">≈ {{ seg.tokens }} token</span>
          </div>
          <pre class="ip-text">{{ seg.text }}</pre>
          <div v-if="seg.hits && seg.hits.length" class="ip-hits">
            <div v-for="h in seg.hits" :key="h.id" class="ip-hit">
              <span class="mono dim">#{{ h.id }}</span>
              <el-tag size="small" effect="plain" :type="hitTypeTag(h.type)">{{ hitTypeLabel(h.type) }}</el-tag>
              <span class="dim">重要度 {{ h.importance }} · 得分 {{ h.score }}</span>
              <el-tag v-if="h.stale" size="small" type="warning">可能过时</el-tag>
              <span class="ip-hit-content">{{ h.content }}</span>
            </div>
          </div>
        </div>
      </div>
      <div v-else class="dim">选择场景后点击「预览」，查看该角色实际注入对话/决策/感知/反思的组装组合与预算消耗。</div>
    </el-dialog>
  </template>
</template>

<script setup>
/**
 * 角色详情 · 「记忆」标签页面板（V2.0 §13.4 巨型组件拆分第二步）。
 *
 * 为什么拆（拆分前 CharacterView.vue 2237 行 / 12 个标签页全挤在一个 setup 里）：
 *   「记忆」是 12 个标签页里最重的一个——它自己就带 5 组接口（L2 长期记忆 / L1 工作记忆 /
 *   L3 核心记忆 / M5 治理健康度 + 注入预览 / O1 短期模糊 + O2 决策协调）与 3 个对话框，
 *   占原视图约 178 行模板 + 330 行脚本 + 64 行样式。接口调用随面板一起下沉：面板只认两个
 *   输入（角色 + 项目 id），父组件不再需要知道「记忆是怎么加载的、什么时候加载」。
 *
 * 与上一步的「概览」面板（纯搬迁、零接口）不同，本面板**自带**加载时机：
 *   · 角色切换（character.id 变化）→ 全量重载（原来写在父组件 selectChar() 里，属跨组件耦合）；
 *   · 父组件切到本标签页 → 调 reload()（记忆是「对话回复完成后异步抽取」，停留页面期间产生的
 *     记忆不会自动出现，切标签重拉保证所见即最新——原逻辑原样保留）。
 *
 * @see docs/设计文档/HolzynActor_界面布局与IA人性化重构_方案_V2.0.md §13.4
 */
import { ref, reactive, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchMemories, deleteMemory, extractCharacterMemories, fetchWorkingMemories,
  fetchCoreMemories, createCoreBlock, updateCoreBlock, deleteCoreBlock,
  updateMemory, manualArchive,
  fetchInjectionPreview, fetchMemoryHealth, consolidateMemory,
  fetchFuzzStates, triggerFuzz,
  fetchForesightRecords, fetchStateDeltas, fetchConflictRecords
} from '@/shared/api'
// 统一身份引用（G1a：special:/crowd: 已废弃，一律 npc:{id}）
import { npcRef } from '@/shared/npcRef'
// V2.0 批次二：空态统一走 EmptyState
import EmptyState from '@/shared/components/EmptyState.vue'

const props = defineProps({
  /** 当前选中角色（null = 未选中，显示引导空态） */
  character: { type: Object, default: null },
  /** 所属项目 id（本面板全部接口的第一个参数；由父组件显式传入，面板自身不读路由） */
  projectId: { type: Number, required: true }
})

const memories = ref([])
const memoryLoading = ref(false)
const extracting = ref(false)
// M2 L1 工作记忆（active 工作项：草稿/任务/上下文/感知）
const workingMemories = ref([])
const workingMemoryLoading = ref(false)
// M3 L3 核心记忆（静态基线只读 + 动态核心块 CRUD）
const coreBlocks = ref([])
const coreBaselineText = ref('')
const coreMemoryLoading = ref(false)
const savingCoreBlock = ref(false)
const coreBlockDialog = reactive({
  visible: false, editId: null,
  form: { blockKey: 'beliefs', blockName: '', content: '', importance: 3, editableByAgent: 1 }
})
// M4 L2 记忆编辑对话框（版本链更新：新条 version+1 + 旧条软删 + 旧条自动归档 superseded）
const editMemoryDialog = reactive({ visible: false, editId: null, saving: false, form: { content: '', importance: 3 } })
// M5 记忆治理健康度（归档量/版本链/分层分布——角色维度） + 注入预览对话框 + 巩固编排
const memoryHealth = ref(null)
const healthLoading = ref(false)
const injectionDialog = reactive({ visible: false, scenario: 'dialog', query: '', loading: false, data: null })
const injectionScenarios = [
  { value: 'dialog', label: '对话 dialog' },
  { value: 'decision', label: '决策 decision' },
  { value: 'perception', label: '感知 perception' },
  { value: 'reflection', label: '反思 reflection' }
]
// O1 记忆内核扩展：L1 短期模糊状态（艾宾浩斯渐进遗忘 + 睡眠清洗；只读展示 + 手动触发）
const fuzzStates = ref([])
const fuzzLoading = ref(false)
const fuzzTriggerId = ref(null)
// O2 决策与协调（P1-1 前瞻推演 / P1-2 state delta / P1-3 冲突协商；只读回看）
const dcForesight = ref([])
const dcConflicts = ref([])
const dcDeltas = ref([])
const dcLoading = ref(false)

/** 加载当前角色记忆列表（随后串行刷新本面板另外 5 组数据） */
async function loadMemories() {
  if (!props.character) { memories.value = []; return }
  memoryLoading.value = true
  try {
    const page = await fetchMemories(props.projectId, { characterId: props.character.id, page: 1, size: 50 })
    memories.value = page.list || []
  } catch (_) { memories.value = [] }
  finally { memoryLoading.value = false }
  await loadWorkingMemories()
  await loadCoreMemories()
  await loadMemoryHealth()
  await loadFuzzStates()
  await loadDecisionCoord()
}

// ===== M5 记忆治理健康度 + 注入预览 + 巩固编排 =====

/** 加载当前角色记忆治理健康度（归档量/版本链/分层分布，角色维度 npc:{id}） */
async function loadMemoryHealth() {
  if (!props.character) { memoryHealth.value = null; return }
  healthLoading.value = true
  try {
    const r = await fetchMemoryHealth(props.projectId, { characterRef: npcRef(props.character.id) })
    // ⚠️ 响应拦截器（shared/api/http.js）在 code=200 时已把 {code,data,message} 解包成 data 本体，
    // 所以 r 就是 {archive,versionChain,tier}。旧写法 `r?.data` 恒为 undefined →
    // 三张健康度卡片永远显示 0/-（搬迁验证抓到的既有缺陷：接口 200 且字段俱全，界面永远是零）。
    // 这里按「先认载荷特征字段、再退回未解包形状」取值，两种后端形状都能显示。
    memoryHealth.value = (r && (r.archive || r.versionChain || r.tier)) ? r : (r?.data || null)
  } catch (_) { memoryHealth.value = null }
  finally { healthLoading.value = false }
}

/** 打开注入预览对话框并立即预览（默认 dialog 场景） */
function openInjectionPreview() {
  injectionDialog.visible = true
  injectionDialog.data = null
  injectionDialog.query = ''
  loadInjectionPreview()
}

/** 按当前场景组装注入预览（分场景：dialog/decision/perception/reflection） */
async function loadInjectionPreview() {
  if (!props.character) return
  injectionDialog.loading = true
  try {
    const r = await fetchInjectionPreview(props.projectId, {
      characterRef: npcRef(props.character.id),
      scenario: injectionDialog.scenario,
      query: injectionDialog.query || undefined
    })
    // 同上：r 就是载荷（含 segments/totalTokens）。旧写法 `r?.data` 恒 undefined →
    // 这个对话框**永远**停在「选择场景后点击『预览』」的引导文案（实测：接口 200 且返回 3 段文本，
    // 界面依旧空白）——整块功能等于不可用。
    injectionDialog.data = (r && Array.isArray(r.segments)) ? r : (r?.data || null)
  } catch (e) { ElMessage.error(e.message || '注入预览失败') }
  finally { injectionDialog.loading = false }
}

/** 手动触发统一巩固编排（M5：L1 TTL/巩固扫描 + 归档超龄/保留/二次清理，scene=memory_manage trace） */
async function doMaintenance() {
  try {
    await ElMessageBox.confirm('触发统一维护（L1 工作记忆 TTL 清理/巩固扫描 + 归档超龄扫描/保留滚动/二次清理）？', '统一维护', { type: 'warning' })
  } catch (_) { return }
  try {
    const r = await consolidateMemory({ maintenance: true })
    // 同上（拦截器已解包）：旧写法 `r.data?.maintenanceSteps` 恒 undefined → 提示永远是「0 步」，
    // 即使后端真的扫了若干步。取值两种形状都认。
    const steps = r?.maintenanceSteps ?? r?.data?.maintenanceSteps ?? 0
    ElMessage.success(`统一维护完成：${steps} 步`)
    await loadMemories()
  } catch (e) { ElMessage.error(e.message || '统一维护失败') }
}

/** 归档原因分组摘要（age/superseded/downgraded/evicted/manual） */
function reasonSummary(byReason) {
  if (!byReason || Object.keys(byReason).length === 0) return '-'
  return Object.entries(byReason).map(([k, v]) => `${k}:${v}`).join(' · ')
}

/** 命中记忆类型显示名（预览命中 HitVO：仅 type，无 subtype） */
function hitTypeLabel(t) {
  return ({ semantic: '语义', episodic: '情节', reflection: '反思', plan: '计划' })[t] || t || '记忆'
}

/** 命中记忆类型标签色（预览命中） */
function hitTypeTag(t) {
  return ({ semantic: 'info', episodic: 'warning', reflection: 'danger', plan: 'success' })[t] || 'info'
}

/** 加载当前角色 L3 核心记忆（M3：静态基线只读 + 动态核心块列表） */
async function loadCoreMemories() {
  if (!props.character) { coreBlocks.value = []; coreBaselineText.value = ''; return }
  coreMemoryLoading.value = true
  try {
    const vo = await fetchCoreMemories(props.projectId, { characterId: props.character.id })
    coreBaselineText.value = vo?.baselineText || ''
    coreBlocks.value = vo?.blocks || []
  } catch (_) { coreBlocks.value = []; coreBaselineText.value = '' }
  finally { coreMemoryLoading.value = false }
}

/** 打开核心块新增/编辑对话框（M3；编辑时预填，块键编辑态只读） */
function openCoreBlockDialog(b = null) {
  if (b) {
    coreBlockDialog.editId = b.id
    coreBlockDialog.form = {
      blockKey: b.blockKey, blockName: b.blockName || '', content: b.content || '',
      importance: b.importance || 3, editableByAgent: b.editableByAgent === 0 ? 0 : 1
    }
  } else {
    coreBlockDialog.editId = null
    coreBlockDialog.form = { blockKey: 'beliefs', blockName: '', content: '', importance: 3, editableByAgent: 1 }
  }
  coreBlockDialog.visible = true
}

/** 保存核心块（新建/编辑，M3；编辑走版本链 version+1） */
async function saveCoreBlock() {
  const f = coreBlockDialog.form
  if (!f.blockKey || !f.blockKey.trim()) return ElMessage.warning('请选择/输入块键')
  if (!f.content || !f.content.trim()) return ElMessage.warning('核心块内容不能为空')
  savingCoreBlock.value = true
  try {
    if (coreBlockDialog.editId) {
      await updateCoreBlock(props.projectId, coreBlockDialog.editId, {
        blockName: f.blockName, content: f.content, importance: f.importance, editableByAgent: f.editableByAgent
      })
      ElMessage.success('核心块已更新（版本 +1）')
    } else {
      await createCoreBlock(props.projectId, {
        characterRef: npcRef(props.character.id), blockKey: f.blockKey.trim(),
        blockName: f.blockName, content: f.content, importance: f.importance, editableByAgent: f.editableByAgent
      })
      ElMessage.success('核心块已创建（恒在注入上下文）')
    }
    coreBlockDialog.visible = false
    await loadCoreMemories()
  } catch (e) {
    ElMessage.error(e.message || '保存核心块失败')
  } finally { savingCoreBlock.value = false }
}

/** 删除核心块（M3，软删确认） */
async function doDeleteCoreBlock(b) {
  try {
    await ElMessageBox.confirm(`确认删除核心块「${b.blockName || b.blockKey}」？删除后不再恒在注入。`, '删除确认', { type: 'warning' })
  } catch (_) { return }
  try {
    await deleteCoreBlock(props.projectId, b.id)
    ElMessage.success('核心块已删除')
    await loadCoreMemories()
  } catch (e) {
    ElMessage.error(e.message || '删除核心块失败')
  }
}

/** 手动归档核心块（M4：写归档原文冷存 + 软删，不再恒在注入但仍可检索/还原） */
async function doArchiveCoreBlock(b) {
  try {
    await ElMessageBox.confirm(`确认归档核心块「${b.blockName || b.blockKey}」？将写入记忆归档（冷存储），不再恒在注入，但仍可检索/还原。`, '归档确认', { type: 'warning' })
  } catch (_) { return }
  try {
    const r = await manualArchive(props.projectId, { sourceTable: 'actor_core_memory', ids: [b.id] })
    ElMessage.success(`核心块已归档（${r.archived} 条）`)
    await loadCoreMemories()
  } catch (e) {
    ElMessage.error(e.message || '归档核心块失败')
  }
}

/** 打开 L2 记忆编辑对话框（M4 版本链：更新后新条 version+1，旧条软删+自动归档 superseded） */
function openEditMemoryDialog(m) {
  editMemoryDialog.editId = m.id
  editMemoryDialog.form.content = m.content || ''
  editMemoryDialog.form.importance = m.importance || 3
  editMemoryDialog.visible = true
}

/** 保存 L2 记忆更新（M4 版本链） */
async function saveEditMemory() {
  const f = editMemoryDialog.form
  if (!f.content || !f.content.trim()) return ElMessage.warning('记忆内容不能为空')
  editMemoryDialog.saving = true
  try {
    await updateMemory(editMemoryDialog.editId, { projectId: props.projectId, content: f.content, importance: f.importance })
    ElMessage.success('记忆已更新（版本 +1，旧版已归档）')
    editMemoryDialog.visible = false
    await loadMemories()
  } catch (e) {
    ElMessage.error(e.message || '更新记忆失败')
  } finally { editMemoryDialog.saving = false }
}

/** 手动归档 L2 长期记忆（M4：写归档原文冷存 + 软删） */
async function doArchiveMemory(m) {
  try {
    await ElMessageBox.confirm('确认归档该记忆？将写入记忆归档（冷存储），不再参与该角色记忆注入，但仍可检索/还原。', '归档确认', { type: 'warning' })
  } catch (_) { return }
  try {
    const r = await manualArchive(props.projectId, { sourceTable: 'actor_memory', ids: [m.id] })
    ElMessage.success(`记忆已归档（${r.archived} 条）`)
    await loadMemories()
  } catch (e) {
    ElMessage.error(e.message || '归档记忆失败')
  }
}

/** 核心块标准键显示名（M3） */
function coreBlockKeyLabel(key) {
  return { identity: '身份', personality: '性格', beliefs: '核心信念', values: '价值观', goals: '长期目标', relations: '关键关系' }[key] || key
}

/** 核心块标签色（M3：自定义=success / 标准=primary） */
function coreBlockTag(b) {
  return (b.blockKey || '').startsWith('custom:') ? 'success' : 'primary'
}

/** 加载当前角色 L1 工作记忆（M2：active 草稿/任务/上下文/感知，只读展示） */
async function loadWorkingMemories() {
  if (!props.character) { workingMemories.value = []; return }
  workingMemoryLoading.value = true
  try {
    const page = await fetchWorkingMemories(props.projectId, { characterId: props.character.id, status: 'active', page: 1, size: 50 })
    workingMemories.value = page.list || []
  } catch (_) { workingMemories.value = [] }
  finally { workingMemoryLoading.value = false }
}

/** L1 工作项类型显示名（M2） */
function workingTypeLabel(w) {
  const t = w.itemType || 'draft'
  return { draft: '草稿', task: '任务', context: '上下文', perception: '感知' }[t] || t
}

/** L1 工作项类型标签色（M2：草稿=info / 任务=primary / 上下文=success / 感知=warning） */
function workingTypeTag(w) {
  const t = w.itemType || 'draft'
  return { draft: 'info', task: 'primary', context: 'success', perception: 'warning' }[t] || 'info'
}

// ===== O1 记忆内核扩展：L1 短期模糊 =====

/** 加载当前角色 L1 模糊状态（active 优先；含蒸馏/归档历史，上限 100） */
async function loadFuzzStates() {
  if (!props.character) { fuzzStates.value = []; return }
  fuzzLoading.value = true
  try {
    const list = await fetchFuzzStates(props.projectId, { characterRef: npcRef(props.character.id) })
    fuzzStates.value = Array.isArray(list) ? list : (list?.data || [])
  } catch (_) { fuzzStates.value = [] }
  finally { fuzzLoading.value = false }
}

/** 手动触发单条模糊（无视艾宾浩斯间隔立即执行一次，O1a 调试/冒烟）。 */
async function doTriggerFuzz(f) {
  fuzzTriggerId.value = f.memoryWorkingId
  try {
    const r = await triggerFuzz(props.projectId, { memoryWorkingId: f.memoryWorkingId })
    ElMessage.success(r?.triggered ? `已模糊（fuzz_level 上升）` : '未触发（状态非 active）')
    await loadFuzzStates()
  } catch (e) {
    ElMessage.error(e.message || '触发失败')
  } finally { fuzzTriggerId.value = null }
}

/** 模糊度标签色（0.7+ 深 / 0.3+ 中 / 低清晰） */
function fuzzLevelTag(f) { return f >= 0.7 ? 'danger' : f >= 0.3 ? 'warning' : 'success' }

/** 模糊状态标签色（active=primary / distilled=success / archived=info） */
function fuzzStatusTag(s) { return s === 'distilled' ? 'success' : s === 'archived' ? 'info' : 'primary' }

/** 模糊状态显示名 */
function fuzzStatusLabel(s) {
  return { active: '模糊中', distilled: '已蒸馏 L2', archived: '已归档' }[s] || s || 'active'
}

// ===== O2 决策与协调（P1-1 前瞻推演 / P1-2 state delta / P1-3 冲突协商） =====

/** 加载当前角色 O2 记录：前瞻推演 + 冲突协商 + 状态增量（npcRef 过滤；R 包裹解包） */
async function loadDecisionCoord() {
  if (!props.character) { dcForesight.value = []; dcConflicts.value = []; dcDeltas.value = []; return }
  dcLoading.value = true
  const ref = npcRef(props.character.id)
  try {
    const [f, c, d] = await Promise.all([
      fetchForesightRecords(props.projectId, { npcRef: ref, limit: 20 }),
      fetchConflictRecords(props.projectId, { npcRef: ref, limit: 20 }),
      fetchStateDeltas(props.projectId, { npcRef: ref, limit: 20 })
    ])
    dcForesight.value = Array.isArray(f) ? f : (f?.data || [])
    dcConflicts.value = Array.isArray(c) ? c : (c?.data || [])
    dcDeltas.value = Array.isArray(d) ? d : (d?.data || [])
  } catch (_) { dcForesight.value = []; dcConflicts.value = []; dcDeltas.value = [] }
  finally { dcLoading.value = false }
}

/** 冲突处置显示名（P1-3 resolution code → 中文） */
function dcResolutionLabel(r) {
  return { takeover: '强占', negotiated: '协商让步', fallback_wait: '等待兜底' }[r] || r || '—'
}

/** 冲突处置标签色（强占=danger / 协商=warning / 等待兜底=info） */
function dcResolutionTag(r) {
  return { takeover: 'danger', negotiated: 'warning', fallback_wait: 'info' }[r] || 'info'
}

/** 前瞻候选 JSON → 多行「① action → consequence」文本（P1-1 展示） */
function prettyCandidates(json) {
  try {
    const arr = JSON.parse(json || '[]')
    if (!Array.isArray(arr)) return ''
    return arr.map((c, i) => `${i + 1}. ${c.action || ''}${c.consequence ? ' → ' + c.consequence : ''}`).join('\n')
  } catch (_) { return '' }
}

/** state delta changes_json → 可读摘要（P1-2：优先取 action/location 变化，其余字段逐一列出） */
function dcActionOf(changesJson) {
  try {
    const obj = JSON.parse(changesJson || '{}')
    const parts = []
    for (const [k, v] of Object.entries(obj)) {
      if (v && typeof v === 'object') {
        const from = v.from, to = v.to
        parts.push(`${k}: ${from === undefined ? '∅' : JSON.stringify(from)} → ${to === undefined ? '∅' : JSON.stringify(to)}`)
      } else {
        parts.push(`${k}: ${JSON.stringify(v)}`)
      }
    }
    return parts.join('；') || '无变化'
  } catch (_) { return changesJson || '无变化' }
}

/** 游戏秒 → D天 HH:00 文本（L1 复查时刻展示） */
function gameSecondText(gs) {
  if (gs == null || gs === '') return '—'
  const day = Math.floor(gs / 86400)
  const hour = Math.floor((gs % 86400) / 3600)
  return `D${day} ${String(hour).padStart(2, '0')}:00`
}

/** 时间格式化（YYYY-MM-DD HH:mm）——与 CharacterView/ArchiveView 等处同形，本仓库既有惯例是各页面自带一份 */
function fmtTime(t) { return t ? String(t).slice(0, 16).replace('T', ' ') : '' }

/** 删除单条记忆（软删） */
async function doDeleteMemory(m) {
  try {
    await ElMessageBox.confirm(`确认删除该条${m.subtype === 'summary' ? '摘要' : '记忆'}？删除后不再注入后续对话。`, '删除确认', { type: 'warning' })
    await deleteMemory(m.id)
    ElMessage.success('已删除')
    await loadMemories()
  } catch (_) { /* 用户取消 */ }
}

/** 手动触发当前角色记忆抽取 */
async function doExtractMemories() {
  if (!props.character) return
  extracting.value = true
  try {
    const res = await extractCharacterMemories(props.character.id)
    ElMessage.success(`抽取完成，新增 ${res.added || 0} 条事实`)
    await loadMemories()
  } catch (e) { ElMessage.error(e.message || '抽取失败') }
  finally { extracting.value = false }
}

/** 重要度颜色映射 */
function impTag(i) { return i >= 4 ? 'danger' : (i >= 3 ? 'warning' : 'info') }

/** 记忆类型显示名（M1 起 type+subtype 取代 kind：semantic 摘要/事实、episodic 经历、reflection 反思、plan 计划） */
function typeLabel(m) {
  if (m.type === 'semantic') return m.subtype === 'summary' ? '摘要' : '事实'
  if (m.type === 'episodic') return '经历'
  if (m.type === 'reflection') return '反思'
  if (m.type === 'plan') return '计划'
  return '记忆'
}

/** 记忆类型标签色（摘要=primary / 事实=info / 经历=warning / 反思=danger / 计划=success） */
function typeTag(m) {
  if (m.type === 'semantic') return m.subtype === 'summary' ? 'primary' : 'info'
  if (m.type === 'episodic') return 'warning'
  if (m.type === 'reflection') return 'danger'
  if (m.type === 'plan') return 'success'
  return 'info'
}

/** 记忆条目样式类（M1 起按 type+subtype 映射到 CSS：summary/fact/observation 沿用 + reflection/plan 新增） */
function memoryClass(m) {
  if (m.type === 'semantic') return m.subtype === 'summary' ? 'summary' : 'fact'
  if (m.type === 'episodic') return 'observation'
  return m.type || 'fact'
}

/** 面板对外的唯一入口：父组件切到「记忆」标签页时调用（等价于原 onInfoTabChange(name==='memory') 的行为） */
defineExpose({ reload: loadMemories })

// 角色切换 → 全量重载。原来这一步写在父组件 selectChar() 里（父组件替子组件决定何时加载），
// 拆分时收回面板自己处理：面板有 character 就加载，没有就清空。
watch(() => props.character?.id, () => { loadMemories() })
// 面板挂载时若已有选中角色（例如 v-if 重挂载）也要加载一次；未选中角色时什么都不做（等 watch）
onMounted(() => { if (props.character) loadMemories() })
</script>

<style scoped>
.main-empty { border: none; box-shadow: none; display: flex; align-items: center; justify-content: center; height: 100%; }
.side-empty { color: var(--text-secondary); font-size: 0.82rem; padding: 12px 4px; }
.mono { font-family: var(--font-mono, Consolas, 'Courier New', monospace); }
/* V2.0 批次四：注入预览对话框里 5 处 class="dim" 在本仓库**从来没有对应的样式规则**
   （全量 grep `\.dim\b` 只命中 dim-title/pa-dim-* 这类别的类名，构建产物里也没有 .dim 规则），
   即这 5 处文字一直按正文色渲染、比预期更抢眼。搬迁面板时补上定义：它表达的是「次要说明文字」，
   取次级文字令牌。 */
.dim { color: var(--text-secondary); }

/* ==== 以下样式块自 CharacterView.vue 原样搬迁（V2.0 §13.4） ==== */
/* 记忆 Tab */
.memory-ops { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.memory-hint { font-size: 0.72rem; color: var(--text-secondary); }
.memory-list { display: flex; flex-direction: column; gap: 8px; max-height: var(--panel-scroll-max, min(46vh, 520px)); overflow-y: auto; }
.memory-item { border: 1px solid var(--border-light); border-radius: 8px; padding: 8px 10px; }
.memory-item.summary { background: var(--state-success-bg); }
.memory-item.fact { background: var(--bg-layer-2); }
.memory-item.observation { background: color-mix(in srgb, var(--state-warning) 8%, transparent); }
.memory-item.reflection { background: color-mix(in srgb, var(--state-danger) 8%, transparent); }
.memory-item.plan { background: color-mix(in srgb, var(--state-primary) 8%, transparent); }
.memory-head { display: flex; align-items: center; gap: 6px; }
.memory-op { opacity: 0; transition: opacity .15s; }
.memory-item:hover .memory-op { opacity: 1; }
.memory-del { margin-left: auto; opacity: 0; transition: opacity .15s; }
.memory-item:hover .memory-del { opacity: 1; }
.edit-memory-hint { font-size: 0.8rem; color: var(--text-secondary); margin-bottom: 12px; }
.memory-content { font-size: 0.8rem; color: var(--text-primary); margin-top: 6px; line-height: 1.5; white-space: pre-wrap; word-break: break-word; }

/* M2 L1 工作记忆（热缓冲） */
.working-memory-sec { margin-top: 14px; border-top: 1px dashed var(--border-light); padding-top: 10px; }
.working-memory-title { font-size: 0.78rem; font-weight: 600; color: var(--text-secondary); margin-bottom: 8px; }
.working-memory-list { display: flex; flex-direction: column; gap: 8px; max-height: 30vh; overflow-y: auto; }
.working-memory-item { border: 1px dashed var(--border-light); border-radius: 8px; padding: 8px 10px; background: color-mix(in srgb, var(--state-primary) 4%, transparent); }
.working-memory-head { display: flex; align-items: center; gap: 6px; }
.working-memory-time { font-size: 0.7rem; color: var(--text-placeholder); margin-left: auto; }
.working-memory-content { font-size: 0.78rem; color: var(--text-primary); margin-top: 5px; line-height: 1.5; white-space: pre-wrap; word-break: break-word; }

/* O1 记忆内核扩展：L1 短期模糊（原版 vs 当前模糊版本对照） */
.fuzz-original { font-size: 0.72rem; color: var(--text-secondary); margin-top: 4px; line-height: 1.5; word-break: break-word; }
.fuzz-original-label { color: var(--text-placeholder); }

/* M5 记忆治理健康度（角色维度摘要卡片） */
.memory-ops-right { display: flex; align-items: center; gap: 2px; }
.memory-health-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 10px; }
.health-item { border: 1px solid var(--border-light); border-radius: 8px; padding: 8px 10px; background: var(--bg-layer-2); }
.health-label { font-size: 0.72rem; color: var(--text-secondary); margin-bottom: 4px; }
.health-value { font-size: 1.1rem; font-weight: 700; color: var(--text-primary); margin-bottom: 4px; }
.health-sub { font-size: 0.68rem; color: var(--text-secondary); line-height: 1.6; }

/* M5 注入预览对话框 */
.ip-controls { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.ip-total { margin-left: auto; }
.ip-body { max-height: 55vh; overflow-y: auto; display: flex; flex-direction: column; gap: 10px; }
.ip-seg { border: 1px solid var(--border-light); border-radius: 8px; padding: 8px 10px; }
.ip-seg-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.ip-key { font-size: 0.7rem; color: var(--state-primary); background: color-mix(in srgb, var(--state-primary) 8%, transparent); padding: 1px 6px; border-radius: 4px; }
.ip-title { font-weight: 600; font-size: 0.78rem; color: var(--text-primary); }
.ip-text { margin: 0; padding: 6px 8px; background: var(--bg-layer-2); border: 1px dashed var(--border-light); border-radius: 6px; font-size: 0.72rem; line-height: 1.6; color: var(--text-primary); white-space: pre-wrap; word-break: break-word; font-family: var(--font-mono, Consolas, 'Courier New', monospace); }
.ip-hits { display: flex; flex-direction: column; gap: 4px; margin-top: 6px; }
.ip-hit { display: flex; align-items: center; gap: 6px; font-size: 0.72rem; color: var(--text-secondary); flex-wrap: wrap; }
.ip-hit-content { color: var(--text-primary); word-break: break-all; }

/* M3 L3 核心记忆（恒在：静态基线只读 + 动态核心块 CRUD） */
.core-memory-sec { margin-top: 14px; border-top: 1px dashed var(--border-light); padding-top: 10px; }
.core-memory-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.core-memory-title { font-size: 0.78rem; font-weight: 600; color: var(--text-secondary); }
.core-memory-body { display: flex; flex-direction: column; gap: 8px; max-height: 34vh; overflow-y: auto; }
.core-baseline { border: 1px solid var(--border-light); border-radius: 8px; padding: 8px 10px; background: color-mix(in srgb, var(--state-success) 6%, transparent); }
.core-baseline-title { font-size: 0.72rem; font-weight: 600; color: var(--state-success); margin-bottom: 5px; }
.core-baseline-text { font-size: 0.76rem; color: var(--text-primary); line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.core-block-item { border: 1px solid var(--border-light); border-radius: 8px; padding: 8px 10px; background: color-mix(in srgb, var(--state-warning) 5%, transparent); }
.core-block-head { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.core-block-version { font-size: 0.7rem; color: var(--text-placeholder); }
.core-block-ops { margin-left: auto; display: flex; align-items: center; }
.core-block-content { font-size: 0.78rem; color: var(--text-primary); margin-top: 6px; line-height: 1.5; white-space: pre-wrap; word-break: break-word; }
/* 核心块对话框里「重要度」下面的说明文字（原 gp-hint 是父组件 scoped 类，搬迁后失效——见模板注释） */
.core-block-hint { font-size: 0.72rem; color: var(--text-secondary); }

/* ===== O2 决策与协调（P1-1 前瞻推演 / P1-3 冲突协商 / P1-2 状态增量）=====
   ⚠️ 这一组 7 个类（.dc-wrap/.dc-empty/.dc-sec/.dc-sec-title/.dc-item/.dc-reason/.dc-cands）
   在搬迁前**全仓库没有任何样式规则**——是本轮新加的死类审计（tmp/plan-input/audit-dead-classes.py）
   查出来的：整段 O2 内容是"裸文本堆叠"，三组分节挤在一起、没有分节标题层级，读起来像一团流水账。
   这里补上定义，沿用工作记忆/核心记忆同一套视觉语言（虚线卡片 + 次级色小标题），不引入新令牌。 */
.dc-wrap { display: flex; flex-direction: column; gap: 10px; }
.dc-empty { color: var(--text-secondary); font-size: 0.8rem; padding: 12px 4px; line-height: 1.6; }
.dc-sec { display: flex; flex-direction: column; gap: 8px; }
.dc-sec-title { font-size: 0.74rem; font-weight: 600; color: var(--text-secondary); }
.dc-item { border: 1px dashed var(--border-light); border-radius: 8px; padding: 8px 10px; background: color-mix(in srgb, var(--state-primary) 4%, transparent); }
/* 「触发原因 / 争什么 / plan #id」这类短标识：比正文弱一档，让右侧的游戏时刻（.working-memory-time）仍是最弱的一档 */
.dc-reason { font-size: 0.74rem; color: var(--text-secondary); min-width: 0; word-break: break-word; }
/* 前瞻候选是多行文本（1. action → consequence），等宽 + 保留换行 */
.dc-cands { margin-top: 5px; font-size: 0.72rem; line-height: 1.6; color: var(--text-secondary); white-space: pre-wrap; word-break: break-word; font-family: var(--font-mono, Consolas, 'Courier New', monospace); }
</style>
