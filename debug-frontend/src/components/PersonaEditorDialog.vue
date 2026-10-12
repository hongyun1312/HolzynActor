<template>
  <el-dialog
    :model-value="visible"
    :title="dialogTitle"
    width="900px"
    class="ped-dialog"
    top="4vh"
    destroy-on-close
    @update:model-value="v => emit('update:visible', v)"
    @closed="onClosed"
  >
    <template #header>
      <div class="ped-header">
        <span class="ped-title">角色档案编辑器（11 维 · G1b）</span>
        <span class="ped-sub">{{ characterName }} · 当前版本 v{{ currentVersion || '—' }}</span>
      </div>
    </template>

    <div v-if="!card" class="ped-empty">
      <el-empty description="该角色尚未生成 11 维档案，请先「AI 生成角色档案」">
        <el-button type="primary" :loading="generating" @click="regenerate">AI 生成角色档案</el-button>
      </el-empty>
    </div>

    <template v-else>
      <!-- 顶部操作区 -->
      <div class="ped-ops">
        <el-tooltip content="只补全真空维（已覆盖维原样保留）；无缺失时零 AI 调用" placement="top">
          <el-button size="small" type="success" plain :loading="completing" @click="completeDims">
            <el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;AI 补全缺失维
          </el-button>
        </el-tooltip>
        <el-button size="small" type="warning" plain :loading="generating" @click="regenerate">
          <el-icon><HIcon name="Refresh" /></el-icon>&nbsp;重新生成（AI 全量）
        </el-button>
        <el-button size="small" plain @click="loadVersions">
          <el-icon><HIcon name="Clock" /></el-icon>&nbsp;版本历史（{{ versions.length }}）
        </el-button>
        <span class="ped-hint">保存后 ③生理基线/⑤目标/⑥知识 按来源分派写回权威模块，版本号 +1</span>
      </div>

      <!-- 主体：左维索引 + 右编辑区 -->
      <div class="ped-body">
        <div class="ped-nav">
          <button
            v-for="d in dims"
            :key="d.key"
            type="button"
            class="ped-nav-item"
            :class="{ 'is-active': d.key === activeDim, 'is-missing': missingDims.has(d.key) }"
            @click="activeDim = d.key"
          >
            <span class="ped-nav-no">{{ d.no }}</span>{{ d.title }}
            <span v-if="missingDims.has(d.key)" class="ped-nav-badge" title="该维当前为空">空</span>
          </button>
        </div>

        <div class="ped-editor">
          <div v-if="!activeDef" class="ped-editor-empty">从左侧选择要编辑的维度</div>
          <template v-else>
            <div class="ped-editor-head">
              <span class="ped-editor-title">{{ activeDef.no }} {{ activeDef.title }}</span>
              <span class="ped-editor-meta">{{ activeDef.shards }} · {{ activeDef.kind }}</span>
              <el-button
                size="small"
                text
                type="primary"
                @click="toggleJsonMode(activeDim)"
              >{{ jsonMode[activeDim] ? '返回表单' : '原始 JSON' }}</el-button>
            </div>

            <!-- 表单模式 -->
            <div v-if="!jsonMode[activeDim]" class="ped-form">
              <template v-for="f in activeDef.fields" :key="f.key">
                <div class="ped-field">
                  <div class="ped-field-label">{{ f.label }}</div>
                  <div class="ped-field-ctrl">
                    <el-input
                      v-if="f.type === 'text'"
                      v-model="formValues[activeDim][f.key]"
                      size="small"
                      clearable
                      placeholder="留空=删除该字段"
                    />
                    <el-input
                      v-else-if="f.type === 'textarea'"
                      v-model="formValues[activeDim][f.key]"
                      type="textarea"
                      :rows="2"
                      size="small"
                      placeholder="留空=删除该字段"
                    />
                    <el-input-number
                      v-else-if="f.type === 'number'"
                      v-model="formValues[activeDim][f.key]"
                      size="small"
                      :controls="false"
                      style="width: 160px"
                    />
                    <div v-else-if="f.type === 'slider10' || f.type === 'slider1'" class="ped-slider-row">
                      <el-slider
                        v-model="formValues[activeDim][f.key]"
                        :min="0"
                        :max="f.type === 'slider10' ? 10 : 1"
                        :step="f.type === 'slider10' ? 1 : 0.05"
                        :show-tooltip="true"
                        style="flex: 1"
                      />
                      <span class="ped-slider-val">{{ formatSlider(formValues[activeDim][f.key], f.type) }}</span>
                    </div>
                    <el-select
                      v-else-if="f.type === 'select'"
                      v-model="formValues[activeDim][f.key]"
                      size="small"
                      clearable
                      placeholder="选择"
                      style="width: 220px"
                    >
                      <el-option v-for="o in f.options" :key="o.value" :label="o.label" :value="o.value" />
                    </el-select>
                    <el-select
                      v-else-if="f.type === 'list'"
                      v-model="formValues[activeDim][f.key]"
                      multiple
                      filterable
                      allow-create
                      default-first-option
                      size="small"
                      placeholder="回车添加一项"
                      style="width: 100%"
                    />
                    <div v-else-if="f.type === 'kv' || f.type === 'kvNum' || f.type === 'kvNum10'" class="ped-kv">
                      <div v-for="(entry, i) in kvEntries(activeDim, f.key)" :key="i" class="ped-kv-row">
                        <el-input v-model="entry.k" size="small" placeholder="键（如 village / before_authority）" class="ped-kv-k" />
                        <el-input-number
                          v-if="f.type === 'kvNum' || f.type === 'kvNum10'"
                          v-model="entry.v"
                          size="small"
                          :controls="false"
                          :min="0"
                          :max="f.type === 'kvNum10' ? 10 : 1"
                          :step="f.type === 'kvNum10' ? 1 : 0.05"
                          class="ped-kv-v"
                        />
                        <el-input v-else v-model="entry.v" size="small" placeholder="值" class="ped-kv-v" />
                        <el-button size="small" text type="danger" @click="removeKv(activeDim, f.key, i)">✕</el-button>
                      </div>
                      <el-button size="small" text type="primary" @click="addKv(activeDim, f.key)">+ 添加一项</el-button>
                    </div>
                    <div v-else-if="f.type === 'kvList'" class="ped-kv">
                      <div v-for="(entry, i) in kvEntries(activeDim, f.key)" :key="i" class="ped-kv-row">
                        <el-input v-model="entry.k" size="small" placeholder="情境码" class="ped-kv-k" />
                        <el-select
                          v-model="entry.v"
                          multiple
                          filterable
                          allow-create
                          default-first-option
                          size="small"
                          placeholder="允许/禁止的表达"
                          class="ped-kv-v"
                        />
                        <el-button size="small" text type="danger" @click="removeKv(activeDim, f.key, i)">✕</el-button>
                      </div>
                      <el-button size="small" text type="primary" @click="addKv(activeDim, f.key)">+ 添加情境</el-button>
                    </div>
                    <div v-else-if="f.type === 'events'" class="ped-kv">
                      <div v-for="(entry, i) in kvEntries(activeDim, f.key)" :key="i" class="ped-event-row">
                        <el-input v-model="entry.age" size="small" placeholder="年龄" class="ped-kv-k" style="width: 70px" />
                        <el-input v-model="entry.what" size="small" placeholder="事件（如：13 岁随父进山采药）" class="ped-kv-v" />
                        <el-button size="small" text type="danger" @click="removeKv(activeDim, f.key, i)">✕</el-button>
                      </div>
                      <el-button size="small" text type="primary" @click="addKv(activeDim, f.key)">+ 添加事件</el-button>
                    </div>
                    <div v-else-if="f.type === 'traumas'" class="ped-kv">
                      <div v-for="(entry, i) in kvEntries(activeDim, f.key)" :key="i" class="ped-trauma-row">
                        <el-input v-model="entry.event" size="small" placeholder="创伤事件" class="ped-kv-v" />
                        <el-select
                          v-model="entry.triggers"
                          multiple
                          filterable
                          allow-create
                          default-first-option
                          size="small"
                          placeholder="触发物（可选）"
                          class="ped-kv-v"
                        />
                        <el-button size="small" text type="danger" @click="removeKv(activeDim, f.key, i)">✕</el-button>
                      </div>
                      <el-button size="small" text type="primary" @click="addKv(activeDim, f.key)">+ 添加创伤</el-button>
                    </div>
                    <div v-else-if="f.type === 'breaches'" class="ped-kv">
                      <div v-for="(entry, i) in kvEntries(activeDim, f.key)" :key="i" class="ped-kv-row">
                        <el-input v-model="entry.trigger" size="small" placeholder="破绽触发物（如 酒）" class="ped-kv-k" />
                        <el-input-number v-model="entry.suppression_delta" size="small" :controls="false" :min="-1" :max="1" :step="0.05" class="ped-kv-v" />
                        <el-button size="small" text type="danger" @click="removeKv(activeDim, f.key, i)">✕</el-button>
                      </div>
                      <el-button size="small" text type="primary" @click="addKv(activeDim, f.key)">+ 添加破绽</el-button>
                    </div>
                  </div>
                </div>
              </template>
              <el-alert v-if="activeDef.fields.length === 0" type="info" :closable="false" title="该维暂无表单字段，请切到「原始 JSON」编辑" />
            </div>

            <!-- JSON 兜底模式 -->
            <div v-else class="ped-json">
              <div class="ped-json-hint">整维 JSON（snake_case 键）。保存时以本 JSON 为准替换该维；改回表单模式后以表单为准。</div>
              <el-input
                v-model="dimJsonText[activeDim]"
                type="textarea"
                :rows="16"
                class="ped-json-input"
                @input="dimJsonDirty[activeDim] = true"
              />
            </div>
          </template>
        </div>
      </div>

      <!-- 版本历史抽屉 -->
      <el-drawer v-model="versionsOpen" title="版本历史（只增不改）" size="420px" append-to-body>
        <div v-if="versions.length === 0" class="ped-empty">
          <el-empty description="暂无历史版本（存量角色在版本表落地前只有当前版）" />
        </div>
        <el-timeline v-else>
          <el-timeline-item
            v-for="v in [...versions].reverse()"
            :key="v.version"
            :timestamp="fmtTime(v.updatedAt) + ' · ' + sourceLabel(v.source)"
            placement="top"
            :type="v.version === currentVersion ? 'primary' : ''"
          >
            <div class="ped-ver-row">
              <span class="ped-ver-no">v{{ v.version }}</span>
              <el-button
                v-if="v.version !== currentVersion"
                size="small"
                text
                type="warning"
                :loading="restoring === v.version"
                @click="restoreVersion(v.version)"
              >回滚到此版</el-button>
              <el-tag v-else size="small" type="success" effect="plain">当前</el-tag>
            </div>
            <pre class="ped-ver-json">{{ pretty(v.personaJson) }}</pre>
          </el-timeline-item>
        </el-timeline>
      </el-drawer>
    </template>

    <template #footer>
      <el-button size="small" @click="emit('update:visible', false)">取消</el-button>
      <el-button size="small" type="primary" :loading="saving" :disabled="!card" @click="save">
        保存（新版本 v{{ nextVersion }}）
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 角色档案（11 维）可视化编辑器对话框（O6 轨道 G1b「前端档案编辑开放」，2026-09-22）。
 *
 * <p><b>形态（用户决策：结构化表单为主 + JSON 兜底）</b>：每维 = 表单字段控件（文本/数字/滑条/
 * 枚举/标签列表/键值对）+ 「原始 JSON」切换（表单未覆盖字段的兜底入口）；保存拼回完整档案 JSON
 * 走 {@code PUT /api/characters/{id}/card}——后端 11 维结构化校验 + ③⑤⑥ 来源分派 + 版本自增 +
 * 版本历史快照，错误逐条返回展示。</p>
 *
 * <p><b>能力</b>：保存（新版本）/ AI 补全缺失维（只填真空维）/ 重新生成（AI 全量，force）/
 * 版本历史 + 回滚（保存为新版本 source=restored）。</p>
 *
 * <p><b>双前端复用</b>：本组件在主前端与调试前端各持一份同源镜像（两个 Vite 工程各自独立构建，
 * 沿用既有 {@code PersonaArchivePanel.vue} 的工程惯例；逻辑层 {@code personaEditor.js} 同样镜像）。</p>
 *
 * <p>所属模块：features/character/components（角色/档案功能域组件）。</p>
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { PERSONA_DIMS, fmtDateTime, sourceLabel } from '../utils/personaArchive'
import { DIM_FORM_FIELDS, buildPersonaJson, formValuesOf } from '../utils/personaEditor'
import { get, post, put } from '../api'

const props = defineProps({
  /** 对话框可见性（v-model:visible） */
  visible: { type: Boolean, default: false },
  /** 角色档案 VO（CharacterCardVO；null=尚未建档） */
  card: { type: Object, default: null },
  /** 角色姓名（标题展示） */
  characterName: { type: String, default: '' }
})
const emit = defineEmits(['update:visible', 'saved'])

// ==================== 调试前端 API 封装（薄适配主前端 shared/api/character 的四个调用） ====================
const editCharacterCard = (characterId, data) => put(`/api/characters/${characterId}/card`, data)
const completeCharacterCard = (characterId) => post(`/api/characters/${characterId}/card/complete`, {})
const generateCharacterCard = (characterId) => post(`/api/characters/${characterId}/generate-card`, {})
const restoreCharacterCard = (characterId, version) => post(`/api/characters/${characterId}/card/restore`, { version })
const fetchCharacterCardVersions = (characterId) => get(`/api/characters/${characterId}/card/versions`)

/** 11 维定义 + 表单字段（与后端 PersonaSchema.Dims 键名一致） */
const dims = PERSONA_DIMS.map(d => ({ ...d, fields: DIM_FORM_FIELDS[d.key] || [] }))
const DIM_BY_KEY = new Map(dims.map(d => [d.key, d]))

/** 当前选中维 */
const activeDim = ref('identity')
const activeDef = computed(() => DIM_BY_KEY.get(activeDim.value))

/** 表单值：维键 → 字段路径 → 值 */
const formValues = reactive({})
/** 每维原始 JSON 文本（兜底模式） */
const dimJsonText = reactive({})
/** 每维是否以 JSON 为准（切过 JSON 且编辑过） */
const dimJsonDirty = reactive({})
/** 每维当前显示模式（true=JSON） */
const jsonMode = reactive({})

/** 操作态 */
const saving = ref(false)
const completing = ref(false)
const generating = ref(false)
const restoring = ref(null)

/** 版本历史 */
const versionsOpen = ref(false)
const versions = ref([])

const currentVersion = computed(() => props.card?.version ?? null)
const nextVersion = computed(() => (currentVersion.value == null ? 1 : currentVersion.value + 1))
const dialogTitle = computed(() => '角色档案编辑器 · ' + (props.characterName || '未命名角色'))

/** 空维集合（后端渲染文本里无内容的维） */
const missingDims = computed(() => {
  const set = new Set()
  const persona = parseJson(props.card?.personaJson)
  const dimsJson = persona?.dims && typeof persona.dims === 'object' ? persona.dims : {}
  for (const d of dims) {
    if (!hasAnyContent(dimsJson[d.key])) set.add(d.key)
  }
  return set
})

/** 打开/切换角色时初始化编辑状态（从最新档案提取表单初值） */
watch(
  () => [props.visible, props.card],
  () => {
    if (!props.visible) return
    initFromCard(props.card)
  },
  { immediate: true }
)

function initFromCard(card) {
  const persona = parseJson(card?.personaJson)
  const dimsJson = persona?.dims && typeof persona.dims === 'object' ? persona.dims : {}
  activeDim.value = 'identity'
  for (const d of dims) {
    const dimObj = dimsJson[d.key]
    const fields = DIM_FORM_FIELDS[d.key] || []
    formValues[d.key] = formValuesOf(dimObj, fields)
    dimJsonText[d.key] = dimObj && hasAnyContent(dimObj) ? prettyJson(dimObj) : ''
    dimJsonDirty[d.key] = false
    jsonMode[d.key] = false
  }
}

// ==================================================================================
// 保存 / AI 操作
// ==================================================================================

async function save() {
  const persona = parseJson(props.card?.personaJson)
  const fieldDefs = {}
  for (const d of dims) fieldDefs[d.key] = d.fields
  const { json, errors } = buildPersonaJson(persona, formValues, dimJsonText, dimJsonDirty, fieldDefs)
  if (errors.length > 0) {
    ElMessage.error('档案 JSON 校验未通过：' + errors.join('；'))
    return
  }
  saving.value = true
  try {
    const card = await editCharacterCard(props.card.characterId || props.card.id, { personaJson: json })
    ElMessage.success('已保存为 v' + (card?.version ?? ''))
    emit('saved', card)
  } catch (e) {
    const msg = e?.response?.data?.message || e?.message || '保存失败'
    ElMessage.error(String(msg))
  } finally {
    saving.value = false
  }
}

async function completeDims() {
  completing.value = true
  try {
    const card = await completeCharacterCard(props.card.characterId || props.card.id)
    ElMessage.success(card ? 'AI 补全完成，当前 v' + card.version : '无缺失维或补全失败（保留已得维度，可重试）')
    if (card) emit('saved', card)
  } catch (e) {
    ElMessage.error(String(e?.response?.data?.message || e?.message || 'AI 补全失败'))
  } finally {
    completing.value = false
  }
}

async function regenerate() {
  generating.value = true
  try {
    const card = await generateCharacterCard(props.card.characterId || props.card.id)
    ElMessage.success(card ? '已重新生成，当前 v' + card.version : 'AI 输出未能成为有效 11 维档案（详见服务端日志）')
    if (card) emit('saved', card)
  } catch (e) {
    ElMessage.error(String(e?.response?.data?.message || e?.message || '重新生成失败'))
  } finally {
    generating.value = false
  }
}

async function loadVersions() {
  versionsOpen.value = true
  try {
    versions.value = (await fetchCharacterCardVersions(props.card.characterId || props.card.id)) || []
  } catch (e) {
    ElMessage.error(String(e?.message || '版本历史读取失败'))
  }
}

async function restoreVersion(version) {
  restoring.value = version
  try {
    const card = await restoreCharacterCard(props.card.characterId || props.card.id, version)
    ElMessage.success('已回滚到 v' + version + '（保存为新版本 v' + (card?.version ?? '') + '）')
    emit('saved', card)
    versionsOpen.value = false
  } catch (e) {
    ElMessage.error(String(e?.response?.data?.message || e?.message || '回滚失败'))
  } finally {
    restoring.value = null
  }
}

// ==================================================================================
// 表单控件工具
// ==================================================================================

function toggleJsonMode(key) {
  jsonMode[key] = !jsonMode[key]
  if (jsonMode[key] && !dimJsonText[key]) {
    // 首次进入 JSON 模式：从原维对象生成 JSON 文本（表单未覆盖的字段保留在其中）
    const persona = parseJson(props.card?.personaJson)
    const dimObj = persona?.dims?.[key]
    dimJsonText[key] = dimObj && hasAnyContent(dimObj) ? prettyJson(dimObj) : '{\n  \n}'
  }
}

function kvEntries(dimKey, fieldKey) {
  const v = formValues[dimKey]?.[fieldKey]
  if (!v || typeof v !== 'object') return []
  if (Array.isArray(v)) return v
  // kv 对象形态 → 转为行（行内对象 {k, v}）
  const rows = []
  for (const [k, val] of Object.entries(v)) {
    rows.push({ k, v: val })
  }
  return rows
}

function addKv(dimKey, fieldKey) {
  const target = formValues[dimKey][fieldKey]
  const def = DIM_BY_KEY.get(dimKey)?.fields?.find(f => f.key === fieldKey)
  if (Array.isArray(target)) {
    target.push(def?.type === 'events' ? { age: '', what: '' }
      : def?.type === 'traumas' ? { event: '', triggers: [] }
        : def?.type === 'breaches' ? { trigger: '', suppression_delta: 0 }
          : def?.type === 'kvList' ? { k: '', v: [] }
            : { k: '', v: '' })
    return
  }
  // kv 对象形态：转成行数组并替换（简化：统一用数组行）
  const rows = Object.entries(target).map(([k, val]) => ({ k, v: val }))
  rows.push({ k: '', v: def?.type === 'kvNum' || def?.type === 'kvNum10' ? 0 : (def?.type === 'kvList' ? [] : '') })
  formValues[dimKey][fieldKey] = rows
}

function removeKv(dimKey, fieldKey, index) {
  const target = formValues[dimKey][fieldKey]
  if (Array.isArray(target)) {
    target.splice(index, 1)
    return
  }
  const rows = Object.entries(target).map(([k, val]) => ({ k, v: val }))
  rows.splice(index, 1)
  formValues[dimKey][fieldKey] = rows
}

function formatSlider(v, type) {
  if (v === undefined || v === null) return '未设'
  return type === 'slider10' ? String(v) : Number(v).toFixed(2)
}

// ==================================================================================
// 工具
// ==================================================================================

function parseJson(raw) {
  if (!raw) return {}
  if (typeof raw === 'object') return raw
  try { return JSON.parse(raw) } catch (_) { return {} }
}

function prettyJson(obj) {
  try { return JSON.stringify(obj, null, 2) } catch (_) { return String(obj) }
}

function hasAnyContent(v) {
  if (v === null || v === undefined) return false
  if (typeof v === 'string') return v.trim() !== ''
  if (Array.isArray(v)) return v.length > 0
  if (typeof v === 'object') return Object.values(v).some(x => {
    if (x === null || x === undefined) return false
    if (typeof x === 'string') return x.trim() !== ''
    if (Array.isArray(x)) return x.length > 0
    if (typeof x === 'object') return Object.keys(x).length > 0
    return true
  })
  return true
}

function fmtTime(t) {
  return fmtDateTime(t)
}

function onClosed() {
  // 关闭时清空版本列表，避免下次打开残留
  versions.value = []
}
</script>

<style scoped>
.ped-header { display: flex; align-items: baseline; gap: 10px; }
.ped-title { font-size: 15px; font-weight: 700; }
.ped-sub { font-size: 12px; color: var(--text-tertiary, #909399); }
.ped-empty { padding: 20px 0; }
.ped-ops { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.ped-hint { font-size: 11.5px; color: var(--text-tertiary, #909399); }

.ped-body { display: flex; gap: 12px; min-height: 420px; max-height: 60vh; }
.ped-nav { flex: 0 0 150px; display: flex; flex-direction: column; gap: 4px; overflow: auto; }
.ped-nav-item {
  display: flex; align-items: center; gap: 6px; padding: 6px 10px; border-radius: 6px;
  font-size: 12.5px; color: var(--text-secondary, #606266); background: var(--bg-layer-2, #f4f4f5);
  border: 1px solid transparent; cursor: pointer; text-align: left;
}
.ped-nav-item:hover { color: var(--accent-text, #409eff); }
.ped-nav-item.is-active { color: #fff; background: var(--accent-color, #409eff); }
.ped-nav-item.is-missing { border-color: var(--el-color-warning, #e6a23c); }
.ped-nav-no { font-weight: 700; }
.ped-nav-badge { margin-left: auto; font-size: 10px; color: #fff; background: var(--el-color-warning, #e6a23c); border-radius: 8px; padding: 0 5px; }

.ped-editor { flex: 1; border: 1px solid var(--border-l1, #e4e7ed); border-radius: 8px; overflow: auto; padding: 10px 12px; }
.ped-editor-empty { color: var(--text-placeholder, #c0c4cc); font-size: 13px; padding: 80px 0; text-align: center; }
.ped-editor-head { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.ped-editor-title { font-size: 14px; font-weight: 700; }
.ped-editor-meta { font-size: 11px; color: var(--text-tertiary, #909399); font-family: var(--font-mono, monospace); }
.ped-editor-head .el-button { margin-left: auto; }

.ped-form { display: flex; flex-direction: column; gap: 8px; }
.ped-field { display: flex; gap: 10px; align-items: flex-start; }
.ped-field-label { flex: 0 0 150px; font-size: 12.5px; color: var(--text-secondary, #606266); padding-top: 6px; }
.ped-field-ctrl { flex: 1; min-width: 0; }
.ped-slider-row { display: flex; align-items: center; gap: 10px; }
.ped-slider-val { font-size: 12px; font-family: var(--font-mono, monospace); width: 46px; text-align: right; color: var(--text-secondary, #606266); }

.ped-kv { display: flex; flex-direction: column; gap: 6px; }
.ped-kv-row { display: flex; gap: 6px; align-items: center; }
.ped-kv-k { flex: 0 0 46%; }
.ped-kv-v { flex: 1; min-width: 0; }
.ped-event-row, .ped-trauma-row { display: flex; gap: 6px; align-items: center; }

.ped-json-hint { font-size: 11.5px; color: var(--text-tertiary, #909399); margin-bottom: 6px; }
.ped-json-input :deep(textarea) { font-family: var(--font-mono, monospace); font-size: 12px; line-height: 1.6; }

.ped-ver-row { display: flex; align-items: center; gap: 10px; }
.ped-ver-no { font-weight: 700; font-size: 13px; }
.ped-ver-json {
  margin: 6px 0 0; padding: 8px 10px; background: var(--bg-base, #fafafa);
  border: 1px dashed var(--border-l1, #e4e7ed); border-radius: 6px;
  font-family: var(--font-mono, monospace); font-size: 11px; line-height: 1.5;
  color: var(--text-regular, #606266); white-space: pre-wrap; word-break: break-all;
  max-height: 180px; overflow: auto;
}
</style>
