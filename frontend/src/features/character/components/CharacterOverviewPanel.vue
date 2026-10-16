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

  角色详情 · 「概览」标签页面板。

  为什么单独拆出来（V2.0 §13.4 巨型组件拆分）：
    CharacterView.vue 原本 2333 行 / 8 个标签页 / 全部状态挤在一个 setup 里，任何一处改动都要在
    两千行里定位，且 8 个标签页的状态无法分别推理。拆分按「一个标签页 = 一个面板组件」起步，
    先把耦合最低、边界最清楚的「概览」拿出来：
      · 它的输入只有两个（角色本身 + 该角色的发育条目），没有任何接口调用；
      · 它独占的三样东西（详细信息折叠态、思考档位文案、.ov-* 样式）在视图里没有第二个使用者。
    这是**纯搬迁**：像素、文案、交互一律不变，改动只为让后续拆「状态」「记忆」等重面板有先例可循。

  设计取舍：
    · 折叠态由面板自己持有（`detailExpanded`），并在角色切换时自动复位——原本这个复位逻辑写在
      父组件的 selectChar() 里，属于「子组件内部状态被父组件代管」，正是拆分要消除的耦合。
    · 「详细信息」长文默认折叠：不折叠时一个角色描述就能把概览页撑成一片文字墙。
-->
<template>
  <div v-if="!character" class="main-empty">
    <EmptyState size="inline" icon="ic_public_contacts" title="从左侧选择一个角色查看详情" />
  </div>
  <template v-else>
    <div class="ov-grid">
      <div class="ov-item"><span class="k">姓名</span><span class="v">{{ character.name }}</span></div>
      <div class="ov-item"><span class="k">头衔</span><span class="v">{{ character.title || '—' }}</span></div>
      <div class="ov-item"><span class="k">类型</span><span class="v">{{ character.type === 'common' ? '普通型' : '特殊型 NPC' }}</span></div>
      <div class="ov-item"><span class="k">是否主角</span><span class="v">{{ character.isProtagonist === 1 ? '是' : '否' }}</span></div>
      <div class="ov-item"><span class="k">位置</span><span class="v">
        <template v-if="character.location">{{ character.location }}<el-tag v-if="character.locationId" size="small" type="success" effect="plain">已归一</el-tag></template>
        <template v-else>—</template>
      </span></div>
      <div class="ov-item"><span class="k">重要度</span><span class="v"><el-rate :model-value="character.importance" disabled size="small" /></span></div>
      <!-- O6 G2 全生命周期阶段机：年龄 / 发育阶段 / 认知 LOD（数据源 /api/debug/growth/overview；旧 jar 无端点显示 —） -->
      <div class="ov-item"><span class="k">年龄</span><span class="v">
        <!-- 年龄缺失时不能渲染成「岁」（实测出现「年龄  岁」空值+量词）；缺失一律显示 — -->
        <template v-if="hasAge">{{ growth.age }} 岁</template>
        <template v-else>—</template>
      </span></div>
      <div class="ov-item"><span class="k">发育阶段</span><span class="v">
        <template v-if="growth">
          {{ growth.stageLabel || growth.stage || '—' }}
          <el-tag v-if="growth.stage === 'DECEASED'" size="small" type="danger" effect="dark">已故</el-tag>
          <el-tag v-else-if="growth.stage === 'INFANT'" size="small" type="warning" effect="plain">婴幼儿期</el-tag>
        </template>
        <template v-else>—</template>
      </span></div>
      <div class="ov-item"><span class="k">思考档位</span><span class="v">
        <template v-if="growth">
          <el-tag size="small" :type="tierTag(growth.tier)" effect="plain">{{ tierLabel(growth.tier) }}</el-tag>
        </template>
        <template v-else>—</template>
      </span></div>
      <div class="ov-item"><span class="k">角色档案</span><span class="v">
        <el-tag v-if="character.hasCard" size="small" type="success">已生成</el-tag>
        <el-tag v-else size="small" type="info">未生成</el-tag>
      </span></div>
    </div>
    <div class="ov-detail">
      <div class="ov-detail-label">
        详细信息
        <el-tooltip
          placement="top"
          content="角色创建时填写的原始描述（自由文本）。结构化的 11 维档案在「角色档案（11 维）」标签页。"
        >
          <span class="ov-detail-info">ⓘ</span>
        </el-tooltip>
      </div>
      <!-- V2.0：长文不再通栏铺开——① 约束到 CJK 40 字/行的阅读列（WCAG 1.4.8）；
            ② 默认折叠为 6 行，需要时展开；③ 给出通往结构化 11 维档案的直达入口。 -->
      <div class="ov-detail-text prose-measure" :class="{ 'is-clamped': isDetailLong && !detailExpanded }">{{ character.detail || '—' }}</div>
      <div class="ov-detail-ops">
        <button
          v-if="isDetailLong"
          type="button"
          class="ov-detail-btn"
          @click="detailExpanded = !detailExpanded"
        >{{ detailExpanded ? '收起' : '展开全文' }}</button>
        <button
          v-if="character.hasCard"
          type="button"
          class="ov-detail-btn is-link"
          @click="emit('open-card')"
        >查看 11 维档案 →</button>
      </div>
    </div>
    <el-button size="small" text type="primary" @click="emit('edit')">编辑档案</el-button>
  </template>
</template>

<script setup>
/**
 * 角色概览面板（V2.0 §13.4 巨型组件拆分第一步，纯搬迁）。
 *
 * @see docs/设计文档/HolzynActor_界面布局与IA人性化重构_方案_V2.0.md §13.4
 */
import { computed, ref, watch } from 'vue'
import EmptyState from '@/shared/components/EmptyState.vue'

const props = defineProps({
  /** 当前选中角色（null = 未选中，显示引导空态） */
  character: { type: Object, default: null },
  /** 该角色的发育条目（/api/debug/growth/overview；无数据 = null，各字段显示 —） */
  growth: { type: Object, default: null }
})

const emit = defineEmits(['edit', 'open-card'])

/** 「详细信息」是否已展开（默认折叠） */
const detailExpanded = ref(false)
/** 描述超过 220 字才给展开按钮（短文本折叠无意义） */
const isDetailLong = computed(() => String(props.character?.detail || '').length > 220)

/** 年龄可显示（0 岁是合法值，只排除 null/undefined） */
const hasAge = computed(() => props.growth && props.growth.age !== null && props.growth.age !== undefined)

// 切换角色时回到折叠态：原本由父组件 selectChar()（视图里）代管，属跨组件状态耦合，
// 拆分时收回面板自己处理——上一个角色的「展开」不该带到下一个角色。
watch(() => props.character?.id, () => { detailExpanded.value = false })

/** 认知 LOD 档位中文名（V2.0 §8.1：去开发者语言——「AI 实时」这类内部术语不再上屏，
    改为用户能懂的结果语言；内部术语保留在 tooltip 与代码注释里） */
function tierLabel(t) {
  return ({ dormant: '休眠（不消耗 AI）', ambient: '偶尔思考', focus: '实时深度思考' })[t] || t || '—'
}

/** 认知 LOD 档位标签色 */
function tierTag(t) {
  return ({ dormant: 'info', ambient: 'warning', focus: 'success' })[t] || 'info'
}
</script>

<style scoped>
.main-empty { border: none; box-shadow: none; display: flex; align-items: center; justify-content: center; height: 100%; }

/* 概览：结构化键值栅格 */
.ov-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 10px 20px; margin-bottom: 16px; }
.ov-item { display: flex; gap: 8px; align-items: center; }
.ov-item .k { color: var(--text-secondary); font-size: 0.8rem; width: 64px; flex-shrink: 0; }
.ov-item .v { color: var(--text-primary); font-size: 0.85rem; }
.ov-detail { margin-bottom: 8px; }
.ov-detail-label { font-size: 0.8rem; color: var(--text-secondary); margin-bottom: 6px; }
.ov-detail-info { font-size: 0.72rem; color: var(--text-tertiary); cursor: help; }
.ov-detail-text { font-size: 0.88rem; color: var(--text-regular); line-height: 1.8; white-space: pre-wrap; word-break: break-word; background: var(--bg-layer-2); border-radius: 8px; padding: 12px; }
/* V2.0：折叠态（默认）——超长 detail 不再把概览页撑成一片文字墙。
   注意：这里刻意不用 display:-webkit-box + -webkit-line-clamp。文本是「保留换行的自由文本」
   （white-space: pre-wrap），Chrome 在该组合下会把第 7 行渲染出来再硬裁，出现半截字的断行
   （实测截图确认）。改为按「行高 × 行数」精确限高：第 7 行起点正好落在容器下边界外，
   被完整裁掉，不出现半行字形。1.584rem = 0.88rem(字号) × 1.8(line-height)。 */
.ov-detail-text.is-clamped {
  max-height: calc(1.584rem * 6 + 24px);   /* 6 行正文 + 上下各 12px 内边距 */
  overflow: hidden;
}
.ov-detail-ops { display: flex; gap: 12px; margin-top: 4px; }
.ov-detail-btn {
  border: none; background: transparent; cursor: pointer; padding: 0;
  color: var(--text-tertiary); font-size: 0.74rem;
}
.ov-detail-btn:hover { color: var(--accent-text); text-decoration: underline; }
.ov-detail-btn.is-link { color: var(--accent-text); }
</style>