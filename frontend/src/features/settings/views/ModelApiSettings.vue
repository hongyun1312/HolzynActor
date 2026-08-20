<template>
  <div class="model-api-view" :class="{ embedded }">
    <!-- 页面标题与新增入口 -->
    <div class="page-header">
      <div>
        <div class="page-title">模型 API 设置</div>
        <div v-if="!embedded" class="page-desc">{{ pageDesc }}</div>
      </div>
      <el-button type="primary" @click="openCreate">
        <el-icon><HIcon name="Plus" /></el-icon>&nbsp;新增 API
      </el-button>
    </div>

    <!-- API 列表 -->
    <div class="table-card">
      <el-table v-loading="loading" :data="list" empty-text="还没有配置 API，点击右上角「新增 API」开始添加">
        <!-- 来源列：仅项目空间显示（区分项目级/用户级；全局设置页只有用户级，无需区分） -->
        <el-table-column v-if="projectMode" label="来源" width="92">
          <template #default="{ row }">
            <el-tag v-if="row._scope === 'project'" size="small" type="success" effect="plain">项目级</el-tag>
            <el-tag v-else size="small" type="info" effect="plain">用户级</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="120">
          <template #default="{ row }">
            <span class="api-name">{{ row.name }}</span>
            <!-- 默认标记：项目级=「默认」；用户级=「用户级默认」（回退默认，与全局设置页的默认一致） -->
            <el-tag v-if="row.isDefault === 1" size="small" type="primary" effect="light" class="default-tag">{{ row._scope === 'user' ? '用户级默认' : '默认' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="baseUrl" label="Base URL" min-width="180" show-overflow-tooltip />
        <el-table-column prop="model" label="模型" min-width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ row.model || '—' }}</template>
        </el-table-column>
        <el-table-column label="用途" min-width="140">
          <template #default="{ row }">
            <el-tag v-if="purposeOf(row) === 'embedding'" size="small" type="warning" effect="light">向量化专用</el-tag>
            <el-tag v-else-if="purposeOf(row) === 'both'" size="small" type="success" effect="light">两者兼用</el-tag>
            <el-tag v-else size="small" type="primary" effect="light">主 AI 对话</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Embedding 模型" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.embeddingModel" class="emb-model">{{ row.embeddingModel }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="apiKeyMasked" label="API Key" min-width="120">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" type="info">{{ row.apiKeyMasked || '未设置' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="流式" width="70" align="center">
          <template #default="{ row }">
            <el-icon v-if="row.supportsStream === 1" color="#165DFF"><HIcon name="VideoPlay" /></el-icon>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || '—' }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="150">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openTest(row)">测试连接</el-button>
            <!-- 「设为默认」仅项目级行可用：用户级默认由全局设置管理（本页只读标记） -->
            <el-button v-if="row.isDefault !== 1 && !isUserRow(row)" link type="primary" @click="handleSetDefault(row)">设为默认</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 新增/编辑对话框 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑 API' : '新增 API'" width="560px" :close-on-click-modal="false">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="如：DeepSeek 主用 / 智谱备用" maxlength="50" />
        </el-form-item>
        <el-form-item label="Base URL" prop="baseUrl">
          <el-input v-model="form.baseUrl" placeholder="如：https://api.deepseek.com/v1（OpenAI 兼容协议地址）" maxlength="255" />
        </el-form-item>
        <el-form-item label="API Key" prop="apiKey">
          <el-input v-model="form.apiKey" type="password" show-password :placeholder="form.id ? '留空表示保持原 Key 不变' : '填写你的 API Key'" maxlength="512" />
        </el-form-item>
        <el-form-item label="模型">
          <el-input v-model="form.model" placeholder="默认模型名，可空（如 deepseek-v4-flash / deepseek-chat）" maxlength="100" />
        </el-form-item>
        <el-form-item label="用途">
          <el-radio-group v-model="form.purpose">
            <el-radio value="chat">主 AI 对话</el-radio>
            <el-radio value="embedding">向量化专用</el-radio>
            <el-radio value="both">两者兼用</el-radio>
          </el-radio-group>
          <div class="purpose-hint muted">
            主 AI（对话/角色卡/行动）只从「主 AI 对话 / 两者兼用」中选择；向量化（知识库 RAG）只从「向量化专用 / 两者兼用」中选择，互不串用。
          </div>
        </el-form-item>
        <el-form-item v-if="form.purpose === 'embedding' || form.purpose === 'both'" label="Embedding 模型">
          <el-input v-model="form.embeddingModel" placeholder="如 doubao-embedding / bge-m3 / text-embedding-3-small" maxlength="100" />
        </el-form-item>
        <el-form-item label="流式支持">
          <el-switch v-model="form.supportsStream" :active-value="true" :inactive-value="false" />
          <span class="muted form-hint">支持流式输出的供应商（对话页使用 SSE 流式）</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" placeholder="用途 / 供应商说明（选填）" maxlength="255" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :loading="testing" @click="handleTestInForm">
          <el-icon><HIcon name="Connection" /></el-icon>&nbsp;测试连接
        </el-button>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 模型 API 配置页（P1，V2.0 项目化；2026-08-19 项目空间合并展示用户级 API）。
 * <p>职责：用户级/项目级 AI 模型 API 的列表 / 新增 / 编辑 / 删除 / 设为默认 / 连通性测试。
 * 通过 projectId prop 指定归属：传入=项目级配置（随 .holzyn 包导入导出），不传=用户级默认。
 * <b>项目空间双级合并展示</b>：projectId 传入时，同一张表合并展示「项目级（本页新建/设默认）+
 * 用户级（全局回退默认，来源列标记；可编辑/删除/测试，但默认由全局设置管理）」；
 * 全局设置（projectId 空）仍只展示用户级，行为不变。
 * api_key 后端加密存储，列表仅展示脱敏尾 4 位；编辑时 Key 留空表示不修改。</p>
 * <p>数据来源：/api/model-apis 系列接口（本人可见，按用户+项目归属隔离）。</p>
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchModelApis, createModelApi, updateModelApi, deleteModelApi,
  setDefaultModelApi, testModelApi, testSavedModelApi
} from '@/shared/api'

// 归属项目：传入=项目级配置（项目空间，合并展示用户级）；不传=用户级默认（全局设置）
// embedded：内嵌到设置弹窗（GlobalSettingsDialog）中使用，隐藏页面级描述、紧凑头部，与其它弹窗 UI 一致
const props = defineProps({
  projectId: { type: [String, Number], default: null },
  embedded: { type: Boolean, default: false }
})

/** 是否项目空间模式（projectId 非空）：合并展示项目级 + 用户级 */
const projectMode = computed(() => props.projectId != null && String(props.projectId) !== '')
/** 页面描述文案（项目模式说明双级合并与回退关系） */
const pageDesc = computed(() => projectMode.value
  ? '配置你自己的 AI 模型接口（OpenAI 兼容协议）。本项目空间展示两套：上为「项目级」API（仅本项目使用，项目级优先），下为「用户级」默认 API（全局回退，来源列已标记）。API Key 加密存储，列表中仅显示脱敏尾 4 位。'
  : '配置你自己的 AI 模型接口（OpenAI 兼容协议），可保存多个，供对话 / 角色卡等场景选用。API Key 加密存储，列表中仅显示脱敏尾 4 位。')

// ===== 列表状态 =====
const list = ref([])
const loading = ref(false)

// ===== 弹窗与表单状态 =====
const dialogVisible = ref(false)
const saving = ref(false)
const testing = ref(false)
const formRef = ref(null)
const form = reactive({ id: null, name: '', baseUrl: '', apiKey: '', model: '', supportsStream: true, purpose: 'chat', embeddingModel: '', remark: '' })
/** 当前弹窗表单的作用域归属（新增/编辑的 projectId：项目行=项目，用户行=null，全局模式=null） */
const formScope = ref(props.projectId ?? null)

// 表单校验规则（名称与 Base URL 必填；Key 新建必填、编辑可空）
const rules = {
  name: [{ required: true, message: '请输入 API 名称', trigger: 'blur' }],
  baseUrl: [{ required: true, message: '请输入 Base URL', trigger: 'blur' }]
}

/** 行是否为用户级（来源标记用；全局模式下无标记字段视为非用户行） */
function isUserRow(row) { return row._scope === 'user' }

/** 行所属归属 projectId（行级操作按行自己的作用域路由）。
 *  项目模式行都已打标：项目行=项目 id，用户行=null（保持 null 路由到用户级，不能回退到项目）；
 *  全局模式行未打标（_projectId 为 undefined）→ 用 props.projectId（本就是 null=用户级）。 */
function rowProjectId(row) {
  return row._projectId !== undefined ? row._projectId : props.projectId
}

/**
 * 加载当前归属的 API 列表。
 * 项目空间模式：并行加载项目级 + 用户级并合并（项目级在上、用户级在下，各按优先级排序）；
 * 全局模式：仅用户级。
 */
async function loadList() {
  loading.value = true
  try {
    if (projectMode.value) {
      const [projectList, userList] = await Promise.all([
        fetchModelApis(props.projectId),
        fetchModelApis(null)
      ])
      const merged = [
        ...(projectList || []).map(r => ({ ...r, _scope: 'project', _projectId: props.projectId })),
        ...(userList || []).map(r => ({ ...r, _scope: 'user', _projectId: null }))
      ]
      list.value = merged
    } else {
      list.value = (await fetchModelApis(null)) || []
    }
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

/**
 * 重置表单为新增态并打开对话框（新增归属=当前页面作用域：项目模式=项目级，全局=用户级）。
 */
function openCreate() {
  Object.assign(form, { id: null, name: '', baseUrl: '', apiKey: '', model: '', supportsStream: true, purpose: 'chat', embeddingModel: '', remark: '' })
  formScope.value = props.projectId ?? null
  dialogVisible.value = true
}

/**
 * 以某行数据填充表单（编辑态），Key 置空表示保持原值；记录该行所属作用域（项目行/用户行）。
 *
 * @param {Object} row 表格行数据
 */
function openEdit(row) {
  Object.assign(form, {
    id: row.id, name: row.name, baseUrl: row.baseUrl, apiKey: '',
    model: row.model, supportsStream: row.supportsStream === 1,
    purpose: purposeOf(row), embeddingModel: row.embeddingModel || '',
    remark: row.remark
  })
  formScope.value = rowProjectId(row)
  dialogVisible.value = true
}

/**
 * 行数据 → 用途类型（旧数据无 purpose 时按 embeddingEnabled 推导）。
 *
 * @param {Object} row 表格行数据
 * @returns {string} chat / embedding / both
 */
function purposeOf(row) {
  if (row.purpose === 'embedding' || row.purpose === 'both' || row.purpose === 'chat') return row.purpose
  return row.embeddingEnabled === 1 ? 'embedding' : 'chat'
}

/**
 * 表单校验通过后执行新增/编辑保存。
 */
async function handleSave() {
  await formRef.value.validate()
  // 向量化用途必填 embedding 模型名
  if ((form.purpose === 'embedding' || form.purpose === 'both') && !form.embeddingModel.trim()) {
    ElMessage.warning('向量化用途需要填写 Embedding 模型名')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.name, baseUrl: form.baseUrl, apiKey: form.apiKey, model: form.model,
      supportsStream: form.supportsStream, purpose: form.purpose, embeddingModel: form.embeddingModel,
      remark: form.remark
    }
    if (form.id) {
      await updateModelApi(form.id, payload, formScope.value)
      ElMessage.success('保存成功')
    } else {
      await createModelApi(payload, formScope.value)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    await loadList()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

/**
 * 设为默认（仅项目级行可见；同归属内互斥，后端处理）。
 *
 * @param {Object} row 表格行数据
 */
async function handleSetDefault(row) {
  try {
    await setDefaultModelApi(row.id, rowProjectId(row))
    ElMessage.success(`已将「${row.name}」设为默认`)
    await loadList()
  } catch (e) {
    ElMessage.error(e.message || '设置失败')
  }
}

/**
 * 删除前二次确认（按行所属作用域删除：项目行删项目级，用户行删用户级）。
 *
 * @param {Object} row 表格行数据
 */
async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确认删除 API「${row.name}」？删除后不可恢复。`, '删除确认', { type: 'warning' })
  } catch (_) {
    return // 用户取消
  }
  try {
    await deleteModelApi(row.id, rowProjectId(row))
    ElMessage.success('删除成功')
    await loadList()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

/**
 * 已保存配置的连通性测试（使用解密后的真实 Key；按行所属作用域路由）。
 *
 * @param {Object} row 表格行数据
 */
async function openTest(row) {
  ElMessage.info('正在测试连接…')
  try {
    const r = await testSavedModelApi(row.id, rowProjectId(row))
    if (r.connected) ElMessage.success(`连接成功（${r.method}，耗时 ${r.latencyMs}ms）`)
    else ElMessage.error(r.message || '连接失败')
  } catch (e) {
    ElMessage.error(e.message || '测试失败')
  }
}

/**
 * 表单内连通性测试：已有 id 用已保存 Key（按表单作用域）；否则用当前输入的明文 Key（不入库）。
 */
async function handleTestInForm() {
  testing.value = true
  try {
    const r = form.id
      ? await testSavedModelApi(form.id, formScope.value)
      : await testModelApi({ baseUrl: form.baseUrl, apiKey: form.apiKey, model: form.model })
    if (r.connected) ElMessage.success(`连接成功（${r.method}，耗时 ${r.latencyMs}ms）`)
    else ElMessage.error(r.message || '连接失败')
  } catch (e) {
    ElMessage.error(e.message || '测试失败')
  } finally {
    testing.value = false
  }
}

/**
 * 时间格式化（后端返回 yyyy-MM-dd HH:mm:ss）。
 *
 * @param {string} v 原始时间字符串
 * @returns {string} 格式化时间
 */
function formatTime(v) {
  return v ? String(v).replace('T', ' ').slice(0, 19) : '—'
}

onMounted(loadList)
</script>

<style scoped>
.model-api-view { max-width: 1200px; margin: 0 auto; }
/* 内嵌到设置弹窗：去掉页面级最大宽度限制、压缩头部与表格卡片内边距 */
.model-api-view.embedded { max-width: none; }
.model-api-view.embedded .page-header { margin-bottom: 12px; }
.model-api-view.embedded .page-title { font-size: 1.05rem; }
.model-api-view.embedded .table-card { padding: 4px; }
.page-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20px; }
.page-title { font-size: 1.3rem; font-weight: 700; color: var(--text-primary); }
.page-desc { font-size: 0.85rem; color: var(--text-secondary); margin-top: 4px; max-width: 760px; line-height: 1.6; }
.table-card { background: var(--bg-layer-1); border-radius: var(--radius-lg); border: 1px solid var(--border-light); padding: 8px; }
.api-name { font-weight: 600; color: var(--text-primary); }
.default-tag { margin-left: 6px; }
.muted { color: var(--text-placeholder); font-size: 0.78rem; }
.form-hint { margin-left: 10px; }
.emb-model { font-size: 0.82rem; color: var(--text-primary); }
.purpose-hint { margin-top: 4px; line-height: 1.5; }
</style>
