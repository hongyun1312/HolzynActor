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
  <!-- data-tour：新手引导「对谈：右侧对谈详情」的聚光灯目标。
       窄屏（<1024）时本面板收进覆盖式抽屉、默认不可见，同名的属性也打在
       DialogueView 顶部那个「对谈详情」切换按钮上——引导按 DOM 顺序取第一个可见元素。 -->
  <aside class="dialogue-context" data-tour="dlg-context">
    <!-- ============ 空态：**一行提示**，不再是第二张空态卡 + 第二个主 CTA ============
         V2.0（§5.6 空态规范）：① 侧栏/上下文面板不得放独立空态卡；
         ② 同一屏不得出现两次同一个 CTA——本面板原有一个「+ 新建对谈」按钮，
            而中栏空态已有主 CTA「新建对谈」、左栏标题栏也有「+ 新建」，同屏共三处；
            现只保留中栏那一个主 CTA，本面板退化为一行说明（讲"选中后会看到什么"）。 -->
    <section v-if="!session" class="dctx-guide">
      <div class="dctx-guide-title">还没有选中对谈</div>
      <p class="dctx-guide-desc">
        选中后这里会显示角色的<b>档案</b>、<b>你与他的关系</b>，以及会注入对话的<b>世界书</b>与<b>作者注释</b>。
      </p>
    </section>

    <template v-else>
      <!-- ============ 冻结时刻（核心语义：世界静止） ============ -->
      <section class="dctx-card" :class="{ 'is-collapsed': !open.frozen }">
        <div
          class="dctx-head" role="button" tabindex="0" :aria-expanded="open.frozen"
          @click="toggle('frozen')" @keydown.enter.prevent="toggle('frozen')" @keydown.space.prevent="toggle('frozen')"
        >
          <el-icon class="dctx-head-icon" :size="14"><HIcon name="AlarmClock" /></el-icon>
          <span class="dctx-head-title">冻结时刻</span>
          <span class="dctx-head-badges">
            <el-tag size="small" type="warning" effect="plain">世界已静止</el-tag>
          </span>
          <span class="dctx-chev" aria-hidden="true"></span>
        </div>
        <div v-show="open.frozen" class="dctx-body">
          <div class="dctx-time">{{ session?.frozenTimeText || '（无冻结快照 · 实时读取）' }}</div>
          <p class="dctx-hint">对谈期间世界零推进；离开后保持暂停，可随时手动恢复。</p>
          <el-button
            size="small" plain type="primary" class="dctx-resume" :loading="resuming"
            title="恢复后世界继续推进；本次对谈的冻结快照不变" @click="$emit('resume-world')"
          >
            <el-icon :size="13"><HIcon name="VideoPlay" /></el-icon>&nbsp;恢复世界（手动）
          </el-button>
        </div>
        <div v-show="!open.frozen" class="dctx-sub-inline">{{ session?.frozenTimeText || '（实时读取）' }}</div>
      </section>

      <!-- ============ 与我的关系（对谈时最常看：默认展开） ============ -->
      <section class="dctx-card" :class="{ 'is-collapsed': !open.relation }">
        <div
          class="dctx-head" role="button" tabindex="0" :aria-expanded="open.relation"
          @click="toggle('relation')" @keydown.enter.prevent="toggle('relation')" @keydown.space.prevent="toggle('relation')"
        >
          <el-icon class="dctx-head-icon" :size="14"><HIcon name="Connection" /></el-icon>
          <span class="dctx-head-title">与我的关系</span>
          <span class="dctx-head-badges">
            <span v-if="relLoading" class="dctx-dim">加载中…</span>
            <template v-else>
              <span class="dctx-stat"><i>好感</i><b>{{ row.affinity ?? 0 }}</b></span>
              <span class="dctx-stat"><i>信任</i><b>{{ row.trust ?? 0 }}</b></span>
              <span class="dctx-chip">{{ row.stage || '陌生' }}</span>
            </template>
          </span>
          <span class="dctx-head-ops" @click.stop>
            <el-button v-if="session?.characterRef" size="small" text type="primary" @click="openRelEdit">编辑</el-button>
          </span>
          <span class="dctx-chev" aria-hidden="true"></span>
        </div>
        <div v-show="open.relation" class="dctx-body">
          <template v-if="!session?.characterRef">
            <div class="dctx-dim">群聊会话没有单角色关系；切换单聊会话后可查看与编辑。</div>
          </template>
          <template v-else>
            <p class="dctx-hint">关系设定会作为「你与他的过往/身份/态度」注入对话，影响角色对你的称呼与分寸。</p>
            <div v-if="settingText" class="dctx-quote">{{ settingText }}</div>
            <div v-if="row.note" class="dctx-dim">备注：{{ row.note }}</div>
            <div v-if="row.stateSummary" class="dctx-dim">近况：{{ row.stateSummary }}</div>
            <div
              v-if="!settingText && !row.note && !row.stateSummary && (row.affinity ?? 0) === 0 && (row.trust ?? 0) === 0"
              class="dctx-dim"
            >
              暂无关系设定（缺省按「陌生 / 0 / 0」注入）。点上方「编辑」配置与{{ session.characterName || '角色' }}的关系。
            </div>
            <div class="dctx-rel-link" @click="goRelSettings">在设置中编辑完整档案 →</div>
          </template>
        </div>
      </section>

      <!-- ============ 角色卡（摘要 + 宽弹窗看全量 11 维：不再把 340px 栏撑到数千像素） ============ -->
      <section class="dctx-card" :class="{ 'is-collapsed': !open.card }">
        <div
          class="dctx-head" role="button" tabindex="0" :aria-expanded="open.card"
          @click="toggle('card')" @keydown.enter.prevent="toggle('card')" @keydown.space.prevent="toggle('card')"
        >
          <el-icon class="dctx-head-icon" :size="14"><HIcon name="User" /></el-icon>
          <span class="dctx-head-title">角色卡</span>
          <span class="dctx-head-badges">
            <span class="dctx-chip">{{ session?.characterName || '群聊' }}</span>
            <span v-if="cardMeta.version != null" class="dctx-chip-plain">v{{ cardMeta.version }}</span>
          </span>
          <span class="dctx-chev" aria-hidden="true"></span>
        </div>
        <div v-show="open.card" class="dctx-body">
          <div v-if="!session?.characterRef" class="dctx-dim">群聊会话没有单一角色档案；切到单聊会话即可查看该角色的 11 维档案。</div>
          <div v-else-if="!card" class="dctx-dim">该角色尚未生成角色档案（11 维），可到「角色」页生成。</div>
          <template v-else>
            <p class="dctx-hint">角色的 11 维设定，对谈时作为人格与说话方式来源注入。</p>
            <div v-for="(r, i) in cardSummary" :key="i" class="dctx-kv">
              <span class="dctx-kv-k">{{ r.label }}</span>
              <span class="dctx-kv-v">{{ r.value }}</span>
            </div>
            <div v-if="!cardSummary.length" class="dctx-dim">档案文本缺失，可打开完整档案查看原始内容。</div>
            <el-button size="small" plain class="dctx-wide-btn" @click="cardDialog.visible = true">
              <el-icon><HIcon name="Reading" /></el-icon>&nbsp;查看完整档案（11 维）
            </el-button>
          </template>
        </div>
      </section>

      <!-- ============ 世界书（默认折叠；折叠态用徽标交代条数） ============ -->
      <section class="dctx-card" :class="{ 'is-collapsed': !open.worldbook }">
        <div
          class="dctx-head" role="button" tabindex="0" :aria-expanded="open.worldbook"
          @click="toggle('worldbook')" @keydown.enter.prevent="toggle('worldbook')" @keydown.space.prevent="toggle('worldbook')"
        >
          <el-icon class="dctx-head-icon" :size="14"><HIcon name="Collection" /></el-icon>
          <span class="dctx-head-title">世界书</span>
          <span class="dctx-head-badges">
            <span v-if="wbLoading" class="dctx-dim">加载中…</span>
            <span v-else class="dctx-chip-plain">{{ worldbook.length }} 条</span>
          </span>
          <span class="dctx-head-ops" @click.stop>
            <el-button size="small" text type="primary" @click="openWbEdit(null)">+ 添加</el-button>
          </span>
          <span class="dctx-chev" aria-hidden="true"></span>
        </div>
        <div v-show="open.worldbook" class="dctx-body">
          <p class="dctx-hint">关键词命中最近消息时，自动把对应片段注入角色的背景知识（让角色「记得」这些设定）。</p>
          <div v-if="!worldbook.length" class="dctx-dim">
            暂无条目。例如关键词「灵脉」+ 片段「灵脉是青禾村的灵气源头」，聊到灵脉时角色就会知道这件事。
          </div>
          <div v-for="w in worldbook" :key="w.id" class="dctx-wb-item">
            <div class="dctx-wb-head">
              <el-tag size="small" :type="w.enabled ? 'primary' : 'info'" effect="plain">{{ w.keyword }}</el-tag>
              <span class="dctx-wb-pri">#{{ w.priority ?? 0 }}</span>
              <span class="dctx-wb-ops">
                <el-switch size="small" :model-value="!!w.enabled" @change="v => toggleWb(w, v)" />
                <el-button size="small" text type="primary" @click="openWbEdit(w)">编辑</el-button>
                <el-button size="small" text type="danger" @click="delWb(w)">删除</el-button>
              </span>
            </div>
            <div class="dctx-wb-content">{{ w.content || '（空片段）' }}</div>
          </div>
        </div>
      </section>

      <!-- ============ 作者注释 / 超窗摘要（默认折叠） ============ -->
      <section class="dctx-card" :class="{ 'is-collapsed': !open.note }">
        <div
          class="dctx-head" role="button" tabindex="0" :aria-expanded="open.note"
          @click="toggle('note')" @keydown.enter.prevent="toggle('note')" @keydown.space.prevent="toggle('note')"
        >
          <el-icon class="dctx-head-icon" :size="14"><HIcon name="EditPen" /></el-icon>
          <span class="dctx-head-title">作者注释</span>
          <span class="dctx-head-badges">
            <span class="dctx-chip-plain">{{ cfg?.authorNote ? '已设置' : '未设置' }}</span>
          </span>
          <span class="dctx-head-ops" @click.stop>
            <el-button size="small" text type="primary" @click="openNoteEdit">编辑</el-button>
          </span>
          <span class="dctx-chev" aria-hidden="true"></span>
        </div>
        <div v-show="open.note" class="dctx-body">
          <p class="dctx-hint">一段常驻提示，按你设定的位置与频率注入对话，用来引导氛围或剧情走向。</p>
          <div v-if="cfg?.authorNote" class="dctx-quote">{{ cfg.authorNote }}</div>
          <div v-else class="dctx-dim">未设置。例如「本次对话氛围偏轻松，多用闲谈口吻」。</div>
          <div v-if="cfg?.authorNote" class="dctx-note-meta">
            位置：{{ depthLabel(cfg.authorNoteDepth) }} · 频率：每 {{ cfg.authorNoteFrequency }} 条
          </div>
          <div v-if="cfg?.summary" class="dctx-note-meta">
            超窗摘要（水位 {{ cfg.summaryMessageCount }} / 窗口 {{ cfg.historyWindow }}）：{{ cfg.summary.length > 60 ? cfg.summary.slice(0, 60) + '…' : cfg.summary }}
          </div>
        </div>
      </section>

      <!-- ============ 上下文用量（默认折叠；折叠态给出「条数 · token」一行） ============ -->
      <section class="dctx-card" :class="{ 'is-collapsed': !open.usage }">
        <div
          class="dctx-head" role="button" tabindex="0" :aria-expanded="open.usage"
          @click="toggle('usage')" @keydown.enter.prevent="toggle('usage')" @keydown.space.prevent="toggle('usage')"
        >
          <el-icon class="dctx-head-icon" :size="14"><HIcon name="DataAnalysis" /></el-icon>
          <span class="dctx-head-title">上下文用量</span>
          <span class="dctx-head-badges">
            <span class="dctx-chip-plain">{{ usageLine }}</span>
          </span>
          <span class="dctx-chev" aria-hidden="true"></span>
        </div>
        <div v-show="open.usage" class="dctx-body">
          <p class="dctx-hint">估算本轮发给模型的上下文规模（含摘要、世界书、作者注释）。</p>
          <div class="dctx-usage">
            <div class="dctx-usage-row"><span>会话消息</span><b>{{ usage?.messageCount ?? props.messagesCount }}</b> 条</div>
            <div class="dctx-usage-row"><span>正文字符</span><b>{{ usage?.contextChars ?? props.contextChars }}</b> 字</div>
            <div class="dctx-usage-row"><span>估算 token</span><b>≈ {{ usage?.estimateTokens ?? estimateTokens }}</b></div>
            <div v-if="(usage?.summaryChars ?? 0) > 0" class="dctx-usage-row"><span>其中摘要</span><b>{{ usage.summaryChars }}</b> 字</div>
            <div v-if="(usage?.worldbookChars ?? 0) > 0" class="dctx-usage-row"><span>其中世界书</span><b>{{ usage.worldbookChars }}</b> 字</div>
            <div v-if="(usage?.authorNoteChars ?? 0) > 0" class="dctx-usage-row"><span>其中作者注释</span><b>{{ usage.authorNoteChars }}</b> 字</div>
          </div>
          <div class="dctx-dim">估算口径：CJK≈0.55 token/字 + ASCII≈0.28 + 单词开销；真实用量见用量日志（dialogue_chat）。</div>
        </div>
      </section>
    </template>

    <!-- 完整角色档案（11 维）宽弹窗：340px 窄栏读不动 11 维，移到宽容器里看 -->
    <el-dialog
      v-model="cardDialog.visible"
      :title="`角色档案 · ${session?.characterName || '角色'}`"
      width="min(960px, 94vw)"
      top="6vh"
    >
      <div class="dctx-archive-body">
        <PersonaArchivePanel v-if="card" :card="card" :character-name="session?.characterName" />
        <div v-else class="dctx-dim">角色档案未生成或加载失败</div>
      </div>
    </el-dialog>

    <!-- 关系编辑对话框（M3） -->
    <el-dialog v-model="relEdit.visible" :title="`与「${session?.characterName || '角色'}」的关系 · 编辑`" width="540px">
      <el-form label-position="top">
        <el-form-item label="过往（你们之间发生过什么）">
          <el-input v-model="relEdit.past" type="textarea" :rows="2" maxlength="300" placeholder="例如：幼时在青禾村相识" />
        </el-form-item>
        <el-form-item label="身份关系（你们是什么关系）">
          <el-input v-model="relEdit.identity" type="textarea" :rows="2" maxlength="200" placeholder="例如：青梅竹马 / 旅伴" />
        </el-form-item>
        <el-form-item label="他对你的态度（基线）">
          <el-input v-model="relEdit.attitude" type="textarea" :rows="2" maxlength="200" placeholder="例如：亲近而克制，愿意为你破例" />
        </el-form-item>
        <div class="dctx-rel-edit-row">
          <el-form-item label="好感">
            <el-slider v-model="relEdit.affinity" :min="0" :max="100" show-input :show-input-controls="false" size="small" />
          </el-form-item>
          <el-form-item label="信任">
            <el-slider v-model="relEdit.trust" :min="0" :max="100" show-input :show-input-controls="false" size="small" />
          </el-form-item>
        </div>
        <div class="dctx-rel-edit-row">
          <el-form-item label="关系阶段">
            <el-select v-model="relEdit.stage" style="width: 150px">
              <el-option v-for="s in STAGES" :key="s" :label="s" :value="s" />
            </el-select>
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="relEdit.note" maxlength="200" clearable placeholder="选填" />
          </el-form-item>
        </div>
      </el-form>
      <div class="dctx-rel-edit-tip">设定注入对谈上下文的关系段；好感/信任/阶段为基线，沉淀（M6）前由你设定。</div>
      <template #footer>
        <el-button @click="relEdit.visible = false">取消</el-button>
        <el-button type="primary" :loading="relSaving" @click="saveRelEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 世界书条目编辑对话框（M4） -->
    <el-dialog v-model="wbEdit.visible" :title="wbEdit.id ? '编辑世界书条目' : '添加世界书条目'" width="520px">
      <el-form label-position="top">
        <el-form-item label="触发关键词（命中最近消息正文即注入）" required>
          <el-input v-model="wbEdit.keyword" maxlength="100" placeholder="例如：灵脉 / 云来茶馆 / 掌门" />
        </el-form-item>
        <el-form-item label="知识片段（注入内容，标注〔来源〕）">
          <el-input v-model="wbEdit.content" type="textarea" :rows="4" placeholder="例如：灵脉是青禾村的灵气源头，靠近它修行事半功倍" />
        </el-form-item>
        <div class="dctx-rel-edit-row">
          <el-form-item label="启用">
            <el-switch v-model="wbEdit.enabled" />
          </el-form-item>
          <el-form-item label="优先级（同轮多条命中时升序注入）">
            <el-input-number v-model="wbEdit.priority" :min="0" :max="99" size="small" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="wbEdit.visible = false">取消</el-button>
        <el-button type="primary" :loading="wbSaving" :disabled="!wbEdit.keyword.trim()" @click="saveWbEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 作者注释编辑对话框（M4） -->
    <el-dialog v-model="noteEdit.visible" :title="'作者注释 · 编辑'" width="540px">
      <el-form label-position="top">
        <el-form-item label="常驻提示（留空=清除，不再注入）">
          <el-input v-model="noteEdit.authorNote" type="textarea" :rows="3" maxlength="1000" placeholder="例如：本次对话氛围偏轻松，多用闲谈口吻" />
        </el-form-item>
        <div class="dctx-rel-edit-row">
          <el-form-item label="位置（深度）">
            <el-select v-model="noteEdit.authorNoteDepth" style="width: 100%">
              <el-option :value="0" label="0 · system 最前" />
              <el-option :value="1" label="1 · system 末尾（默认）" />
              <el-option :value="2" label="2 · 动态段首" />
            </el-select>
          </el-form-item>
          <el-form-item label="频率（每 N 条消息注入）">
            <el-input-number v-model="noteEdit.authorNoteFrequency" :min="1" :max="99" size="small" />
          </el-form-item>
        </div>
      </el-form>
      <div class="dctx-rel-edit-tip">位置/频率对齐 SillyTavern Author's Note：深度控制注入在上下文的位置，频率控制每隔 N 条消息注入一次。</div>
      <template #footer>
        <el-button @click="noteEdit.visible = false">取消</el-button>
        <el-button type="primary" :loading="noteSaving" @click="saveNoteEdit">保存</el-button>
      </template>
    </el-dialog>
  </aside>
</template>

<script setup>
/**
 * 对谈上下文面板（右栏）。
 * <p>职责：冻结时刻（世界静止提示 + 手动恢复）、「与我的关系」（M3：关系设定 + 关系行 + 内联编辑）、
 * 角色卡（摘要 + 宽弹窗看全量 11 维）、<b>世界书（M4：会话级关键词触发条目
 * CRUD + 启用开关）</b>、<b>作者注释（M4：常驻提示 + 位置/频率配置）与超窗摘要水位</b>、
 * <b>上下文用量（M4：后端估算含摘要/世界书/作者注释段）</b>。</p>
 *
 * <p><b>P1-1 折叠重构</b>：6 张卡常驻且默认全展开 → 世界书/作者注释条数一多，右栏远超一屏，
 * 且角色卡的完整 11 维档案在 340px 宽度内实测高度 4995px（把右栏变成一条无尽滚动条）。
 * 现改为：① 每张卡可折叠（表头整行可点，键盘可达，状态持久化到 localStorage）；
 * ② 默认只展开「冻结时刻 + 与我的关系」两张；③ 折叠态不是"空白"——表头副信息行/徽标
 * 仍交代该卡的关键状态（时间、好感/信任/阶段、档案版本、条目数、用量）；
 * ④ 角色卡改为「摘要 + 查看完整档案（11 维）宽弹窗」，11 维不再挤在 340px 窄栏里；
 * ⑤ 未选会话时只显示一张引导卡（原来 6 张空卡）；⑥ 世界书/作者注释/用量补一句人话解释，
 * 不再是酒馆黑话。</p>
 *
 * <p>数据：/api/player-profile + /api/worlds/{id}/player-profile/relations（M3）；
 * /api/worlds/{id}/dialogue/sessions/{sid}/worldbook|context-config|usage（M4）。</p>
 */
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchPlayerProfile, savePlayerRelationSetting, fetchPlayerRelations, savePlayerRelation,
  fetchDialogueWorldbook, createDialogueWorldbook, updateDialogueWorldbook, deleteDialogueWorldbook,
  fetchDialogueContextConfig, saveDialogueAuthorNote, fetchDialogueUsage
} from '@/shared/api'
import PersonaArchivePanel from '@/features/character/components/PersonaArchivePanel.vue'
import { buildArchive, splitDimLine } from '@/features/character/personaArchive'

const props = defineProps({
  /** 当前会话（DialogueSessionVO） */
  session: { type: Object, default: null },
  /** 角色卡（fetchCharacterCard 结果） */
  card: { type: Object, default: null },
  /** 会话消息数 */
  messagesCount: { type: Number, default: 0 },
  /** 全部正文累计字符数 */
  contextChars: { type: Number, default: 0 },
  /** 恢复世界请求中 */
  resuming: { type: Boolean, default: false }
})
defineEmits(['resume-world', 'create'])

const route = useRoute()
const router = useRouter()
const projectId = Number(route.params.id)

const STAGES = ['陌生', '相识', '相熟', '熟络', '亲密', '知己']
const DEPTH_LABELS = { 0: 'system 最前', 1: 'system 末尾', 2: '动态段首' }

// ==================== 折叠状态（P1-1） ====================
/** 折叠偏好持久化键（用户展开过哪些卡，下次进对谈保持一致） */
const OPEN_KEY = 'holzyn-actor.dialogue.context.open.v1'
/** 默认展开：冻结时刻（世界静止语义）+ 与我的关系（对谈时最常看） */
const DEFAULT_OPEN = { frozen: true, relation: true, card: false, worldbook: false, note: false, usage: false }

function loadOpen() {
  try {
    const raw = localStorage.getItem(OPEN_KEY)
    if (raw) return { ...DEFAULT_OPEN, ...JSON.parse(raw) }
  } catch (_) { /* 损坏数据按默认 */ }
  return { ...DEFAULT_OPEN }
}

const open = reactive(loadOpen())

/** 切换某张卡的展开态并持久化 */
function toggle(key) {
  open[key] = !open[key]
  try { localStorage.setItem(OPEN_KEY, JSON.stringify(open)) } catch (_) { /* 忽略 */ }
}

const cardDialog = reactive({ visible: false })

// ==================== 关系（M3） ====================
const relLoading = ref(false)
const relSaving = ref(false)
const settingText = ref('')
const row = reactive({ affinity: 0, trust: 0, stage: '陌生', note: '', stateSummary: '' })
const relEdit = reactive({ visible: false, past: '', identity: '', attitude: '', affinity: 0, trust: 0, stage: '陌生', note: '' })

// ==================== M4：世界书 / 作者注释 / 用量 ====================
const worldbook = ref([])
const wbLoading = ref(false)
const wbSaving = ref(false)
const cfg = ref(null)
const noteSaving = ref(false)
const usage = ref(null)
const wbEdit = reactive({ visible: false, id: null, keyword: '', content: '', enabled: true, priority: 0 })
const noteEdit = reactive({ visible: false, authorNote: '', authorNoteDepth: 1, authorNoteFrequency: 1 })

/** token 估算（CJK 约 1 token/字；ASCII 折半，v1 简化——M4 起以 /usage 后端估算为准） */
const estimateTokens = computed(() => {
  if (!props.contextChars) return 0
  return Math.max(1, Math.round(props.contextChars / 1.2))
})

/** 折叠态用量一行摘要（P1-1：不展开也能看到规模） */
const usageLine = computed(() => {
  const n = usage.value?.messageCount ?? props.messagesCount
  const t = usage.value?.estimateTokens ?? estimateTokens.value
  return `${n} 条 · ≈${t} token`
})

/** 角色卡档案结构（P1-1：复用 personaArchive 的切分结果做摘要，不重复实现渲染规则） */
const cardArchive = computed(() => (props.card ? buildArchive(props.card) : null))

/** 角色卡表头徽标（档案版本） */
const cardMeta = computed(() => cardArchive.value?.meta || {})

/** 摘要偏好字段（按 ① 身份与社会 的标签挑几条最像"名片"的；缺则顺次补足） */
const SUMMARY_LABELS = ['姓名', '性别', '出生', '阶层', '谋生', '声望']
/** 角色卡摘要行（最多 4 行，超出由 CSS 截断，长文本不再无限撑高右栏） */
const cardSummary = computed(() => {
  const identity = cardArchive.value?.dims?.[0]
  if (!identity || !identity.hasContent) return []
  const rows = identity.lines.map(splitDimLine).filter(r => r.label && r.value)
  const picked = []
  for (const label of SUMMARY_LABELS) {
    const hit = rows.find(r => r.label === label)
    if (hit) picked.push(hit)
    if (picked.length >= 4) break
  }
  if (!picked.length) picked.push(...rows.slice(0, 4))
  return picked.slice(0, 4)
})

/** 当前角色引用 */
function characterRef() {
  return props.session?.characterRef || null
}

/** 当前会话 ID（null=未选择） */
function sessionId() {
  return props.session?.id || null
}

/** 加载当前角色的关系设定 + 关系行 */
async function loadRelation() {
  const ref = characterRef()
  if (!ref) {
    settingText.value = ''
    Object.assign(row, { affinity: 0, trust: 0, stage: '陌生', note: '', stateSummary: '' })
    return
  }
  relLoading.value = true
  try {
    let settings = {}
    try {
      const p = await fetchPlayerProfile()
      settings = p?.relationSettings?.[ref] || {}
    } catch (_) { /* 档案读取失败按空处理 */ }
    settingText.value = [settings.past && `过往：${settings.past}`, settings.identity && `身份关系：${settings.identity}`, settings.attitude && `态度：${settings.attitude}`]
      .filter(Boolean)
      .join('；')
    let r = null
    try {
      const rows = await fetchPlayerRelations(projectId)
      r = rows.find(x => x.characterRef === ref) || null
    } catch (_) { /* 关系行读取失败按缺省处理 */ }
    Object.assign(row, {
      affinity: r?.affinity ?? 0,
      trust: r?.trust ?? 0,
      stage: r?.stage || '陌生',
      note: r?.note || '',
      stateSummary: r?.stateSummary || ''
    })
  } finally {
    relLoading.value = false
  }
}

/** 打开编辑：回填当前设定与关系行 */
function openRelEdit() {
  fillRelEdit()
  relEdit.visible = true
}

async function fillRelEdit() {
  const ref = characterRef()
  if (!ref) return
  try {
    const p = await fetchPlayerProfile()
    const s = p?.relationSettings?.[ref] || {}
    relEdit.past = s.past || ''
    relEdit.identity = s.identity || ''
    relEdit.attitude = s.attitude || ''
  } catch (_) {
    relEdit.past = ''
    relEdit.identity = ''
    relEdit.attitude = ''
  }
  relEdit.affinity = row.affinity ?? 0
  relEdit.trust = row.trust ?? 0
  relEdit.stage = row.stage || '陌生'
  relEdit.note = row.note || ''
}

/** 保存关系设定 + 关系行基线 */
async function saveRelEdit() {
  const ref = characterRef()
  if (!ref) return
  relSaving.value = true
  try {
    await savePlayerRelationSetting(ref, { past: relEdit.past, identity: relEdit.identity, attitude: relEdit.attitude })
    await savePlayerRelation(projectId, ref, {
      affinity: relEdit.affinity,
      trust: relEdit.trust,
      stage: relEdit.stage,
      note: relEdit.note
    })
    ElMessage.success('关系已保存，将注入后续对谈上下文')
    relEdit.visible = false
    await loadRelation()
  } catch (e) {
    ElMessage.error(e?.message || '保存失败，请稍后重试')
  } finally {
    relSaving.value = false
  }
}

/** 跳转对谈界面玩家档案页（完整编辑；P0-1 玩家档案已移入对谈独立界面） */
function goRelSettings() {
  router.push(`/world/${projectId}/dialogue/player-profile`)
}

// ==================== M4：世界书 ====================

/** 加载会话世界书条目 */
async function loadWorldbook() {
  const sid = sessionId()
  if (!sid) {
    worldbook.value = []
    return
  }
  wbLoading.value = true
  try {
    worldbook.value = await fetchDialogueWorldbook(projectId, sid)
  } catch (_) {
    worldbook.value = []
  } finally {
    wbLoading.value = false
  }
}

/** 打开条目编辑（entry=null 新建；否则回填） */
function openWbEdit(entry) {
  wbEdit.id = entry?.id ?? null
  wbEdit.keyword = entry?.keyword ?? ''
  wbEdit.content = entry?.content ?? ''
  wbEdit.enabled = entry ? !!entry.enabled : true
  wbEdit.priority = entry?.priority ?? 0
  wbEdit.visible = true
}

async function saveWbEdit() {
  const sid = sessionId()
  if (!sid || !wbEdit.keyword.trim()) return
  wbSaving.value = true
  try {
    const payload = {
      keyword: wbEdit.keyword.trim(),
      content: wbEdit.content,
      enabled: wbEdit.enabled,
      priority: wbEdit.priority ?? 0
    }
    if (wbEdit.id) {
      await updateDialogueWorldbook(projectId, sid, wbEdit.id, payload)
    } else {
      await createDialogueWorldbook(projectId, sid, payload)
    }
    ElMessage.success(wbEdit.id ? '世界书条目已更新' : '世界书条目已添加')
    wbEdit.visible = false
    await loadWorldbook()
  } catch (e) {
    ElMessage.error(e?.message || '保存失败，请稍后重试')
  } finally {
    wbSaving.value = false
  }
}

/** 启用/停用（开关切换即更新） */
async function toggleWb(w, v) {
  const sid = sessionId()
  if (!sid) return
  const prev = w.enabled
  w.enabled = v
  try {
    await updateDialogueWorldbook(projectId, sid, w.id, {
      keyword: w.keyword,
      content: w.content,
      enabled: v,
      priority: w.priority ?? 0
    })
  } catch (e) {
    w.enabled = prev
    ElMessage.error(e?.message || '切换失败')
  }
}

async function delWb(w) {
  const sid = sessionId()
  if (!sid) return
  try {
    await ElMessageBox.confirm(`删除世界书条目「${w.keyword}」？`, '删除世界书条目', { type: 'warning' })
  } catch (_) { return }
  try {
    await deleteDialogueWorldbook(projectId, sid, w.id)
    ElMessage.success('已删除')
    await loadWorldbook()
  } catch (e) {
    ElMessage.error(e?.message || '删除失败')
  }
}

// ==================== M4：作者注释 / 上下文配置 ====================

/** 加载上下文配置（作者注释 + 摘要水位） */
async function loadContextConfig() {
  const sid = sessionId()
  if (!sid) {
    cfg.value = null
    return
  }
  try {
    cfg.value = await fetchDialogueContextConfig(projectId, sid)
  } catch (_) {
    cfg.value = null
  }
}

function openNoteEdit() {
  noteEdit.authorNote = cfg.value?.authorNote ?? ''
  noteEdit.authorNoteDepth = cfg.value?.authorNoteDepth ?? 1
  noteEdit.authorNoteFrequency = cfg.value?.authorNoteFrequency ?? 1
  noteEdit.visible = true
}

async function saveNoteEdit() {
  const sid = sessionId()
  if (!sid) return
  noteSaving.value = true
  try {
    cfg.value = await saveDialogueAuthorNote(projectId, sid, {
      authorNote: noteEdit.authorNote,
      authorNoteDepth: noteEdit.authorNoteDepth,
      authorNoteFrequency: noteEdit.authorNoteFrequency
    })
    ElMessage.success('作者注释已保存，将按位置/频率注入上下文')
    noteEdit.visible = false
  } catch (e) {
    ElMessage.error(e?.message || '保存失败，请稍后重试')
  } finally {
    noteSaving.value = false
  }
}

function depthLabel(d) {
  return DEPTH_LABELS[d] || 'system 末尾'
}

// ==================== M4：上下文用量 ====================

/** 加载后端用量估算（含摘要/世界书/作者注释段） */
async function loadUsage() {
  const sid = sessionId()
  if (!sid) {
    usage.value = null
    return
  }
  try {
    usage.value = await fetchDialogueUsage(projectId, sid)
  } catch (_) {
    usage.value = null
  }
}

/** 会话切换 → 全量重载 */
function reloadAll() {
  loadRelation()
  loadWorldbook()
  loadContextConfig()
  loadUsage()
}

watch(() => props.session?.id, () => {
  cardDialog.visible = false
  reloadAll()
})
// 消息数变化 → 刷新用量（会话切换时 loadUsage 也会跑，重复请求无害）
watch(() => props.messagesCount, () => loadUsage())
onMounted(() => reloadAll())
</script>

<style scoped>
.dialogue-context {
  width: var(--page-info-w, 340px);
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-lg);
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
  overflow-y: auto;
  flex-shrink: 0;
}

/* ===== 空态引导（P1-1：未选会话只显示这一张） ===== */
/* V2.0：空态退化为「一行说明」——不再是居中大卡（同屏只允许中栏那一个主体空态） */
.dctx-guide {
  margin: auto 0;
  padding: 16px 14px;
  display: flex; flex-direction: column; align-items: flex-start; text-align: left; gap: 6px;
  border-left: 2px solid var(--border-l2);
}
/* 图标已不再使用（保留规则以免历史引用报错，同时避免被误认为仍在渲染） */
.dctx-guide-icon {
  width: 44px; height: 44px; border-radius: 14px;
  display: flex; align-items: center; justify-content: center;
  background: var(--accent-soft); color: var(--accent-text);
}
.dctx-guide-title { font-weight: 600; font-size: 0.82rem; color: var(--text-tertiary); }
/* 长文/说明统一约束阅读宽度（CJK 40 字/行） */
.dctx-guide-desc { font-size: 0.76rem; line-height: 1.75; color: var(--text-secondary); margin: 0; max-width: var(--prose-measure, 40em); }
.dctx-guide-desc b { color: var(--text-regular); font-weight: 600; }

/* ===== 折叠卡 ===== */
.dctx-card {
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-md);
  background: var(--bg-layer-1);
  overflow: hidden;
  flex-shrink: 0;
  transition: border-color .18s var(--dsw-ease), background-color .18s var(--dsw-ease);
}
.dctx-card:hover { border-color: var(--border-l2); }
.dctx-card.is-collapsed { background: var(--bg-layer-2); }

/* 表头：整行可点（键盘可达）；右侧梳齿箭头指示展开态 */
.dctx-head {
  display: flex; align-items: center; gap: 6px;
  padding: 8px 10px;
  cursor: pointer;
  user-select: none;
}
/* V2.0 a11y：自定义焦点指示必须是实心主色（--focus-ring-inset）——
   原用 --accent-border（38% 透明度主色）与相邻底色达不到 3:1 对比度。 */
.dctx-head:focus-visible { box-shadow: var(--focus-ring-inset); }
.dctx-head-icon { color: var(--text-tertiary); }
.dctx-card.is-collapsed .dctx-head-icon { color: var(--text-placeholder); }
.dctx-head-title { font-weight: 600; font-size: 0.8rem; color: var(--text-primary); white-space: nowrap; }
.dctx-head-badges { margin-left: auto; display: flex; align-items: center; gap: 6px; min-width: 0; }
.dctx-head-ops { display: flex; align-items: center; gap: 2px; }
/* 展开指示：CSS 画的双边角箭头（比图标集里的 ic_public_drawer 更精确——那个是三横线，
   旋转 -90° 后是「|||」；ic_public_arrow_right 是 12×24 视口、按方形容器渲染会缩成一半） */
.dctx-chev {
  width: 7px; height: 7px; flex-shrink: 0;
  margin: 0 4px 0 2px;
  border-right: 1.5px solid var(--text-placeholder);
  border-bottom: 1.5px solid var(--text-placeholder);
  border-radius: 1px;
  transform: rotate(45deg) translate(-1px, -1px);
  transition: transform .2s var(--dsw-ease);
}
.dctx-card.is-collapsed .dctx-chev { transform: rotate(-45deg); }

/* 折叠态的一行副信息（P1-1：折叠不等于失信息） */
.dctx-sub-inline {
  padding: 0 10px 8px 30px;
  font-size: 0.74rem; color: var(--text-secondary);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}

/* 关系徽标：好感/信任数值 + 阶段胶囊 */
.dctx-stat { display: inline-flex; align-items: baseline; gap: 3px; font-size: 0.72rem; }
.dctx-stat i { font-style: normal; color: var(--text-tertiary); }
.dctx-stat b { color: var(--text-primary); font-variant-numeric: tabular-nums; }
.dctx-chip {
  font-size: 0.7rem; color: var(--accent-text);
  background: var(--accent-soft); border-radius: 999px; padding: 1px 7px;
}
.dctx-chip-plain { font-size: 0.7rem; color: var(--text-tertiary); }

.dctx-body { padding: 0 10px 10px; }
.dctx-time { font-size: 0.84rem; color: var(--text-primary); line-height: 1.6; word-break: break-word; }
.dctx-hint { font-size: 0.72rem; color: var(--text-tertiary); line-height: 1.6; margin: 4px 0 6px; }
.dctx-resume { width: 100%; margin-top: 2px; }
.dctx-wide-btn { width: 100%; }
.dctx-dim { font-size: 0.74rem; color: var(--text-secondary); line-height: 1.7; }

/* 引文块（关系设定 / 作者注释） */
.dctx-quote {
  font-size: 0.76rem; color: var(--text-regular); line-height: 1.7;
  background: var(--bg-layer-2); border-radius: 8px; padding: 6px 10px;
  margin-bottom: 6px; word-break: break-word;
}

/* 键值行（角色卡摘要） */
.dctx-kv { display: flex; gap: 8px; font-size: 0.76rem; line-height: 1.7; margin-bottom: 2px; }
.dctx-kv-k { flex: 0 0 auto; min-width: 34px; color: var(--text-tertiary); }
.dctx-kv-v {
  flex: 1; min-width: 0; color: var(--text-regular); word-break: break-word;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}

.dctx-usage { display: flex; flex-direction: column; gap: 4px; font-size: 0.78rem; color: var(--text-secondary); margin-bottom: 6px; }
.dctx-usage-row { display: flex; justify-content: space-between; align-items: center; }
.dctx-usage-row b { color: var(--text-primary); font-variant-numeric: tabular-nums; }
.dctx-rel-link { font-size: 0.72rem; color: var(--accent-text); cursor: pointer; margin-top: 6px; }
.dctx-rel-link:hover { text-decoration: underline; }
.dctx-rel-edit-row { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
.dctx-rel-edit-tip { font-size: 0.72rem; color: var(--text-tertiary); line-height: 1.6; }
.dctx-wb-item { border: 1px solid var(--border-l1); border-radius: 8px; padding: 6px 8px; margin-bottom: 6px; background: var(--bg-layer-2); }
.dctx-wb-head { display: flex; align-items: center; gap: 6px; }
.dctx-wb-pri { font-size: 0.68rem; color: var(--text-tertiary); }
.dctx-wb-ops { margin-left: auto; display: flex; align-items: center; gap: 2px; }
.dctx-wb-content { font-size: 0.74rem; color: var(--text-secondary); line-height: 1.6; margin-top: 4px; word-break: break-word; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.dctx-note-meta { font-size: 0.72rem; color: var(--text-tertiary); line-height: 1.6; margin-top: 4px; word-break: break-word; }

/* 完整档案弹窗：11 维在宽容器里两列铺开，超高内部滚动 */
.dctx-archive-body { max-height: 70vh; overflow-y: auto; padding-right: 4px; }
</style>
