<template>
  <div class="macro">
    <h2 class="page-title">宏观社会面板（M3/M4/M5）</h2>

    <div class="toolbar">
      <el-select v-model="projectId" clearable filterable placeholder="选择项目" class="proj-select" @change="reloadAll">
        <el-option v-for="p in projects" :key="p.id" :label="`#${p.id} ${p.name || ''}`" :value="p.id" />
      </el-select>
      <el-button size="small" type="primary" :disabled="!projectId" @click="reloadAll">刷新</el-button>
      <span class="hint">宏观调度：每日 00:05 收成→粮价→民怨 / 每旬 00:10 势力 / 每月 00:15 文化科技</span>
    </div>

    <template v-if="projectId">
      <!-- ==================== 状态摘要 ==================== -->
      <el-card shadow="never" class="card">
        <template #header><div class="card-title">状态摘要（/macro/state）</div></template>
        <div v-if="state" class="sum-grid">
          <div class="sum-cell"><span class="k">模板</span><span class="v">{{ templateLabel(state.templateId) }}（{{ state.templateId }}）</span></div>
          <div class="sum-cell"><span class="k">AI 强度</span><span class="v">{{ aiIntensityLabel }}</span></div>
          <div class="sum-cell"><span class="k">游戏日</span><span class="v">第 {{ (state.gameDay || 0) + 1 }} 日</span></div>
          <div class="sum-cell"><span class="k">季节</span><span class="v">{{ seasonName(state.season) }}</span></div>
          <div class="sum-cell"><span class="k">人口</span><span class="v">{{ state.population }} 口</span></div>
          <div class="sum-cell"><span class="k">粮价</span><span class="v">{{ state.grainPrice != null ? state.grainPrice.toFixed(1) : '—' }} 文/石</span></div>
          <div class="sum-cell"><span class="k">市集活跃</span><span class="v">{{ state.marketActivity != null ? state.marketActivity.toFixed(1) : '—' }}</span></div>
          <div class="sum-cell"><span class="k">库存</span><span class="v">{{ state.grainStock != null ? state.grainStock.toFixed(0) : '—' }} 石</span></div>
          <div class="sum-cell"><span class="k">存款 / 货币</span><span class="v">{{ state.bankDeposit != null ? state.bankDeposit.toFixed(0) : '—' }} / {{ state.currencySupply != null ? state.currencySupply.toFixed(0) : '—' }}</span></div>
          <div class="sum-cell"><span class="k">民怨</span>
            <span class="v" :class="gvCls(state.grievance)">{{ state.grievance != null ? state.grievance.toFixed(1) : '—' }}/100</span>
          </div>
          <div class="sum-cell"><span class="k">收成系数</span><span class="v">{{ state.harvestFactor != null ? state.harvestFactor.toFixed(3) : '—' }}</span></div>
          <div class="sum-cell"><span class="k">当权势力</span><span class="v">{{ state.topFaction || '—' }}</span></div>
          <div class="sum-cell"><span class="k">最强议题</span><span class="v">{{ state.topIssue || '—' }}</span></div>
          <div class="sum-cell sum-brief"><span class="k">简报</span><span class="v">{{ state.summary || '—' }}</span></div>
        </div>
        <div v-else class="empty">暂无宏观状态（未初始化；世界模拟推进后每日 00:05 自动初始化）</div>
      </el-card>

      <!-- ==================== 指标曲线 ==================== -->
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-title">
            指标曲线（快照历史）
            <el-radio-group v-model="tickType" size="small" class="filter" @change="loadSnapshots">
              <el-radio-button value="">全部</el-radio-button>
              <el-radio-button value="daily">每日</el-radio-button>
              <el-radio-button value="faction">每旬</el-radio-button>
              <el-radio-button value="monthly">每月</el-radio-button>
            </el-radio-group>
            <el-input-number v-model="snapLimit" :min="10" :max="500" :step="10" size="small" controls-position="right" class="limit" @change="loadSnapshots" />
          </div>
        </template>
        <EChart :option="curveOption" height="340px" />
      </el-card>

      <!-- ==================== 最近宏观事件 ==================== -->
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-title">
            最近宏观事件（/macro/events）
            <el-select v-model="eventSeverity" clearable size="small" placeholder="严重度≥" class="filter" @change="loadEvents">
              <el-option v-for="n in [2, 3, 4, 5]" :key="n" :label="`≥${n}`" :value="n" />
            </el-select>
          </div>
        </template>
        <el-table :data="events" size="small" empty-text="暂无宏观事件" max-height="360">
          <el-table-column label="日" width="64" align="center">
            <template #default="{ row }">第{{ row.gameDay + 1 }}日</template>
          </el-table-column>
          <el-table-column prop="eventType" label="事件" width="140" show-overflow-tooltip />
          <el-table-column prop="macroType" label="子系统" width="90" />
          <el-table-column label="严重度" width="80" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="sevTag(row.severity)" effect="light">{{ row.severity }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="来源" width="70" align="center">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="row.source === 'ai' ? 'warning' : 'info'">{{ row.source === 'ai' ? 'AI' : '规则' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="narrative" label="叙事" min-width="320" show-overflow-tooltip />
        </el-table>
      </el-card>

      <!-- ==================== NPC 入账流水 ==================== -->
      <el-card shadow="never" class="card">
        <template #header><div class="card-title">NPC 行动入账流水（/macro/deltas，M2 可归因断言回看）</div></template>
        <el-table :data="deltas" size="small" empty-text="暂无入账流水" max-height="300">
          <el-table-column label="日" width="64" align="center">
            <template #default="{ row }">第{{ row.gameDay + 1 }}日</template>
          </el-table-column>
          <el-table-column prop="npcRef" label="NPC" width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ row.npcName || row.npcRef || '—' }}</template>
          </el-table-column>
          <el-table-column prop="indicator" label="指标" width="160" show-overflow-tooltip />
          <el-table-column prop="delta" label="数值" width="90" align="right" />
          <el-table-column prop="actionText" label="行动摘要" min-width="300" show-overflow-tooltip />
        </el-table>
      </el-card>

      <!-- ==================== 全链路 trace（M5.5） ==================== -->
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-title">
            全链路 trace（/macro/trace，M5.5 tick 输入→输出→回灌逐跳回放）
            <el-select v-model="traceTickType" clearable size="small" placeholder="tick 类型" class="filter" style="width: 110px" @change="loadTraces">
              <el-option label="daily" value="daily" />
              <el-option label="faction" value="faction" />
              <el-option label="monthly" value="monthly" />
            </el-select>
            <el-input-number v-model="traceLimit" :min="10" :max="200" :step="10" size="small" controls-position="right" style="width: 110px" @change="loadTraces" />
          </div>
        </template>
        <el-table :data="traces" size="small" empty-text="暂无 trace（宏观 tick 推进后自动落库）" max-height="380" row-key="id" @expand-change="onTraceExpand">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="trace-detail">
                <div class="trace-jump">
                  <div class="trace-jump-title">① 输入（入账聚合 / 季节天气 / 话题）</div>
                  <pre class="trace-json">{{ pretty(row.input) }}</pre>
                </div>
                <div class="trace-jump">
                  <div class="trace-jump-title">② 引擎参数（收成 / 粮价 / 民怨 / 人口 / 势力 / 议题 / 科技）</div>
                  <pre class="trace-json">{{ pretty(row.params) }}</pre>
                </div>
                <div class="trace-jump">
                  <div class="trace-jump-title">③ 产出事件（actor_world_macro_event 主键）</div>
                  <div class="trace-events">
                    <el-tag v-for="id in (row.eventIds || [])" :key="id" size="small" type="info" class="evt-tag">#{{ id }}</el-tag>
                    <span v-if="!(row.eventIds || []).length" class="trace-none">无</span>
                  </div>
                </div>
                <div class="trace-jump">
                  <div class="trace-jump-title">④ 回灌落点（帧板块 / 编年史「宏观」标签 / 宏观信息包）</div>
                  <pre class="trace-json">{{ pretty(row.feedback) }}</pre>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="日" width="70" align="center">
            <template #default="{ row }">第{{ row.gameDay + 1 }}日</template>
          </el-table-column>
          <el-table-column label="tick" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="traceTickTag(row.tickType)">{{ row.tickType }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="输入摘要" min-width="260" show-overflow-tooltip>
            <template #default="{ row }">{{ traceInputSummary(row) }}</template>
          </el-table-column>
          <el-table-column label="引擎关键值" min-width="260" show-overflow-tooltip>
            <template #default="{ row }">{{ traceParamsSummary(row) }}</template>
          </el-table-column>
          <el-table-column label="事件数" width="70" align="center">
            <template #default="{ row }">{{ (row.eventIds || []).length }}</template>
          </el-table-column>
          <el-table-column label="回灌" width="130" align="center">
            <template #default="{ row }">
              <span class="fb">{{ row.feedback?.frameBoard ? '帧✓' : '帧✗' }} {{ row.feedback?.chronicleMacro ?? 0 }} 史 {{ row.feedback?.macroPackets ?? 0 }} 包</span>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- ==================== 中观组织（M6.5a：组织/指令/响应回放） ==================== -->
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-title">
            中观组织（/macro/groups，M6.5a 中观协调层）
            <el-button size="small" type="primary" class="ml-auto" @click="openCreateGroup">手动建组</el-button>
          </div>
        </template>
        <el-table :data="groups" size="small" empty-text="暂无中观组织（势力派生/自发成组/手动建组后显示）" max-height="360"
                  row-key="id" @expand-change="onGroupExpand">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="group-detail" v-loading="groupLoadingId === row.id">
                <div class="group-sec">
                  <div class="trace-jump-title">指令回放（/macro/groups/{{ row.id }}/directives）</div>
                  <el-table :data="row._directives || []" size="small" empty-text="暂无指令">
                    <el-table-column label="类型" width="90">
                      <template #default="{ row: d }"><el-tag size="small" effect="plain">{{ d.directiveType }}</el-tag></template>
                    </el-table-column>
                    <el-table-column label="来源" width="90">
                      <template #default="{ row: d }">{{ d.source }}</template>
                    </el-table-column>
                    <el-table-column label="状态" width="80">
                      <template #default="{ row: d }">{{ d.status }}</template>
                    </el-table-column>
                    <el-table-column label="下发秒" width="110">
                      <template #default="{ row: d }">{{ gameSecondText(d.issuedGameSecond) }}</template>
                    </el-table-column>
                    <el-table-column prop="payloadJson" label="指令载荷" min-width="280" show-overflow-tooltip />
                  </el-table>
                </div>
                <div class="group-sec">
                  <div class="trace-jump-title">响应回收（/macro/groups/{{ row.id }}/responses）</div>
                  <el-table :data="row._responses || []" size="small" empty-text="暂无响应回收（响应回收=仅特殊 NPC）">
                    <el-table-column prop="actorRef" label="NPC" width="140" show-overflow-tooltip />
                    <el-table-column label="响应" width="90">
                      <template #default="{ row: r }">
                        <el-tag size="small" :type="respTag(r.responseType)" effect="light">{{ respLabel(r.responseType) }}</el-tag>
                      </template>
                    </el-table-column>
                    <el-table-column prop="decisionTraceId" label="决策 trace" min-width="180" show-overflow-tooltip />
                    <el-table-column label="时刻" width="110">
                      <template #default="{ row: r }">{{ gameSecondText(r.gameSecond) }}</template>
                    </el-table-column>
                  </el-table>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="ID" width="60" align="center"><template #default="{ row }">{{ row.id }}</template></el-table-column>
          <el-table-column prop="name" label="名称" width="160" show-overflow-tooltip />
          <el-table-column label="类型" width="90">
            <template #default="{ row }">{{ groupTypeLabel(row.type) }}</template>
          </el-table-column>
          <el-table-column label="来源" width="90">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="originTag(row.origin)">{{ originLabel(row.origin) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="影响力" width="90" align="right">
            <template #default="{ row }">{{ row.influence != null ? row.influence.toFixed(1) : '—' }}</template>
          </el-table-column>
          <el-table-column prop="leaderRef" label="领袖" width="120" show-overflow-tooltip />
          <el-table-column label="成员" width="70" align="center">
            <template #default="{ row }">{{ row.memberCount ?? 0 }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" align="center">
            <template #default="{ row }">
              <el-button size="small" type="danger" text @click="dissolveGroup(row)">解散</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- ==================== 二阶涌现（M6.6：涌现事件 + 成员明细） ==================== -->
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-title">
            二阶涌现（/macro/emergence，M6.6 行为聚合涌现检测）
            <el-input-number v-model="emergenceLimit" :min="5" :max="200" :step="5" size="small" controls-position="right" class="limit" @change="loadEmergence" />
          </div>
        </template>
        <el-table :data="emergences" size="small" empty-text="暂无涌现事件（NPC 行为聚合超阈值后每日 tick 自动检测）" max-height="380"
                  row-key="id" @expand-change="onEmergenceExpand">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="group-detail" v-loading="emergenceLoadingId === row.id">
                <div class="trace-jump-title">叙事（AI/规则回退）</div>
                <div class="emg-narrative">{{ row._detail?.narrative || row.narrative || '—' }}</div>
                <div class="trace-jump-title">参与成员明细（{{ (row._detail?.members || []).length }} 人）</div>
                <el-table :data="row._detail?.members || []" size="small" empty-text="无成员明细">
                  <el-table-column prop="actorRef" label="NPC" width="160" show-overflow-tooltip />
                  <el-table-column prop="actionType" label="行动" width="120" show-overflow-tooltip />
                  <el-table-column prop="indicator" label="指标" width="160" show-overflow-tooltip />
                  <el-table-column label="入账" width="90" align="right">
                    <template #default="{ row: m }">{{ m.delta }}</template>
                  </el-table-column>
                  <el-table-column label="日" width="70" align="center">
                    <template #default="{ row: m }">第{{ m.gameDay + 1 }}日</template>
                  </el-table-column>
                </el-table>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="日" width="64" align="center">
            <template #default="{ row }">第{{ row.gameDay + 1 }}日</template>
          </el-table-column>
          <el-table-column prop="emergenceTypeDisplay" label="涌现类型" width="110" show-overflow-tooltip />
          <el-table-column prop="behaviorClass" label="行为类" width="90" show-overflow-tooltip />
          <el-table-column label="成员" width="70" align="center">
            <template #default="{ row }">{{ row.memberCount }}</template>
          </el-table-column>
          <el-table-column label="权重" width="80" align="right">
            <template #default="{ row }">{{ row.weight != null ? row.weight.toFixed(1) : '—' }}</template>
          </el-table-column>
          <el-table-column label="严重度" width="80" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="sevTag(row.severity)" effect="light">{{ row.severity }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="narrative" label="叙事" min-width="320" show-overflow-tooltip />
          <el-table-column label="冷却至" width="80" align="center">
            <template #default="{ row }">第{{ (row.cooledUntilDay || 0) + 1 }}日</template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- ==================== 行为多样性（M6.5b：状态涟漪注入回放） ==================== -->
      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-title">
            行为多样性（/macro/diversity，M6.5b 状态涟漪注入回放）
            <el-input v-model="diversityActor" size="small" placeholder="角色 ref 过滤（可选）" clearable class="filter" style="width: 200px" @change="loadDiversity" />
            <el-input-number v-model="diversityLimit" :min="10" :max="300" :step="10" size="small" controls-position="right" class="limit" @change="loadDiversity" />
          </div>
        </template>
        <el-table :data="diversities" size="small" empty-text="暂无多样性注入记录（特殊 NPC AI 决策前注入后落库）" max-height="360">
          <el-table-column label="日" width="64" align="center">
            <template #default="{ row }">第{{ (row.gameDay || 0) + 1 }}日</template>
          </el-table-column>
          <el-table-column prop="actorRef" label="角色" width="140" show-overflow-tooltip />
          <el-table-column label="通道" width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <el-tag v-for="c in ((row.channel || '') + '').split(',').filter(Boolean)" :key="c" size="small" effect="plain" class="ch-tag">{{ c }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="强度" width="80" align="right">
            <template #default="{ row }">{{ row.strength != null ? row.strength.toFixed(3) : '—' }}</template>
          </el-table-column>
          <el-table-column prop="source" label="来源" width="120" show-overflow-tooltip />
          <el-table-column label="到期" width="110">
            <template #default="{ row }">{{ gameSecondText(row.expiresGameSecond) }}</template>
          </el-table-column>
          <el-table-column label="上下文" min-width="260" show-overflow-tooltip>
            <template #default="{ row }">{{ diversityContextSummary(row.contextJson) }}</template>
          </el-table-column>
          <el-table-column prop="traceId" label="trace" min-width="140" show-overflow-tooltip />
        </el-table>
      </el-card>

      <!-- ==================== 控制（模板覆盖 / 初始化 / AI 强度） ==================== -->
      <el-card shadow="never" class="card">
        <template #header><div class="card-title">控制（模板覆盖 / 初始化 / AI 强度）</div></template>
        <div class="ctrl-row">
          <span class="ctrl-label">世界观模板（M3 双维度）</span>
          <el-select v-model="templateId" size="small" class="ctrl-select">
            <el-option v-for="t in templateOptions" :key="t.id" :label="t.label" :value="t.id" />
          </el-select>
          <el-button size="small" :loading="savingTemplate" @click="applyTemplate">应用模板</el-button>
        </div>
        <div class="ctrl-row">
          <span class="ctrl-label">AI 强度（M5 独立四档）</span>
          <el-select v-model="aiIntensity" size="small" class="ctrl-select">
            <el-option label="纯规则（零 AI）" value="programmatic" />
            <el-option label="轻 AI（预算减半）" value="light" />
            <el-option label="标准（默认）" value="full" />
            <el-option label="全 AI（叙事拉满）" value="max" />
          </el-select>
          <el-button size="small" :loading="savingIntensity" @click="applyIntensity">应用 AI 强度</el-button>
        </div>
        <div class="ctrl-row">
          <span class="ctrl-label">宏观状态初始化</span>
          <el-button size="small" type="warning" plain :loading="initializing" @click="doInitialize">重置宏观状态（世界观默认值）</el-button>
          <span class="hint">⚠ 重置后历史快照保留，当前指标从默认值重新演进</span>
        </div>
      </el-card>
    </template>

    <!-- 手动建组对话框（M6.5a 调试/定向冒烟，可回滚——解散） -->
    <el-dialog v-model="createGroupOpen" title="手动建组（中观组织）" width="420px">
      <el-form label-width="76px" @submit.prevent>
        <el-form-item label="名称">
          <el-input v-model="newGroup.name" placeholder="如：商路行会" @keyup.enter="doCreateGroup" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="newGroup.type" style="width: 100%">
            <el-option label="宗族 family（影响力/动员型）" value="family" />
            <el-option label="门派 sect（武力/权威型）" value="sect" />
            <el-option label="行会 guild（同业/经济型）" value="guild" />
            <el-option label="结社 community（乡邻/舆论型）" value="community" />
          </el-select>
        </el-form-item>
        <el-form-item label="领袖">
          <el-input v-model="newGroup.leaderRef" placeholder="角色 ref（可选，如 npc:3）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createGroupOpen = false">取消</el-button>
        <el-button type="primary" :loading="creatingGroup" @click="doCreateGroup">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { get, post, put, del } from '../api'
import EChart from '../components/EChart.vue'

/**
 * 宏观社会面板（M3/M4/M5 调试页，2026-09-16）。
 * 职责：宏观演化一屏总览——状态摘要（/macro/state）、指标曲线（/macro/snapshot 五段指标）、
 * 最近宏观事件（/macro/events）、NPC 入账流水（/macro/deltas）、
 * 控制（模板覆盖 / 初始化 / AI 强度，M3 双维度模板 + M5 独立四档维度）。
 */

const projects = ref([])
const projectId = ref(null)

const state = ref(null)
const snapshots = ref([])
const events = ref([])
const deltas = ref([])
const traces = ref([])

// M6.5a 中观组织 / M6.6 二阶涌现 / M6.5b 行为多样性
const groups = ref([])
const emergences = ref([])
const diversities = ref([])
const emergenceLimit = ref(50)
const diversityLimit = ref(80)
const diversityActor = ref('')
const groupLoadingId = ref(null)
const emergenceLoadingId = ref(null)
// 手动建组表单
const createGroupOpen = ref(false)
const creatingGroup = ref(false)
const newGroup = ref({ name: '', type: 'family', leaderRef: '' })

const tickType = ref('')
const snapLimit = ref(120)
const eventSeverity = ref(null)
const traceTickType = ref('')
const traceLimit = ref(60)

// 模板（当前值 + 五内置模板）
const templateOptions = [
  { id: 'ancient', label: 'ancient 古代·农耕' },
  { id: 'xianxia', label: 'xianxia 仙侠·农耕（默认）' },
  { id: 'wuxia', label: 'wuxia 高武·农耕' },
  { id: 'modern', label: 'modern 现代·工业' },
  { id: 'future', label: 'future 未来·信息' }
]
const templateId = ref('xianxia')

// AI 强度
const aiIntensity = ref('full')
const aiIntensityLabel = ref('—')

const savingTemplate = ref(false)
const savingIntensity = ref(false)
const initializing = ref(false)

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

function seasonName(s) {
  return { spring: '春季', summer: '夏季', autumn: '秋季', winter: '冬季' }[s] || '—'
}
function templateLabel(t) {
  return { ancient: '古代·农耕', xianxia: '仙侠·农耕', wuxia: '高武·农耕', modern: '现代·工业', future: '未来·信息' }[t] || t || '—'
}
function gvCls(g) { return g >= 80 ? 'gv-high' : g >= 50 ? 'gv-mid' : '' }
function sevTag(s) { return s >= 5 ? 'danger' : s === 4 ? 'warning' : s === 3 ? 'primary' : 'info' }

async function reloadAll() {
  if (!projectId.value) return
  await Promise.all([loadState(), loadSnapshots(), loadEvents(), loadDeltas(), loadTraces(), loadEngine(),
    loadGroups(), loadEmergence(), loadDiversity()])
}

async function loadState() {
  try { state.value = await get(`/projects/${projectId.value}/macro/state`) } catch { state.value = null }
}

async function loadSnapshots() {
  if (!projectId.value) return
  try {
    const params = { limit: snapLimit.value }
    if (tickType.value) params.tickType = tickType.value
    snapshots.value = await get(`/projects/${projectId.value}/macro/snapshot`, params)
  } catch { snapshots.value = [] }
}

async function loadEvents() {
  if (!projectId.value) return
  try {
    const params = { limit: 80 }
    if (eventSeverity.value != null) params.severity = eventSeverity.value
    events.value = await get(`/projects/${projectId.value}/macro/events`, params)
  } catch { events.value = [] }
}

async function loadDeltas() {
  if (!projectId.value) return
  try { deltas.value = await get(`/projects/${projectId.value}/macro/deltas`, { limit: 60 }) } catch { deltas.value = [] }
}

async function loadTraces() {
  if (!projectId.value) return
  try {
    const params = { limit: traceLimit.value }
    if (traceTickType.value) params.tickType = traceTickType.value
    traces.value = await get(`/projects/${projectId.value}/macro/trace`, params)
  } catch { traces.value = [] }
}

/** trace 展开行加载（懒加载空操作占位：数据已在列表行内，无需额外请求）。 */
function onTraceExpand() { /* 数据已内联在行内 */ }

// ==================== M6.5a 中观组织 ====================

async function loadGroups() {
  if (!projectId.value) return
  try { groups.value = await get(`/projects/${projectId.value}/macro/groups`) } catch { groups.value = [] }
}

function openCreateGroup() {
  newGroup.value = { name: '', type: 'family', leaderRef: '' }
  createGroupOpen.value = true
}

async function doCreateGroup() {
  if (!newGroup.value.name?.trim()) { alert('请输入组织名称'); return }
  creatingGroup.value = true
  try {
    await post(`/projects/${projectId.value}/macro/groups`, {
      name: newGroup.value.name.trim(),
      type: newGroup.value.type,
      leaderRef: newGroup.value.leaderRef?.trim() || null
    })
    createGroupOpen.value = false
    await loadGroups()
  } catch (e) { alert('建组失败：' + e.message) } finally { creatingGroup.value = false }
}

async function dissolveGroup(row) {
  if (!confirm(`确认解散组织「${row.name}」？成员关系与指令一并软删（可回滚）。`)) return
  try {
    await del(`/projects/${projectId.value}/macro/groups/${row.id}`)
    await loadGroups()
  } catch (e) { alert('解散失败：' + e.message) }
}

/** 组织展开行懒加载：指令回放 + 响应回收。 */
async function onGroupExpand(row, expandedRows) {
  if (!expandedRows.length || row._loaded) return
  groupLoadingId.value = row.id
  try {
    const [directives, responses] = await Promise.all([
      get(`/projects/${projectId.value}/macro/groups/${row.id}/directives`),
      get(`/projects/${projectId.value}/macro/groups/${row.id}/responses`)
    ])
    row._directives = directives
    row._responses = responses
    row._loaded = true
  } catch { row._directives = []; row._responses = [] } finally { groupLoadingId.value = null }
}

// ==================== M6.6 二阶涌现 ====================

async function loadEmergence() {
  if (!projectId.value) return
  try { emergences.value = await get(`/projects/${projectId.value}/macro/emergence`, { limit: emergenceLimit.value }) }
  catch { emergences.value = [] }
}

/** 涌现展开行懒加载：详情（含成员明细）。 */
async function onEmergenceExpand(row, expandedRows) {
  if (!expandedRows.length || row._detail) return
  emergenceLoadingId.value = row.id
  try { row._detail = await get(`/projects/${projectId.value}/macro/emergence/${row.id}`) }
  catch { row._detail = null } finally { emergenceLoadingId.value = null }
}

// ==================== M6.5b 行为多样性 ====================

async function loadDiversity() {
  if (!projectId.value) return
  try {
    const params = { limit: diversityLimit.value }
    if (diversityActor.value) params.actorRef = diversityActor.value
    diversities.value = await get(`/projects/${projectId.value}/macro/diversity`, params)
  } catch { diversities.value = [] }
}

/** 多样性注入上下文摘要（挑主要键渲染成一行）。 */
function diversityContextSummary(json) {
  let o = null
  try { o = typeof json === 'string' ? JSON.parse(json) : json } catch { return String(json || '') }
  if (!o || typeof o !== 'object') return ''
  const parts = []
  const pick = (k) => (o[k] != null ? o[k] : null)
  if (pick('situationSegment')) parts.push(`段=${pick('situationSegment')}`)
  if (pick('traits')) parts.push(`特质=${Object.keys(pick('traits')).length}维`)
  if (pick('preferenceBias') != null) parts.push(`偏好=${pick('preferenceBias')}`)
  if (pick('emotionBias') != null) parts.push(`情绪=${pick('emotionBias')}`)
  if (pick('socialReference') != null) parts.push(`参照=${pick('socialReference')}`)
  if (pick('emotionPleasure') != null) parts.push(`愉悦=${pick('emotionPleasure')}`)
  if (pick('emotionIntensity') != null) parts.push(`强度=${pick('emotionIntensity')}`)
  return parts.join('  ') || Object.keys(o).slice(0, 6).join(',')
}

// ==================== 标签辅助 ====================

function groupTypeLabel(t) {
  return { family: '宗族', sect: '门派', guild: '行会', community: '结社', adhoc: '自发' }[t] || t || '—'
}
function originLabel(o) {
  return { template: '模板派生', manual: '手动', spontaneous: '自发', emergent: '涌现' }[o] || o || '—'
}
function originTag(o) {
  return o === 'spontaneous' ? 'warning' : o === 'manual' ? 'success' : 'info'
}
function respLabel(t) {
  return { comply: '遵从', partial: '部分遵从', defy: '违抗', ignore: '忽略' }[t] || t || '—'
}
function respTag(t) {
  return t === 'defy' ? 'danger' : t === 'partial' ? 'warning' : t === 'comply' ? 'success' : 'info'
}
function gameSecondText(sec) {
  if (sec == null) return '—'
  return `第${Math.floor(sec / 86400) + 1}日 ${String(Math.floor((sec % 86400) / 3600)).padStart(2, '0')}:${String(Math.floor((sec % 3600) / 60)).padStart(2, '0')}`
}

/** 输入摘要（挑有信息量的键渲染成一行）。 */
function traceInputSummary(row) {
  const in_ = row.input || {}
  const parts = []
  if (in_.season) parts.push(`季节=${in_.season}`)
  if (in_.weather) parts.push(`天气=${in_.weather}`)
  if (in_.dryStreak != null) parts.push(`旱${in_.dryStreak}日`)
  if (in_.floodStreak != null) parts.push(`涝${in_.floodStreak}日`)
  if (in_.snowStreak != null) parts.push(`雪${in_.snowStreak}日`)
  if (in_.grievance != null) parts.push(`民怨=${in_.grievance}`)
  if (in_.harvestOutcome != null) parts.push(`收成结果=${in_.harvestOutcome}`)
  if (in_.contrib) parts.push(`入账=${Object.keys(in_.contrib).length}项`)
  if (in_.packetTopics && in_.packetTopics.length) parts.push(`话题=${in_.packetTopics.join('/')}`)
  if (in_.deltas) parts.push(`经济入账=${Object.keys(in_.deltas).length}项`)
  return parts.join('  ') || '—'
}

/** 引擎关键值摘要。 */
function traceParamsSummary(row) {
  const p = row.params || {}
  const parts = []
  if (p.harvestFactor != null) parts.push(`收成=${p.harvestFactor}`)
  if (p.grainPrice != null) parts.push(`粮价=${p.grainPrice}`)
  if (p.grievance != null) parts.push(`民怨=${Math.round(p.grievance)}`)
  if (p.population != null) parts.push(`人口=${p.population}`)
  if (p.factions) parts.push(`势力=${Object.keys(p.factions).length}个`)
  if (p.issues) parts.push(`议题=${Object.keys(p.issues).length}个`)
  if (p.techLevels) parts.push(`科技=${Object.keys(p.techLevels).length}维`)
  if (p.beliefs) parts.push(`信仰=${Object.keys(p.beliefs).length}项`)
  return parts.join('  ') || '—'
}

function traceTickTag(t) {
  return t === 'daily' ? 'primary' : t === 'faction' ? 'warning' : 'success'
}

function pretty(o) {
  try { return JSON.stringify(o, null, 2) } catch { return String(o) }
}

async function loadEngine() {
  try {
    const r = await get(`/projects/${projectId.value}/world-engine`)
    const c = r?.data || {}
    aiIntensity.value = c.aiIntensity || 'full'
    aiIntensityLabel.value = c.aiIntensityLabel || '标准'
  } catch { /* 引擎行懒创建 */ }
}

async function applyTemplate() {
  savingTemplate.value = true
  try {
    const r = await post(`/projects/${projectId.value}/macro/template`, { templateId: templateId.value })
    alert(`模板已切换：${templateLabel(r?.current?.id)}`)
    await loadState()
  } catch (e) { alert('应用失败：' + e.message) } finally { savingTemplate.value = false }
}

async function applyIntensity() {
  savingIntensity.value = true
  try {
    const r = await put(`/projects/${projectId.value}/world-engine`, { aiIntensity: aiIntensity.value })
    aiIntensityLabel.value = r?.data?.aiIntensityLabel || aiIntensityLabel.value
  } catch (e) { alert('保存失败：' + e.message) } finally { savingIntensity.value = false }
}

async function doInitialize() {
  if (!confirm('确认重置宏观状态？当前指标将从世界观默认值重新演进（历史快照保留）。')) return
  initializing.value = true
  try {
    state.value = await post(`/projects/${projectId.value}/macro/initialize`, {})
    await Promise.all([loadSnapshots(), loadEvents()])
  } catch (e) { alert('初始化失败：' + e.message) } finally { initializing.value = false }
}

// ==================== 曲线 ====================

function cssVar(name, fallback) {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || fallback
}
const axisColor = () => cssVar('--text-tertiary', '#8b9098')
const accentColor = () => cssVar('--accent', '#4176e6')

/** 快照指标取值（indicators 五段 JSON；缺值 → null 断线） */
function pick(row, path) {
  const seg = path.split('.')
  let cur = row?.indicators
  for (const s of seg) {
    if (cur == null) return null
    cur = cur[s]
  }
  return typeof cur === 'number' ? cur : null
}

const curveOption = computed(() => {
  const days = snapshots.value.map((r) => `D${r.gameDay + 1}`)
  const series = [
    { name: '人口', path: 'population.total', yAxisIndex: 1, color: '#8b5cf6' },
    { name: '粮价', path: 'economy.grainPrice', yAxisIndex: 0, color: '#f59e0b' },
    { name: '民怨', path: 'grievance', yAxisIndex: 0, color: '#ef4444' },
    { name: '收成系数', path: 'economy.harvestFactor', yAxisIndex: 0, color: '#22c55e' },
    { name: '市集', path: 'economy.marketActivity', yAxisIndex: 0, color: '#06b6d4' }
  ]
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: series.map((s) => s.name), textStyle: { color: axisColor() } },
    grid: { left: 55, right: 55, top: 40, bottom: 55 },
    xAxis: { type: 'category', data: days, axisLabel: { color: axisColor(), fontSize: 9 } },
    yAxis: [
      { type: 'value', name: '指标', axisLabel: { color: axisColor() } },
      { type: 'value', name: '人口', axisLabel: { color: axisColor() } }
    ],
    series: series.map((s) => ({
      name: s.name,
      type: 'line',
      yAxisIndex: s.yAxisIndex,
      data: snapshots.value.map((r) => pick(r, s.path)),
      connectNulls: true,
      smooth: true,
      itemStyle: { color: s.color },
      lineStyle: { width: 1.5 }
    }))
  }
})
</script>

<style scoped>
.macro { display: flex; flex-direction: column; gap: 14px; }
.page-title { margin: 0; font-size: 18px; color: var(--text-primary); }
.toolbar { display: flex; align-items: center; gap: 10px; }
.proj-select { width: 260px; }
.hint { font-size: 12px; color: var(--text-tertiary); }
.card { border: 1px solid var(--border-l2); }
.card-title { display: flex; align-items: center; gap: 10px; font-weight: 600; color: var(--text-primary); }
.filter { margin-left: auto; }
.limit { width: 110px; }
.sum-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px 18px; }
.sum-cell { display: flex; justify-content: space-between; gap: 12px; font-size: 13px; padding: 6px 10px; border-radius: var(--radius-sm); background: var(--bg-layer-1); }
.sum-cell .k { color: var(--text-tertiary); white-space: nowrap; }
.sum-cell .v { color: var(--text-primary); font-weight: 600; font-variant-numeric: tabular-nums; }
.sum-cell .v.gv-high { color: var(--state-error); }
.sum-cell .v.gv-mid { color: var(--state-warn); }
.sum-brief { grid-column: 1 / -1; }
.sum-brief .v { font-weight: 400; line-height: 1.7; }
.empty { color: var(--text-tertiary); font-size: 13px; padding: 12px 0; }
.ctrl-row { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; font-size: 13px; }
.ctrl-label { width: 180px; color: var(--text-secondary); }
.ctrl-select { width: 240px; }
.trace-detail { display: flex; flex-wrap: wrap; gap: 12px; padding: 6px 12px 12px; }
.trace-jump { flex: 1 1 300px; min-width: 280px; }
.trace-jump-title { font-size: 12px; color: var(--text-tertiary); margin-bottom: 4px; }
.trace-json { margin: 0; padding: 8px 10px; background: var(--bg-layer-1); border: 1px solid var(--border-l2); border-radius: var(--radius-sm); font-size: 11px; line-height: 1.6; max-height: 180px; overflow: auto; color: var(--text-secondary); }
.trace-events { display: flex; flex-wrap: wrap; gap: 6px; }
.evt-tag { cursor: pointer; }
.trace-none { font-size: 12px; color: var(--text-tertiary); }
.fb { font-size: 12px; color: var(--text-secondary); white-space: nowrap; }
.ml-auto { margin-left: auto; }
.group-detail { display: flex; flex-direction: column; gap: 12px; padding: 6px 12px 12px; }
.group-sec { display: flex; flex-direction: column; gap: 4px; }
.ch-tag { margin-right: 4px; }
.emg-narrative { font-size: 13px; line-height: 1.8; color: var(--text-secondary); padding: 8px 10px; background: var(--bg-layer-1); border: 1px solid var(--border-l2); border-radius: var(--radius-sm); margin-bottom: 10px; }
</style>
