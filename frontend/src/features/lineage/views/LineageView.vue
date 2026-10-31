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
  <div class="lineage-view project-col">
    <!-- 顶部：角色选择 + 刷新 -->
    <div class="lg-toolbar">
      <span class="lg-title">家族谱系</span>
      <el-select v-model="npcRef" filterable placeholder="选择角色（本人视角）" class="lg-select" @change="loadAll">
        <el-option v-for="ch in characters" :key="ch.id" :label="charLabel(ch)" :value="refOf(ch)" />
      </el-select>
      <el-button size="small" type="primary" :disabled="!npcRef" :loading="loading" @click="loadAll">刷新</el-button>
      <span v-if="tree" class="lg-hint">
        三代家族树 · 聚焦：{{ focusName }}（{{ tree.nodes.length }} 节点 / {{ tree.relations.length }} 边）
      </span>
      <span class="lg-hint">G4 遗传婚育谱系：孟德尔遗传 · 婚姻 · 子嗣 · 遗产（只读观测）</span>
    </div>

    <!-- 加载失败要说出来：改前只 console.error，页面静默停在空态，用户只会看到「什么都没有」 -->
    <el-alert
      v-if="loadError"
      class="lg-error"
      type="error"
      show-icon
      :closable="false"
      :title="loadError"
      description="谱系数据来自 /api/debug/growth/lineage（后端 18080）。若刚升级过前端，请确认后端已重启。"
    />

    <template v-if="npcRef">
      <el-row :gutter="12">
        <!-- 家族树（代际列布局） -->
        <el-col :span="15">
          <el-card shadow="never" class="lg-card">
            <template #header>
              <div class="lg-card-head">
                <span>家族树（按代际分列：祖辈 → 父母 → 本人/兄弟姐妹 → 子女）</span>
                <el-button size="small" :disabled="!tree" @click="loadAll">刷新</el-button>
              </div>
            </template>
            <template v-if="tree && tree.nodes.length">
              <div class="lg-tree">
                <div v-for="gen in generations" :key="gen.key" class="lg-gen">
                  <div class="lg-gen-title">{{ gen.title }}</div>
                  <div class="lg-gen-cards">
                    <div
                      v-for="node in gen.nodes"
                      :key="node.ref"
                      class="lg-node"
                      :class="{ focus: node.ref === tree.focus, deceased: node.deceased }"
                      @click="switchFocus(node.ref)"
                    >
                      <div class="lg-node-name">{{ node.name }}</div>
                      <div class="lg-node-rel">
                        <el-tag size="small" :type="relTagType(node.relation)" effect="plain">{{ node.relation }}</el-tag>
                      </div>
                      <div class="lg-node-meta">
                        <el-tag size="small" :type="genderType(node.gender)" effect="plain">{{ node.gender || '—' }}</el-tag>
                        <span class="lg-node-stage">{{ stageLabel(node.stage) }}</span>
                        <span class="lg-node-age">{{ node.age ?? '?' }} 岁</span>
                      </div>
                      <div v-if="node.deceased" class="lg-node-dead">已故</div>
                    </div>
                  </div>
                  <div v-if="!gen.nodes.length" class="lg-gen-empty">（无）</div>
                </div>
              </div>
              <div class="lg-tree-hint">点击任意节点切换聚焦视角（重新加载该角色三代家族树）</div>
            </template>
            <EmptyState v-else size="inline" title="该角色暂无谱系信息（未登记婚姻/亲子边）" />
          </el-card>

          <!-- 谱系边明细 -->
          <el-card shadow="never" class="lg-card">
            <template #header>
              <div class="lg-card-head"><span>谱系边（{{ tree ? tree.relations.length : 0 }}）</span></div>
            </template>
            <el-table v-if="tree && tree.relations.length" :data="tree.relations" size="small" height="220">
              <el-table-column label="类型" width="90">
                <template #default="{ row }">
                  <el-tag size="small" :type="isMarriage(row) ? 'danger' : 'success'" effect="plain">
                    {{ isMarriage(row) ? '婚姻' : '亲子' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="关系" width="80">
                <template #default="{ row }">
                  <!-- 婚姻边的 relation 由 LineageService.marry 留空（历史边不写关系文本），
                       这里按"婚姻"补成「配偶」，不给用户看「—」 -->
                  <span class="lg-rel">{{ relLabel(row.relation, row.type) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="当事人" min-width="170">
                <template #default="{ row }">
                  <span class="lg-ref">{{ refName(row.from) }}</span>
                  <span class="lg-arrow">→</span>
                  <span class="lg-ref">{{ refName(row.to) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="游戏时刻" width="110">
                <template #default="{ row }">
                  <span class="sub-text">{{ row.gameSecond ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="存续" width="70">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.active === 1 ? 'success' : 'info'" effect="plain">
                    {{ row.active === 1 ? '存续' : '已终止' }}
                  </el-tag>
                </template>
              </el-table-column>
            </el-table>
            <EmptyState v-else size="inline" title="暂无谱系边——登记婚姻 / 亲子关系后出现" />
          </el-card>
        </el-col>

        <!-- 基因档案 -->
        <el-col :span="9">
          <el-card shadow="never" class="lg-card">
            <template #header>
              <div class="lg-card-head">
                <span>基因档案：{{ focusName }}</span>
                <el-tag v-if="genome" size="small" :type="genome.present ? 'success' : 'info'" effect="plain">
                  {{ genome.present ? '已建档' : '未建档' }}
                </el-tag>
              </div>
            </template>
            <template v-if="genome && genome.present">
              <div class="lg-genome-src">
                来源：{{ sourceLabel(genome.source) }}
                <template v-if="genome.sourceParentA || genome.sourceParentB">
                  ｜父母：{{ refName(toNpcRef(genome.sourceParentA)) }} × {{ refName(toNpcRef(genome.sourceParentB)) }}
                </template>
              </div>
              <!-- 派生特征（OCEAN + 气质/学习/天赋） -->
              <div class="lg-genome-sec-title">派生特征（遗传度框架 midparent 输入）</div>
              <div v-for="t in derivedList" :key="t.key" class="lg-trait">
                <div class="lg-trait-head">
                  <span class="lg-trait-key">{{ traitLabel(t.key) }}</span>
                  <span class="lg-trait-val">{{ (t.value ?? 0).toFixed(2) }}</span>
                </div>
                <el-progress :percentage="Math.round((t.value ?? 0) * 100)" :show-text="false" :stroke-width="6"
                  :color="t.value >= 0.6 ? '#67c23a' : t.value >= 0.4 ? '#e6a23c' : '#909399'" />
              </div>
              <!-- 15 位点 -->
              <div class="lg-genome-sec-title">位点（{{ (genome.geneLoci?.loci || []).length }}）</div>
              <el-table v-if="(genome.geneLoci?.loci || []).length" :data="genome.geneLoci.loci" size="small" height="240">
                <el-table-column prop="locus_id" label="位点" width="130" />
                <el-table-column label="等位基因" width="110">
                  <template #default="{ row }">{{ (row.alleles || []).join(' / ') }}</template>
                </el-table-column>
                <el-table-column label="效应" width="80">
                  <template #default="{ row }">
                    <el-tag size="small" :type="row.effect === 'dominant' ? 'warning' : 'info'" effect="plain">
                      {{ row.effect === 'dominant' ? '显性' : '加性' }}
                    </el-tag>
                  </template>
                </el-table-column>
              </el-table>
              <!-- 表观印记 -->
              <div class="lg-genome-sec-title">表观印记（{{ (epigeneticMarks || []).length }}，7 岁前随年龄衰减）</div>
              <div v-if="(epigeneticMarks || []).length" class="lg-epi-list">
                <div v-for="(m, i) in epigeneticMarks" :key="i" class="lg-epi-item">
                  <span class="lg-epi-target">{{ epiTargetLabel(m.target) }}</span>
                  <span class="lg-epi-factor">×{{ (m.factor ?? 1).toFixed(2) }}</span>
                  <span class="lg-epi-src">{{ m.sourceEvent || '—' }}</span>
                </div>
              </div>
              <div v-else class="sub-text">（无表观印记——出生后 90 游戏日内父母经历创伤/饥荒/孤寂/丰裕才会留下）</div>
            </template>
            <EmptyState v-else size="inline" title="选择左侧角色可查看基因档案" />
          </el-card>
        </el-col>
      </el-row>
    </template>
    <EmptyState v-else size="page" icon="ic_public_albums" title="请选择一位角色" desc="左侧列出本项目的全部角色；选中后查看三代家族树、谱系边与基因档案。" />
  </div>
</template>

<script setup>
/**
 * 家族谱系页。
 * 职责：以任一 NPC 为「本人」查看三代家族树（祖辈/父母/本人与兄弟姐妹/子女 + 婚姻/亲子边），
 * 以及该角色基因档案（15 位点孟德尔位点 + derived_traits 派生特征 + 表观印记）。
 * 数据源：/api/debug/growth/lineage（LineageService.familyTree）+ /api/debug/growth/genome。
 *
 * <p><b>2026-10-25 修复「页面什么都显示不出来」</b>：本页所有请求都要 {@code npcRef}（形如
 * {@code npc:12}），而改前选择器的 {@code :value} 取的是 {@code ch.npcRef}——角色接口
 * （CharacterVO）当时并没有这个字段 → 选项能列出、但选中后 {@code npcRef} 仍是 undefined
 * → {@code v-if="npcRef"} 恒假 → 页面永远停在「请选择一位角色」，谱系/基因两个请求一次都没发。
 * 现在取值走 {@link refOf}（{@code ch.npcRef} 优先，缺失时用 {@code shared/npcRef.js} 拼装，
 * 与后端 {@code NpcRefs} 同源，不手写前缀），后端 CharacterVO 也补了 npcRef/stage/age。</p>
 */
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { fetchCharacters, fetchLineageTree, fetchGenome } from '@/shared/api'
import { npcRef as toNpcRef } from '@/shared/npcRef'
// V2.0 批次二：空态统一走 EmptyState
import EmptyState from '@/shared/components/EmptyState.vue'

const route = useRoute()
const projectId = computed(() => Number(route.params.id))

const characters = ref([])
const npcRef = ref(null)
const tree = ref(null)
const genome = ref(null)
const loading = ref(false)
/** 加载失败原因（有内容就顶部红条提示，不再静默空白） */
const loadError = ref('')

/**
 * 角色 → 统一身份引用 {@code npc:{id}}。
 * <p>优先用后端返回的 npcRef（CharacterVO 新增字段）；旧后端没有这个字段时回落到
 * {@code shared/npcRef.js} 的唯一拼装点——绝不能取 {@code ch.npcRef} 当选择值还可能为 undefined。</p>
 */
function refOf(ch) {
  return ch?.npcRef || toNpcRef(ch?.id) || null
}

/** 选择器标签：有阶段/年龄才拼括号（旧后端缺字段时不会显示「— ?岁」） */
function charLabel(ch) {
  const parts = []
  if (ch?.stage) parts.push(stageLabel(ch.stage))
  if (ch?.age != null) parts.push(`${ch.age}岁`)
  return parts.length ? `${ch.name}（${parts.join(' ')}）` : (ch?.name || '未命名角色')
}

/** 代际分列（relation 标签 → 代际） */
const GEN = {
  祖辈: -2, 祖父: -2, 祖母: -2, 外祖父: -2, 外祖母: -2, 祖辈配偶: -2,
  父亲: -1, 母亲: -1, 岳父: -1, 岳母: -1, 公公: -1, 婆婆: -1,
  本人: 0, 配偶: 0, 兄弟姐妹: 0, 亲属: 0,
  子女: 1, 女婿: 1, 儿媳: 1, 孙辈: 2
}

const generations = computed(() => {
  if (!tree.value) return []
  const cols = [
    { key: 'g-2', title: '祖辈（祖父母/外祖父母）', gen: -2 },
    { key: 'g-1', title: '父母辈', gen: -1 },
    { key: 'g0', title: '本人 / 配偶 / 兄弟姐妹', gen: 0 },
    { key: 'g1', title: '子女辈', gen: 1 }
  ]
  return cols.map((c) => ({
    ...c,
    nodes: (tree.value.nodes || []).filter((n) => (GEN[n.relation] ?? 0) === c.gen)
  }))
})

const focusName = computed(() => {
  if (!tree.value) return npcRef.value || '—'
  const n = (tree.value.nodes || []).find((x) => x.ref === tree.value.focus)
  return n ? n.name : tree.value.focus
})

const derivedList = computed(() => {
  const t = genome.value?.geneLoci?.derived_traits || {}
  return [
    ...['OCEAN_OPENNESS', 'OCEAN_CONSCIENTIOUSNESS', 'OCEAN_EXTRAVERSION', 'OCEAN_AGREEABLENESS', 'OCEAN_NEUROTICISM'],
    ...['TEMPO_AROUSAL', 'TEMPO_RECOVERY', 'LEARN_SOCIAL', 'LEARN_HABIT', 'APTITUDE_GENERAL']
  ]
    .filter((k) => t[k] != null)
    .map((k) => ({ key: k, value: t[k] }))
})

const epigeneticMarks = computed(() => {
  const e = genome.value?.epigenetics
  if (!e) return []
  if (Array.isArray(e)) return e
  if (Array.isArray(e.marks)) return e.marks
  return []
})

async function loadCharacters() {
  try {
    characters.value = (await fetchCharacters(projectId.value)) || []
  } catch (e) {
    characters.value = []
    loadError.value = '读取角色列表失败：' + (e?.message || '未知错误')
  }
}

async function loadAll() {
  if (!projectId.value || !npcRef.value) return
  loading.value = true
  loadError.value = ''
  try {
    const [t, g] = await Promise.all([
      fetchLineageTree(projectId.value, npcRef.value),
      fetchGenome(projectId.value, npcRef.value)
    ])
    tree.value = t
    genome.value = g
  } catch (e) {
    tree.value = null
    genome.value = null
    loadError.value = '加载谱系失败：' + (e?.message || '未知错误')
    console.error('[lineage] 加载失败', e)
  } finally {
    loading.value = false
  }
}

function switchFocus(ref) {
  if (ref && ref !== npcRef.value) {
    npcRef.value = ref
    loadAll()
  }
}

function refName(ref) {
  if (!ref) return '—'
  const ch = characters.value.find((c) => c.npcRef === ref)
  return ch ? ch.name : ref
}

function stageLabel(s) {
  return { INFANT: '婴儿', TODDLER: '幼儿', CHILD: '儿童', ADOLESCENT: '少年', YOUTH: '青年', PRIME: '壮年', MIDDLE: '中年', ELDER: '老年', ANCIENT: '耄耋', DECEASED: '已故' }[s] || s || '—'
}

function genderType(g) {
  return g === '女' ? 'danger' : 'success'
}

function relTagType(r) {
  if (!r) return 'info'
  if (['本人', '配偶'].includes(r)) return 'danger'
  if (['父亲', '母亲', '祖父', '祖母', '外祖父', '外祖母'].includes(r)) return 'success'
  if (['子女'].includes(r)) return 'warning'
  return 'info'
}

/**
 * 是否婚姻边。
 * <p>后端 {@code ActorGrowthLineage.EDGE_MARRIAGE = "marriage"}（小写），改前前端拿
 * {@code 'MARRIAGE'} 大写比对 → 婚姻边被渲染成「亲子」，当事人那一列还显示「阿澈 → 苏晚晴」
 * （夫妻被当成父子）。这里统一按小写 + 兼容旧大写。</p>
 */
function isMarriage(row) {
  const t = String(row?.type || '').toLowerCase()
  return t === 'marriage'
}

/**
 * 关系文本。
 *
 * @param {string} r 关系码（father/mother/spouse…）
 * @param {string} type 边类型（marriage/parent_child）
 * @returns {string} 中文
 */
function relLabel(r, type) {
  if (r === 'father') return '父'
  if (r === 'mother') return '母'
  if (r) return r
  return String(type || '').toLowerCase() === 'marriage' ? '配偶' : '—'
}

function sourceLabel(s) {
  return { inherited: '父母遗传（孟德尔重组）', inferred: '程序化推断（存量角色）', random: '随机初始化' }[s] || s || '—'
}

function traitLabel(k) {
  return {
    OCEAN_OPENNESS: '开放性 Openness',
    OCEAN_CONSCIENTIOUSNESS: '尽责性 Conscientiousness',
    OCEAN_EXTRAVERSION: '外向性 Extraversion',
    OCEAN_AGREEABLENESS: '宜人性 Agreeableness',
    OCEAN_NEUROTICISM: '神经质 Neuroticism',
    TEMPO_AROUSAL: '唤醒阈值（气质）',
    TEMPO_RECOVERY: '恢复速率（气质）',
    LEARN_SOCIAL: '社交学习倾向',
    LEARN_HABIT: '习惯形成倾向',
    APTITUDE_GENERAL: '天赋综合倾向'
  }[k] || k
}

function epiTargetLabel(t) {
  return { NEUROTICISM: '神经质', METABOLISM: '代谢基线', EXTRAVERSION: '外向性', AGREEABLENESS: '宜人性' }[t] || t || '—'
}

watch(projectId, () => {
  npcRef.value = null
  tree.value = null
  genome.value = null
  loadCharacters()
})

onMounted(async () => {
  await loadCharacters()
  // 默认聚焦第一个特殊型 NPC（如有）
  const first = characters.value.find((c) => c.type === 'special') || characters.value[0]
  const ref = refOf(first)
  if (ref) {
    npcRef.value = ref
    await loadAll()
  }
})
</script>

<style scoped>
.lineage-view { display: flex; flex-direction: column; gap: 12px; }

.lg-toolbar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.lg-title { font-weight: 700; font-size: 1.05rem; color: var(--text-primary); }
.lg-select { width: 280px; }
.lg-hint { font-size: 0.78rem; color: var(--text-secondary); }
/* 加载失败提示条（改前失败只进 console，页面静默空白） */
.lg-error { margin: 0; }

.lg-card { border: 1px solid var(--border-l1); border-radius: 12px; margin-bottom: 12px; }
.lg-card-head { display: flex; align-items: center; justify-content: space-between; font-weight: 600; }

/* 家族树代际列 */
.lg-tree { display: grid; grid-template-columns: repeat(4, minmax(150px, 1fr)); gap: 10px; overflow-x: auto; }
.lg-gen { background: var(--bg-layer-2, #f5f7fb); border: 1px dashed var(--border-l1); border-radius: 10px; padding: 10px; min-width: 150px; }
.lg-gen-title { font-size: 0.8rem; font-weight: 600; color: var(--text-secondary); margin-bottom: 8px; }
.lg-gen-cards { display: flex; flex-direction: column; gap: 8px; }
.lg-gen-empty { font-size: 0.75rem; color: var(--text-placeholder); padding: 6px 0; }

.lg-node {
  background: var(--bg-page, #fff); border: 1px solid var(--border-l1); border-radius: 10px;
  padding: 8px 10px; cursor: pointer; transition: all 0.15s; position: relative;
}
.lg-node:hover { border-color: var(--accent-border, #9db8ff); box-shadow: var(--shadow-lv1); }
.lg-node.focus { border-color: var(--brand-primary, #1d5cff); box-shadow: 0 0 0 2px var(--accent-soft, #e8efff); }
.lg-node.deceased { opacity: 0.55; filter: grayscale(0.6); }
.lg-node-name { font-weight: 600; font-size: 0.88rem; color: var(--text-primary); }
.lg-node-rel { margin: 4px 0; }
.lg-node-meta { display: flex; align-items: center; gap: 6px; font-size: 0.74rem; color: var(--text-secondary); }
.lg-node-stage { }
.lg-node-age { }
.lg-node-dead { position: absolute; top: 6px; right: 8px; font-size: 0.7rem; color: var(--state-error, #f56c6c); font-weight: 600; }
.lg-tree-hint { margin-top: 8px; font-size: 0.75rem; color: var(--text-placeholder); }

/* 谱系边 */
.lg-rel { font-size: 0.78rem; }
.lg-ref { font-size: 0.78rem; color: var(--text-primary); }
.lg-arrow { margin: 0 6px; color: var(--text-placeholder); }

/* 基因档案 */
.lg-genome-src { font-size: 0.78rem; color: var(--text-secondary); margin-bottom: 8px; }
.lg-genome-sec-title { font-size: 0.8rem; font-weight: 600; color: var(--text-secondary); margin: 10px 0 6px; border-top: 1px dashed var(--border-l1); padding-top: 8px; }
.lg-trait { margin-bottom: 6px; }
.lg-trait-head { display: flex; justify-content: space-between; font-size: 0.76rem; margin-bottom: 2px; }
.lg-trait-key { color: var(--text-regular); }
.lg-trait-val { font-family: var(--font-mono, monospace); color: var(--text-secondary); }
.lg-epi-list { display: flex; flex-direction: column; gap: 6px; }
.lg-epi-item { display: flex; align-items: center; gap: 8px; font-size: 0.78rem; background: var(--bg-layer-2, #f5f7fb); border-radius: 8px; padding: 6px 8px; }
.lg-epi-target { font-weight: 600; color: var(--text-primary); }
.lg-epi-factor { font-family: var(--font-mono, monospace); color: var(--state-warning, #e6a23c); }
.lg-epi-src { color: var(--text-secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
