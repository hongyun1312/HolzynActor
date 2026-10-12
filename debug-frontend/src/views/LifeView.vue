<template>
  <div class="life-view">
    <!-- 顶部：项目选择 + 刷新 -->
    <div class="toolbar">
      <el-select v-model="projectId" clearable filterable placeholder="选择项目" class="proj-select" @change="onProjectChange">
        <el-option v-for="p in projects" :key="p.id" :label="`#${p.id} ${p.name || ''}`" :value="p.id" />
      </el-select>
      <el-button size="small" type="primary" :disabled="!projectId" @click="reloadAll">刷新</el-button>
      <el-tag v-if="overview" size="small" :type="overview.enabled ? 'success' : 'info'" effect="plain">
        生命周期总开关：{{ overview.enabled ? '开' : '关' }}
      </el-tag>
      <span class="hint">O6 轨道 G2：全生命周期阶段机 + 此刻可发生空间（测试操作需后端 holzyn.actor.evaluation.test-enabled=true）</span>
    </div>

    <template v-if="projectId">
      <!-- 指标卡 -->
      <div class="metric-grid" v-if="metrics">
        <div class="metric-card" v-for="m in metricCards" :key="m.label">
          <div class="metric-label">{{ m.label }}</div>
          <div class="metric-value" :style="{ color: m.color }">{{ m.value }}</div>
        </div>
      </div>

      <!-- NPC 生命状态表 -->
      <el-card shadow="never" class="block-card">
        <template #header>
          <div class="card-header">
            <span>全角色生命状态（{{ npcs.length }}）</span>
            <el-button size="small" @click="reloadAll">刷新</el-button>
          </div>
        </template>
        <el-table :data="npcs" size="small" height="320" highlight-current-row @current-change="onSelectNpc"
          :default-sort="{ prop: 'importance', order: 'descending' }">
          <el-table-column prop="id" label="ID" width="60" />
          <el-table-column prop="name" label="名称" min-width="90" show-overflow-tooltip />
          <el-table-column prop="type" label="类型" width="70">
            <template #default="{ row }">
              <el-tag size="small" :type="row.type === 'special' ? 'warning' : 'info'" effect="plain">
                {{ row.type === 'special' ? '特殊' : '普通' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="age" label="年龄" width="60" sortable />
          <el-table-column prop="stageLabel" label="阶段" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="stageTagType(row.stage)" effect="plain">{{ row.stageLabel || row.stage }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="importance" label="重要度" width="70" sortable />
          <el-table-column prop="tier" label="LOD" width="80">
            <template #default="{ row }">
              <el-tag size="small" :type="tierTagType(row.tier)" effect="plain">{{ tierLabel(row.tier) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="空间" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.spaceSource === 'ai' ? 'success' : 'info'" effect="plain">
                {{ row.spaceSource === 'ai' ? 'AI 空间' : row.spaceSource === 'programmatic' ? '程序化' : '—' }}
              </el-tag>
              <div class="sub-text">{{ row.spaceTrigger || '' }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="currentActivity" label="当前行动" min-width="140" show-overflow-tooltip />
          <el-table-column label="测试操作" width="320" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" link @click="openSpace(row)">空间</el-button>
              <el-button size="small" link @click="openFastForward(row)">快进</el-button>
              <el-button size="small" link @click="openForceDeath(row)">死亡</el-button>
              <el-button size="small" link @click="regenerate(row)">重算</el-button>
              <el-button size="small" link @click="evalShift(row)">偏移</el-button>
              <el-button size="small" link @click="maskViolation(row)">破绽</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-row :gutter="12">
        <!-- 此刻可发生空间 -->
        <el-col :span="14">
          <el-card shadow="never" class="block-card">
            <template #header>
              <div class="card-header">
                <span>此刻可发生空间：{{ selectedNpc ? selectedNpc.name : '（未选择）' }}</span>
                <el-button size="small" :disabled="!selectedNpc" @click="reloadSpace">刷新</el-button>
              </div>
            </template>
            <template v-if="spaceDoc">
              <div class="space-inner" v-if="spaceDoc.inner">
                <div class="space-title">内心</div>
                <div v-if="spaceDoc.inner.dominant_thought">主导念头：{{ spaceDoc.inner.dominant_thought }}</div>
                <div v-if="spaceDoc.inner.body_feel">身体感受：{{ spaceDoc.inner.body_feel }}</div>
                <div v-if="spaceDoc.inner.pad">PAD：{{ padText(spaceDoc.inner.pad) }}</div>
              </div>
              <div class="space-inner">
                <div class="space-title">可发生状态</div>
                <div v-for="(s, i) in spaceDoc.possible_states || []" :key="i" class="space-item">
                  {{ i + 1 }}. {{ s.state }}（倾向 {{ pct(s.likelihood) }}）
                  <span v-if="s.trigger" class="sub-text">【若 {{ s.trigger }}】</span>
                </div>
                <div v-if="!(spaceDoc.possible_states || []).length" class="sub-text">（无）</div>
              </div>
              <div class="space-inner">
                <div class="space-title">可发生行动（软引导）</div>
                <div v-for="(a, i) in spaceDoc.possible_actions || []" :key="i" class="space-item">
                  {{ i + 1 }}. {{ a.action }}（倾向 {{ pct(a.likelihood) }}）
                  <span v-if="a.why" class="sub-text">【{{ a.why }}】</span>
                </div>
                <div v-if="!(spaceDoc.possible_actions || []).length" class="sub-text">（无）</div>
              </div>
              <div class="space-inner">
                <div class="space-title warn">不可发生（硬边界）</div>
                <div v-for="(f, i) in spaceDoc.forbidden || []" :key="i" class="space-item warn-text">
                  {{ i + 1 }}. {{ f.what }}（{{ f.reason }}）
                </div>
                <div v-if="!(spaceDoc.forbidden || []).length" class="sub-text">（无）</div>
              </div>
              <div v-if="spaceMeta" class="sub-text meta-line">
                来源：{{ spaceMeta.source }}｜触发：{{ spaceMeta.triggerCode }}｜游戏时刻：{{ spaceMeta.gameSecond }}
              </div>
            </template>
            <el-empty v-else description="选择角色后查看其此刻可发生空间" :image-size="60" />
          </el-card>
        </el-col>

        <!-- 里程碑 -->
        <el-col :span="10">
          <el-card shadow="never" class="block-card">
            <template #header>
              <div class="card-header"><span>里程碑（{{ milestones.length }}）</span></div>
            </template>
            <el-scrollbar height="380">
              <div v-for="m in milestones" :key="m.id" class="milestone-item">
                <el-tag size="small" :type="milestoneTagType(m.kind)" effect="plain">{{ milestoneLabel(m.kind) }}</el-tag>
                <div class="milestone-title">{{ m.title }}</div>
                <div class="sub-text">{{ m.stageFrom || '' }}{{ m.stageFrom && m.stageTo ? ' → ' : '' }}{{ m.stageTo || '' }}
                  ｜重要度 {{ m.importance }}</div>
                <div class="milestone-detail">{{ m.detail }}</div>
              </div>
              <el-empty v-if="!milestones.length" description="暂无里程碑" :image-size="50" />
            </el-scrollbar>
          </el-card>
        </el-col>
      </el-row>

      <!-- O6 G3：人格偏移日志 + 社会面具 -->
      <el-row :gutter="12">
        <el-col :span="12">
          <el-card shadow="never" class="block-card">
            <template #header>
              <div class="card-header">
                <span>人格偏移日志（{{ selectedNpc ? selectedNpc.name : '全部角色' }}，{{ shiftLogs.length }}）</span>
                <div>
                  <el-button size="small" @click="loadShiftLogs">刷新</el-button>
                  <el-button size="small" type="primary" :disabled="!selectedNpc" @click="evalShift()">手动评估</el-button>
                </div>
              </div>
            </template>
            <el-table :data="shiftLogs" size="small" height="300">
              <el-table-column prop="triggerLabel" label="触发" width="72" />
              <el-table-column prop="dimLabel" label="维度" width="72" />
              <el-table-column prop="field" label="字段" width="90" show-overflow-tooltip />
              <el-table-column label="Δ" width="80">
                <template #default="{ row }">
                  <span :class="deltaClass(row.delta)">{{ fmtDelta(row.delta) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="变更" width="130">
                <template #default="{ row }">
                  <span class="sub-text">{{ row.oldValue }} → {{ row.newValue }}</span>
                </template>
              </el-table-column>
              <el-table-column label="来源" width="80">
                <template #default="{ row }">
                  <el-tag size="small" :type="sourceTagType(row.source)" effect="plain">{{ sourceLabel(row.source) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="aiReason" label="AI 理由 / 兜底说明" min-width="170" show-overflow-tooltip />
            </el-table>
            <div v-if="!shiftLogs.length" class="sub-text" style="padding: 8px 2px">（暂无偏移记录——未达 30 游戏日周期或证据不足时零偏移）</div>
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card shadow="never" class="block-card">
            <template #header>
              <div class="card-header">
                <span>社会面具状态：{{ selectedNpc ? selectedNpc.name : '（未选择）' }}</span>
                <div>
                  <el-button size="small" :disabled="!selectedNpc" @click="loadMask">刷新</el-button>
                  <el-button size="small" type="warning" :disabled="!selectedNpc" @click="maskViolation()">破绽模拟</el-button>
                </div>
              </div>
            </template>
            <template v-if="maskStatus">
              <div class="space-inner">
                <div class="space-title">真实 vs 表达 PAD（双段分离）</div>
                <div class="space-item">真实：愉悦 {{ fmtP(maskStatus.realPad && maskStatus.realPad.pleasure) }} /
                  唤醒 {{ fmtP(maskStatus.realPad && maskStatus.realPad.arousal) }} /
                  支配 {{ fmtP(maskStatus.realPad && maskStatus.realPad.dominance) }}</div>
                <div class="space-item">表达：愉悦 {{ fmtP(maskStatus.expressedPad && maskStatus.expressedPad.pleasure) }} /
                  唤醒 {{ fmtP(maskStatus.expressedPad && maskStatus.expressedPad.arousal) }} /
                  支配 {{ fmtP(maskStatus.expressedPad && maskStatus.expressedPad.dominance) }}</div>
              </div>
              <el-table :data="maskStatus.rows || []" size="small" height="210">
                <el-table-column prop="contextLabel" label="情境" width="90" />
                <el-table-column label="抑制（预设→生效）" width="140">
                  <template #default="{ row }">
                    <span class="sub-text">{{ (row.presetSuppression ?? 0).toFixed(2) }} → {{ (row.effectiveSuppression ?? 0).toFixed(2) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="允许表达" min-width="130" show-overflow-tooltip>
                  <template #default="{ row }">{{ maskList(row.allowed) }}</template>
                </el-table-column>
                <el-table-column label="禁止表达" min-width="130" show-overflow-tooltip>
                  <template #default="{ row }">
                    <span class="warn-text">{{ maskList(row.forbidden) }}</span>
                  </template>
                </el-table-column>
              </el-table>
              <div v-if="!(maskStatus.rows || []).length" class="sub-text" style="padding: 8px 2px">
                （暂无面具运行态行——未经历破绽/固化时抑制与清单直接读档案 ⑪ 维预设）
              </div>
            </template>
            <el-empty v-else description="选择角色后查看面具状态（真实/表达 PAD 分离、情境抑制、允许与禁止表达）" :image-size="60" />
          </el-card>
        </el-col>
      </el-row>

      <!-- O6 G4：家族谱系 + 基因档案 -->
      <el-row :gutter="12">
        <el-col :span="14">
          <el-card shadow="never" class="block-card">
            <template #header>
              <div class="card-header">
                <span>家族谱系：{{ selectedNpc ? selectedNpc.name : '（未选择）' }}</span>
                <el-button size="small" :disabled="!selectedNpc" @click="loadLineage">刷新</el-button>
              </div>
            </template>
            <template v-if="lineageTree">
              <div class="lg-tree">
                <div v-for="gen in lineageGens" :key="gen.key" class="lg-gen">
                  <div class="lg-gen-title">{{ gen.title }}</div>
                  <div
                    v-for="node in gen.nodes"
                    :key="node.ref"
                    class="lg-node"
                    :class="{ focus: node.ref === lineageTree.focus, deceased: node.deceased }"
                    :title="'点击以该角色为视角查看'"
                    @click="switchLineageFocus(node.ref)"
                  >
                    <div class="lg-node-name">{{ node.name }}</div>
                    <div class="lg-node-sub">
                      <el-tag size="small" effect="plain">{{ node.relation }}</el-tag>
                      <span>{{ node.gender || '—' }} {{ node.age ?? '?' }}岁 {{ node.deceased ? '（已故）' : '' }}</span>
                    </div>
                  </div>
                  <div v-if="!gen.nodes.length" class="sub-text">（无）</div>
                </div>
              </div>
              <div class="sub-text meta-line">谱系边：{{ lineageEdgesText }}</div>
            </template>
            <el-empty v-else description="选择角色后查看三代家族树（婚姻/亲子边；点击节点切换视角）" :image-size="60" />
          </el-card>
        </el-col>
        <el-col :span="10">
          <el-card shadow="never" class="block-card">
            <template #header>
              <div class="card-header">
                <span>基因档案：{{ selectedNpc ? selectedNpc.name : '（未选择）' }}</span>
                <el-tag v-if="genome" size="small" :type="genome.present ? 'success' : 'info'" effect="plain">
                  {{ genome.present ? '已建档' : '未建档' }}
                </el-tag>
              </div>
            </template>
            <template v-if="genome && genome.present">
              <div class="space-inner">
                <div class="space-title">派生特征（遗传度框架 midparent 输入）</div>
                <div v-for="t in genomeTraits" :key="t.key" class="lg-trait">
                  <div class="lg-trait-head"><span>{{ t.label }}</span><span>{{ (t.value ?? 0).toFixed(2) }}</span></div>
                  <el-progress :percentage="Math.round((t.value ?? 0) * 100)" :show-text="false" :stroke-width="5"
                    :color="t.value >= 0.6 ? '#67c23a' : t.value >= 0.4 ? '#e6a23c' : '#909399'" />
                </div>
              </div>
              <div class="sub-text meta-line">
                来源：{{ genomeSourceLabel }}
                <template v-if="genome.sourceParentA || genome.sourceParentB">
                  ｜父 npc:{{ genome.sourceParentA }} × 母 npc:{{ genome.sourceParentB }}
                </template>
                ｜位点 {{ (genome.geneLoci && genome.geneLoci.loci || []).length }} 个
              </div>
            </template>
            <el-empty v-else description="选择角色查看基因档案（15 位点 + 表观印记）" :image-size="60" />
          </el-card>
        </el-col>
      </el-row>

      <!-- O6 G4：婚育测试操作（心动/求婚/婚礼/受孕/分娩/婴儿/遗产） -->
      <el-card shadow="never" class="block-card">
        <template #header>
          <div class="card-header">
            <span>G4 婚育测试操作（holzyn.actor.evaluation.test-enabled 门控）</span>
            <span class="sub-text">当事人 A/B 默认取表格选中角色；B 可手动填 npc:{id}</span>
          </div>
        </template>
        <div class="inline-form">
          <el-input v-model="g4RefA" placeholder="当事人A npc:{id}" style="width: 150px" size="small" />
          <el-input v-model="g4RefB" placeholder="当事人B npc:{id}" style="width: 150px" size="small" />
          <el-input-number v-model="g4Affinity" :min="0" :max="100" size="small" style="width: 100px" :controls="false" />
          <el-button size="small" type="primary" :disabled="!g4RefA || !g4RefB" @click="opAttract">心动判定 A→B</el-button>
          <el-button size="small" type="primary" :disabled="!g4RefA" @click="opPropose">求婚判定 A</el-button>
          <el-button size="small" type="warning" :disabled="!g4RefA || !g4RefB" @click="opWedding">强制婚礼 A+B</el-button>
          <el-button size="small" type="warning" :disabled="!g4RefA" @click="opPregnancy">强制受孕 母A</el-button>
          <el-button size="small" type="danger" :disabled="!g4RefA" @click="opOffspring">强制分娩 母A</el-button>
          <el-button size="small" @click="opInfant">婴儿推进</el-button>
          <el-button size="small" type="danger" :disabled="!g4RefA" @click="opLegacy">遗产转移 A</el-button>
        </div>
      </el-card>

      <!-- 阶段门控表 -->
      <el-card shadow="never" class="block-card">
        <template #header>
          <div class="card-header">
            <span>阶段门控表（表优先读、代码兜底；热更新即时生效）</span>
            <el-button size="small" @click="loadStages">刷新</el-button>
          </div>
        </template>
        <el-tabs v-model="gateTab">
          <el-tab-pane label="阶段" name="stage">
            <el-table :data="stages" size="small" height="260">
              <el-table-column prop="code" label="码" width="110" />
              <el-table-column prop="label" label="阶段" width="90" />
              <el-table-column prop="ageRange" label="年龄区间" width="90" />
              <el-table-column prop="summary" label="关键门控摘要" min-width="300" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="能力门控" name="gate">
            <el-table :data="gates" size="small" height="260">
              <el-table-column prop="stage" label="阶段" width="110" sortable />
              <el-table-column prop="ability" label="能力码" width="160" />
              <el-table-column label="允许" width="70">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.allowed === 1 ? 'success' : 'danger'" effect="plain">
                    {{ row.allowed === 1 ? '允许' : '禁止' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="promptText" label="注入文案" min-width="220" show-overflow-tooltip />
              <el-table-column label="操作" width="90">
                <template #default="{ row }">
                  <el-button size="small" link type="danger" @click="deleteGate(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="行为约束" name="constraint">
            <el-table :data="constraints" size="small" height="260">
              <el-table-column prop="stage" label="阶段" width="110" sortable />
              <el-table-column label="类型" width="80">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.kind === 'HARD' ? 'danger' : 'warning'" effect="plain">
                    {{ row.kind === 'HARD' ? '硬约束' : '软约束' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="content" label="约束原文" min-width="200" show-overflow-tooltip />
              <el-table-column prop="promptText" label="注入措辞" min-width="180" show-overflow-tooltip />
              <el-table-column label="操作" width="90">
                <template #default="{ row }">
                  <el-button size="small" link type="danger" @click="deleteConstraint(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="LOD 覆盖" name="lod">
            <div class="inline-form">
              <el-input v-model="lodRef" placeholder="npc:{id}" style="width: 140px" size="small" />
              <el-select v-model="lodTier" size="small" style="width: 110px">
                <el-option label="focus" value="focus" />
                <el-option label="ambient" value="ambient" />
                <el-option label="dormant" value="dormant" />
              </el-select>
              <el-button size="small" type="primary" @click="setLodOverride">覆盖</el-button>
              <span class="sub-text">当前覆盖：{{ JSON.stringify(lodOverrides) }}</span>
            </div>
          </el-tab-pane>
        </el-tabs>
      </el-card>
    </template>
    <el-empty v-else description="请选择项目" />

    <!-- 年龄快进对话框 -->
    <el-dialog v-model="ffVisible" title="年龄快进（测试接口）" width="420px">
      <el-form label-width="90px">
        <el-form-item label="角色">{{ ffName }}</el-form-item>
        <el-form-item label="目标年龄">
          <el-input-number v-model="ffTargetAge" :min="0" :max="130" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="ffVisible = false">取消</el-button>
        <el-button size="small" type="primary" @click="doFastForward">执行</el-button>
      </template>
    </el-dialog>

    <!-- 强制死亡对话框 -->
    <el-dialog v-model="fdVisible" title="强制死亡（测试接口）" width="420px">
      <el-form label-width="90px">
        <el-form-item label="角色">{{ fdName }}</el-form-item>
        <el-form-item label="原因">
          <el-input v-model="fdReason" placeholder="如：寿终正寝" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="fdVisible = false">取消</el-button>
        <el-button size="small" type="danger" @click="doForceDeath">执行</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { get, post, del } from '../api'

/**
 * 生命与人格 O6 调试页（2026-09-21，O6 轨道 G2）。
 * 职责：全生命周期阶段机 + 此刻可发生空间的可视化——角色生命状态表（阶段/年龄/LOD/空间）、
 * 空间四段式渲染、里程碑时间线、阶段门控表热更新、G2 指标卡、测试操作
 * （年龄快进/强制死亡/手动重算/日检，均受 holzyn.actor.evaluation.test-enabled 门控）。
 * 数据源：/api/debug/growth/*。
 */

const projects = ref([])
const projectId = ref(null)

const overview = ref(null)
const npcs = ref([])
const metrics = ref(null)
const stages = ref([])
const gates = ref([])
const constraints = ref([])
const milestones = ref([])
const lodOverrides = ref({})

const gateTab = ref('stage')

const selectedNpc = ref(null)
const spaceDoc = ref(null)
const spaceMeta = ref(null)

// O6 G3：人格偏移日志 + 社会面具
const shiftLogs = ref([])
const maskStatus = ref(null)

// O6 G4：家族谱系 + 基因档案 + 婚育测试操作
const lineageTree = ref(null)
const genome = ref(null)
const g4RefA = ref('')
const g4RefB = ref('')
const g4Affinity = ref(60)

// 测试操作
const ffVisible = ref(false)
const ffName = ref('')
const ffTargetAge = ref(30)
const fdVisible = ref(false)
const fdName = ref('')
const fdReason = ref('寿终正寝')
const lodRef = ref('')
const lodTier = ref('focus')

const metricCards = computed(() => {
  if (!metrics.value) return []
  const m = metrics.value
  return [
    { label: '空间 AI 成功', value: m.spaceAiOk ?? 0, color: 'var(--state-success)' },
    { label: '空间 AI 失败→程序化', value: m.spaceAiFail ?? 0, color: 'var(--state-error)' },
    { label: '空间程序化生成', value: m.spaceProgrammatic ?? 0, color: 'var(--text-secondary)' },
    { label: '空间 JSON 合规率', value: m.spaceComplianceRate == null ? '—' : m.spaceComplianceRate + '%', color: 'var(--accent-text)' },
    { label: '空间内行动率', value: m.inSpaceRate == null ? '—' : m.inSpaceRate + '%', color: 'var(--accent-text)' },
    { label: '决策总数', value: m.decisionTotal ?? 0, color: 'var(--text-regular)' },
    { label: '阶段护栏拦截', value: m.stageComplianceIntercepts ?? 0, color: 'var(--state-warning)' },
    { label: '空间护栏拦截', value: m.spaceComplianceIntercepts ?? 0, color: 'var(--state-warning)' },
    { label: '知识边界拦截', value: m.knowledgeBoundIntercepts ?? 0, color: 'var(--state-warning)' }
  ]
})

onMounted(async () => {
  try {
    const r = await get('/debug/agent/runtime-overview')
    projects.value = r.projects || []
    if (projects.value.length === 1) {
      projectId.value = projects.value[0].id
      reloadAll()
    }
  } catch { projects.value = [] }
})

function onProjectChange() {
  selectedNpc.value = null
  spaceDoc.value = null
  lineageTree.value = null
  genome.value = null
  g4RefA.value = ''
  g4RefB.value = ''
  if (projectId.value) reloadAll()
}

async function reloadAll() {
  await Promise.all([loadOverview(), loadStages(), loadMilestones(), loadLod(), loadShiftLogs()])
}

async function loadOverview() {
  if (!projectId.value) return
  try {
    const r = await get('/debug/growth/overview', { projectId: projectId.value })
    overview.value = r
    npcs.value = r.npcs || []
    metrics.value = r.metrics || null
  } catch (e) {
    overview.value = null
    npcs.value = []
  }
}

async function loadStages() {
  try {
    const r = await get('/debug/growth/stages')
    stages.value = r.stages || []
    gates.value = r.gates || []
    constraints.value = r.constraints || []
  } catch { /* 忽略 */ }
}

async function loadMilestones() {
  try {
    const r = await get('/debug/growth/milestones', { projectId: projectId.value })
    milestones.value = (r.list || []).slice(0, 100)
  } catch { milestones.value = [] }
}

async function loadLod() {
  try {
    const r = await get('/debug/growth/lod', { projectId: projectId.value })
    lodOverrides.value = r.overrides || {}
  } catch { lodOverrides.value = {} }
}

function onSelectNpc(row) {
  selectedNpc.value = row
  g4RefA.value = 'npc:' + row.id
  reloadSpace()
  loadShiftLogs()
  loadMask()
  loadLineage()
}

async function reloadSpace() {
  if (!projectId.value || !selectedNpc.value) return
  try {
    const r = await get('/debug/growth/space', { projectId: projectId.value, npcRef: 'npc:' + selectedNpc.value.id })
    const cur = r.current
    spaceMeta.value = cur
    spaceDoc.value = cur && cur.space_json ? safeParse(cur.space_json) : null
  } catch (e) {
    spaceDoc.value = null
  }
}

function openSpace(row) {
  onSelectNpc(row)
}

function openFastForward(row) {
  ffName.value = row.name
  ffTargetAge.value = (row.age ?? 0) + 10
  ffVisible.value = true
}

async function doFastForward() {
  try {
    await post('/debug/growth/age-fastforward', {
      projectId: projectId.value,
      profileId: selectedNpc.value.id,
      targetAge: ffTargetAge.value
    })
    ffVisible.value = false
    await reloadAll()
  } catch (e) { alert('年龄快进失败：' + e.message) }
}

function openForceDeath(row) {
  fdName.value = row.name
  fdVisible.value = true
}

async function doForceDeath() {
  try {
    await post('/debug/growth/force-death', {
      projectId: projectId.value,
      profileId: selectedNpc.value.id,
      reason: fdReason.value
    })
    fdVisible.value = false
    await reloadAll()
  } catch (e) { alert('强制死亡失败：' + e.message) }
}

async function regenerate(row) {
  try {
    await post('/debug/growth/space/regenerate', { projectId: projectId.value, npcRef: 'npc:' + row.id })
    if (selectedNpc.value && selectedNpc.value.id === row.id) await reloadSpace()
    await loadOverview()
  } catch (e) { alert('空间重算失败：' + e.message) }
}

// ==================== O6 G3：人格偏移 + 社会面具 ====================

async function loadShiftLogs() {
  if (!projectId.value) return
  try {
    const params = { projectId: projectId.value }
    if (selectedNpc.value) params.npcRef = 'npc:' + selectedNpc.value.id
    const r = await get('/debug/growth/shift-log', params)
    shiftLogs.value = (r.list || []).slice(0, 100)
  } catch { shiftLogs.value = [] }
}

async function loadMask() {
  if (!projectId.value || !selectedNpc.value) return
  try {
    maskStatus.value = await get('/debug/growth/mask', { projectId: projectId.value, npcRef: 'npc:' + selectedNpc.value.id })
  } catch { maskStatus.value = null }
}

async function evalShift(row) {
  if (row) selectedNpc.value = row
  if (!selectedNpc.value) return
  try {
    await post('/debug/growth/shift/evaluate', { projectId: projectId.value, npcRef: 'npc:' + selectedNpc.value.id })
    await loadShiftLogs()
    await loadOverview()
  } catch (e) { alert('人格偏移评估失败：' + e.message) }
}

async function maskViolation(row) {
  if (row) selectedNpc.value = row
  if (!selectedNpc.value) return
  try {
    const r = await post('/debug/growth/mask/violation', { projectId: projectId.value, npcRef: 'npc:' + selectedNpc.value.id })
    maskStatus.value = r
    await loadShiftLogs()
  } catch (e) { alert('面具破绽模拟失败：' + e.message) }
}

// ==================== O6 G4：家族谱系 + 基因档案 + 婚育测试操作 ====================

/** 关系标签 → 代际（与 LineageService.relationLabel 口径一致） */
const LINEAGE_GEN = {
  祖辈: -2, 祖父: -2, 祖母: -2, 外祖父: -2, 外祖母: -2, 祖辈配偶: -2,
  父亲: -1, 母亲: -1, 岳父: -1, 岳母: -1, 公公: -1, 婆婆: -1,
  本人: 0, 配偶: 0, 兄弟姐妹: 0, 亲属: 0,
  子女: 1, 女婿: 1, 儿媳: 1, 孙辈: 2
}

const lineageGens = computed(() => {
  if (!lineageTree.value) return []
  const cols = [
    { key: 'g-2', title: '祖辈', gen: -2 },
    { key: 'g-1', title: '父母辈', gen: -1 },
    { key: 'g0', title: '本人/配偶/兄弟姐妹', gen: 0 },
    { key: 'g1', title: '子女辈', gen: 1 }
  ]
  return cols.map((c) => ({
    ...c,
    nodes: (lineageTree.value.nodes || []).filter((n) => (LINEAGE_GEN[n.relation] ?? 0) === c.gen)
  }))
})

const lineageEdgesText = computed(() => {
  if (!lineageTree.value) return '—'
  const rs = lineageTree.value.relations || []
  return rs.map((r) => {
    const t = r.type === 'MARRIAGE' ? '婚姻' : '亲子'
    const rel = r.relation ? (r.relation === 'father' ? '父' : r.relation === 'mother' ? '母' : r.relation) : ''
    return `${t}(${r.from}${rel ? '·' + rel : ''}→${r.to})`
  }).join('　') || '—'
})

const genomeTraits = computed(() => {
  const t = genome.value && genome.value.geneLoci ? (genome.value.geneLoci.derived_traits || {}) : {}
  const labels = {
    OCEAN_OPENNESS: '开放性', OCEAN_CONSCIENTIOUSNESS: '尽责性', OCEAN_EXTRAVERSION: '外向性',
    OCEAN_AGREEABLENESS: '宜人性', OCEAN_NEUROTICISM: '神经质',
    TEMPO_AROUSAL: '唤醒阈值', TEMPO_RECOVERY: '恢复速率', LEARN_SOCIAL: '社交学习',
    LEARN_HABIT: '习惯形成', APTITUDE_GENERAL: '天赋综合'
  }
  return Object.entries(t)
    .filter(([, v]) => v != null)
    .map(([k, v]) => ({ key: k, label: labels[k] || k, value: v }))
})

const genomeSourceLabel = computed(() => {
  const s = genome.value && genome.value.source
  return { inherited: '父母遗传（孟德尔重组）', inferred: '程序化推断', random: '随机初始化' }[s] || s || '—'
})

async function loadLineage() {
  if (!projectId.value || !selectedNpc.value) return
  try {
    const [t, g] = await Promise.all([
      get('/debug/growth/lineage', { projectId: projectId.value, npcRef: 'npc:' + selectedNpc.value.id }),
      get('/debug/growth/genome', { projectId: projectId.value, npcRef: 'npc:' + selectedNpc.value.id })
    ])
    lineageTree.value = t
    genome.value = g
  } catch (e) {
    lineageTree.value = null
    genome.value = null
  }
}

function switchLineageFocus(ref) {
  if (!ref || ref === 'npc:' + selectedNpc.value.id) return
  const npc = npcs.value.find((n) => 'npc:' + n.id === ref)
  if (npc) {
    onSelectNpc(npc)
  } else {
    g4RefA.value = ref
    loadLineage()
  }
}

async function opAttract() {
  try {
    const r = await post('/debug/growth/romance/attract', {
      projectId: projectId.value, npcRef: g4RefA.value, partnerRef: g4RefB.value, affinity: g4Affinity.value
    })
    const d = r.decision || {}
    alert(`心动判定：${d.attracted ? '心动（' + (d.reason || '') + '）' : '未心动（' + (d.reason || '') + '）'} 置信 ${d.confidence ?? '—'}`)
  } catch (e) { alert('心动判定失败：' + e.message) }
}

async function opPropose() {
  try {
    const r = await post('/debug/growth/romance/propose', { projectId: projectId.value, npcRef: g4RefA.value })
    if (r.ok) alert('求婚成功 → 婚礼已执行')
    else alert('求婚未成功：' + (r.reason || JSON.stringify(r.decision || {})))
    await loadLineage()
    await loadOverview()
  } catch (e) { alert('求婚判定失败：' + e.message) }
}

async function opWedding() {
  try {
    await post('/debug/growth/marriage/force', { projectId: projectId.value, npcRefA: g4RefA.value, npcRefB: g4RefB.value })
    alert('强制婚礼完成')
    await loadLineage()
    await loadOverview()
  } catch (e) { alert('强制婚礼失败：' + e.message) }
}

async function opPregnancy() {
  try {
    const r = await post('/debug/growth/pregnancy/start', { projectId: projectId.value, motherRef: g4RefA.value, fatherRef: g4RefB.value })
    alert(r.ok ? '受孕成功（280 游戏日预产期）' : '受孕失败（可能已孕/门控拦截）')
  } catch (e) { alert('强制受孕失败：' + e.message) }
}

async function opOffspring() {
  try {
    const r = await post('/debug/growth/offspring/force', { projectId: projectId.value, motherRef: g4RefA.value, fatherRef: g4RefB.value })
    if (r.ok) {
      alert(`分娩成功：${r.childName}（${r.childRef}）`)
      await loadLineage()
      await loadOverview()
    } else {
      alert('分娩失败（人口上限/母亲已故等门控拦截）')
    }
  } catch (e) { alert('强制分娩失败：' + e.message) }
}

async function opInfant() {
  try {
    const r = await post('/debug/growth/infant/advance', { projectId: projectId.value })
    alert('婴儿行为推进完成：处理 ' + (r.handled ?? 0) + ' 个婴儿')
    await loadOverview()
  } catch (e) { alert('婴儿推进失败：' + e.message) }
}

async function opLegacy() {
  try {
    await post('/debug/growth/legacy/transfer', { projectId: projectId.value, npcRef: g4RefA.value })
    alert('遗产转移已执行（继承人档案追加 + 记忆/里程碑/事件留痕）')
    await loadOverview()
  } catch (e) { alert('遗产转移失败：' + e.message) }
}

async function deleteGate(row) {
  try {
    await del(`/debug/growth/gate/${row.id}`)
    await loadStages()
  } catch (e) { alert('门控删除失败：' + e.message) }
}

async function deleteConstraint(row) {
  try {
    await del(`/debug/growth/constraint/${row.id}`)
    await loadStages()
  } catch (e) { alert('约束删除失败：' + e.message) }
}

async function setLodOverride() {
  if (!lodRef.value) return
  try {
    const r = await post('/debug/growth/lod/override', { npcRef: lodRef.value, tier: lodTier.value })
    lodOverrides.value = r.overrides || {}
    await loadOverview()
  } catch (e) { alert('LOD 覆盖失败：' + e.message) }
}

// ==================== 展示辅助 ====================

function safeParse(json) {
  try { return JSON.parse(json) } catch { return null }
}

function pct(v) {
  if (v == null) return '—'
  return Math.round(v * 100) + '%'
}

function padText(pad) {
  if (!pad) return ''
  return Object.entries(pad).map(([k, v]) => `${k}=${v}`).join(' ')
}

function stageTagType(stage) {
  if (!stage) return 'info'
  if (stage === 'DECEASED') return 'danger'
  if (['INFANT', 'TODDLER', 'CHILD', 'ADOLESCENT'].includes(stage)) return 'warning'
  if (['ELDER', 'ANCIENT'].includes(stage)) return 'warning'
  return 'success'
}

function tierLabel(tier) {
  return { focus: '焦点', ambient: '常态', dormant: '休眠' }[tier] || tier || '—'
}

function tierTagType(tier) {
  return { focus: 'warning', ambient: 'info', dormant: 'info' }[tier] || 'info'
}

function milestoneLabel(kind) {
  return { stage_transition: '阶段跨越', death: '死亡', birth: '出生', marker: '标记' }[kind] || kind
}

function milestoneTagType(kind) {
  return { death: 'danger', stage_transition: 'warning', birth: 'success', marker: 'info' }[kind] || 'info'
}

// ==================== O6 G3 展示辅助 ====================

function fmtDelta(v) {
  if (v == null) return '—'
  return (v > 0 ? '+' : '') + Number(v).toFixed(3)
}

function deltaClass(v) {
  if (v == null) return ''
  if (v > 0) return 'delta-up'
  if (v < 0) return 'delta-down'
  return ''
}

function sourceLabel(s) {
  return { ai: 'AI 判定', programmatic: '程序化', breach: '破绽' }[s] || s || '—'
}

function sourceTagType(s) {
  return { ai: 'success', programmatic: 'info', breach: 'warning' }[s] || 'info'
}

function maskList(json) {
  if (!json || json === '[]') return '—'
  try { return JSON.parse(json).join('、') } catch { return json }
}

function fmtP(v) {
  if (v == null) return '—'
  return (v > 0 ? '+' : '') + Number(v).toFixed(2)
}
</script>

<style scoped>
.life-view {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.proj-select {
  width: 220px;
}

.hint {
  font-size: 12px;
  color: var(--text-tertiary);
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 8px;
}

.metric-card {
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l2);
  border-radius: var(--radius-sm);
  padding: 8px 12px;
}

.metric-label {
  font-size: 12px;
  color: var(--text-tertiary);
}

.metric-value {
  font-size: 20px;
  font-weight: 600;
  font-family: var(--font-mono, monospace);
}

.block-card {
  border: 1px solid var(--border-l2);
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}

.sub-text {
  font-size: 12px;
  color: var(--text-tertiary);
}

.space-inner {
  margin-bottom: 10px;
}

.space-title {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 4px;
}

.space-title.warn {
  color: var(--state-error);
}

.space-item {
  font-size: 13px;
  line-height: 1.7;
}

.warn-text {
  color: var(--state-error);
}

.meta-line {
  margin-top: 8px;
  border-top: 1px dashed var(--border-l2);
  padding-top: 6px;
}

.milestone-item {
  border-bottom: 1px solid var(--border-l2);
  padding: 8px 2px;
}

.milestone-title {
  font-size: 13px;
  font-weight: 600;
  margin: 4px 0 2px;
}

.milestone-detail {
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.6;
}

.inline-form {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.delta-up {
  color: var(--state-success);
}

.delta-down {
  color: var(--state-error);
}

/* O6 G4：家族谱系代际列 + 基因特征条 */
.lg-tree {
  display: grid;
  grid-template-columns: repeat(4, minmax(130px, 1fr));
  gap: 8px;
  overflow-x: auto;
}

.lg-gen {
  background: var(--bg-layer-2);
  border: 1px dashed var(--border-l2);
  border-radius: var(--radius-sm);
  padding: 8px;
  min-width: 130px;
}

.lg-gen-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-tertiary);
  margin-bottom: 6px;
}

.lg-node {
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l2);
  border-radius: var(--radius-sm);
  padding: 6px 8px;
  margin-bottom: 6px;
  cursor: pointer;
  transition: all 0.15s;
}

.lg-node:hover {
  border-color: var(--accent-text);
}

.lg-node.focus {
  border-color: var(--accent-text);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--accent-text) 20%, transparent);
}

.lg-node.deceased {
  opacity: 0.55;
  filter: grayscale(0.6);
}

.lg-node-name {
  font-size: 13px;
  font-weight: 600;
}

.lg-node-sub {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-tertiary);
}

.lg-trait {
  margin-bottom: 6px;
}

.lg-trait-head {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  margin-bottom: 2px;
}

.lg-trait-head span:last-child {
  font-family: var(--font-mono, monospace);
  color: var(--text-tertiary);
}
</style>
