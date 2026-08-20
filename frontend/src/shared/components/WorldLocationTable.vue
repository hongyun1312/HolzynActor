<template>
  <div class="world-location-table">
    <!-- 工具行：统计 + AI 提取 + 修改/保存 -->
    <div class="loc-toolbar">
      <el-tag size="small" type="info" effect="plain">共 {{ list.length }} 个地点</el-tag>
      <span v-if="extracting" class="loc-extracting"><el-icon class="is-loading"><HIcon name="Loading" /></el-icon>AI 提取中… 已生成 {{ extractCount }} 个</span>
      <div class="loc-toolbar-right">
        <!-- AI 提取：仅持久化模式（有 projectId）可用，基于已存地理文本识别并合并 -->
        <el-button v-if="extractable && !editing" size="small" :loading="extracting" @click="doExtract">
          <el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;AI 提取
        </el-button>
        <!-- 修改 → 进入增删改查编辑模式 -->
        <el-button v-if="editable && !editing" size="small" type="primary" plain @click="startEdit">修改</el-button>
        <template v-if="editing">
          <el-button size="small" type="primary" :loading="saving" @click="saveEdit">保存</el-button>
          <el-button size="small" @click="cancelEdit">取消</el-button>
        </template>
      </div>
    </div>

    <!-- 地点表格：只读 / 编辑两种形态 -->
    <el-table v-if="list.length > 0" :data="list" border size="small" style="width: 100%">
      <el-table-column label="地点名称" min-width="130">
        <template #default="{ row }">
          <el-input v-if="editing" v-model="row.name" placeholder="地点名称（必填）" maxlength="100" />
          <span v-else class="loc-name">{{ row.name || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="110">
        <template #default="{ row }">
          <el-input v-if="editing" v-model="row.type" placeholder="城市/酒馆…" maxlength="50" />
          <el-tag v-else size="small" effect="plain" type="info">{{ row.type || '—' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="详细简介" min-width="280">
        <template #default="{ row }">
          <el-input v-if="editing" v-model="row.intro" type="textarea" :rows="2" maxlength="2000" placeholder="描述位置/风貌/功能/与世界观的关联" />
          <span v-else class="loc-intro">{{ row.intro || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="重要度" width="90" align="center">
        <template #default="{ row }">
          <el-input-number v-if="editing" v-model="row.importance" :min="1" :max="5" size="small" controls-position="right" style="width: 82px" />
          <el-tag v-else size="small" :type="impType(row.importance)">{{ row.importance ?? 3 }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column v-if="editing" label="操作" width="90" align="center">
        <template #default="{ $index }">
          <el-button size="small" text type="danger" title="删除该地点" @click="removeRow($index)">
            <el-icon><HIcon name="Delete" /></el-icon>
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 空态 -->
    <div v-if="list.length === 0 && !editing" class="loc-empty">
      <el-empty description="暂无地点（AI 提取 或 手动添加）" :image-size="60">
        <el-button v-if="editable" size="small" type="primary" plain @click="startEdit">手动添加</el-button>
        <el-button v-if="extractable" size="small" :loading="extracting" @click="doExtract">AI 提取</el-button>
      </el-empty>
    </div>

    <!-- 编辑模式：新增行 -->
    <div v-if="editing" class="loc-add">
      <el-button size="small" @click="addRow"><el-icon><HIcon name="Plus" /></el-icon>&nbsp;新增地点</el-button>
      <span class="loc-hint">保存后按当前顺序入库；空名称行会被跳过。</span>
    </div>
  </div>
</template>

<script setup>
/**
 * 世界观地点表组件（可复用：世界详情「地点详情」/ 修改世界观页 / 新建项目解析预览）。
 * <p>双模式：</p>
 * <ul>
 *   <li><b>持久化模式</b>（传 projectId）：挂载时从 /world-locations 加载；「修改」进入编辑，
 *       保存走 batch（全量替换）、删除行即从列表移除；「AI 提取」基于已存地理文本识别并合并追加。</li>
 *   <li><b>草稿模式</b>（不传 projectId，新建项目预览用）：以 v-model 双向绑定外部列表，
 *       编辑直接改本地数组并同步父级，不调后端。</li>
 * </ul>
 * <p>数据来源：/api/projects/{id}/world-locations（项目级，随 .holzyn 导入导出）。</p>
 */
import { ref, watch, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  fetchWorldLocations, batchSaveWorldLocations
} from '@/shared/api'

const props = defineProps({
  /** 项目 ID：传入=持久化模式；不传=草稿模式（v-model 双向） */
  projectId: { type: [String, Number], default: null },
  /** 草稿模式初始/外部列表（持久化模式忽略） */
  modelValue: { type: Array, default: () => [] },
  /** 是否提供「修改」入口（进入增删改查） */
  editable: { type: Boolean, default: true },
  /** 是否提供「AI 提取」入口（仅持久化模式生效） */
  extractable: { type: Boolean, default: true }
})
const emit = defineEmits(['update:modelValue'])

const list = ref([])
const editing = ref(false)
const saving = ref(false)
const extracting = ref(false)
const extractCount = ref(0) // 本次流式提取已生成的条数（前端逐条显示）

/** 草稿模式：外部列表变化时同步本地副本 */
watch(() => props.modelValue, (v) => {
  if (!props.projectId) list.value = (v || []).map(r => ({ ...r }))
}, { immediate: true, deep: true })

/** 持久化模式：挂载加载 */
async function load() {
  if (!props.projectId) return
  try {
    list.value = await fetchWorldLocations(props.projectId)
  } catch (e) { ElMessage.error(e.message || '地点加载失败') }
}

/** 草稿模式：同步父级 v-model */
function syncParent() {
  if (!props.projectId) emit('update:modelValue', list.value.map(r => ({ ...r })))
}

/** 进入编辑模式 */
function startEdit() { editing.value = true }

/** 取消编辑：持久化模式回读数据库，草稿模式回退到父级初始 */
function cancelEdit() {
  editing.value = false
  if (props.projectId) load()
  else list.value = (props.modelValue || []).map(r => ({ ...r }))
}

/** 新增一行 */
function addRow() {
  list.value.push({ name: '', type: '', intro: '', importance: 3 })
  syncParent()
}

/** 删除一行 */
function removeRow(i) {
  list.value.splice(i, 1)
  syncParent()
}

/** 保存：持久化模式 batch 全量替换；草稿模式仅过滤空名称行并同步 */
async function saveEdit() {
  const items = list.value.filter(r => r.name && String(r.name).trim())
  if (props.projectId) {
    saving.value = true
    try {
      list.value = await batchSaveWorldLocations(props.projectId, items)
      ElMessage.success(`地点已保存（${items.length} 条）`)
    } catch (e) { ElMessage.error(e.message || '保存失败') }
    finally { saving.value = false }
  } else {
    list.value = items
    syncParent()
  }
  editing.value = false
}

/**
 * AI 提取（持久化模式，SSE 流式）：基于已存地理文本流式识别地点，
 * 每提取完一个立即追加到列表（前端逐条显示），完成后与现有合并（重复跳过）。
 * 事件：start / location(每条) / done(added,total) / error。
 */
async function doExtract() {
  if (!props.projectId) return
  extracting.value = true
  extractCount.value = 0
  try {
    const res = await fetch(`/api/projects/${props.projectId}/world-locations/extract/stream`, { method: 'POST' })
    if (!res.ok || !res.body) {
      let msg = 'AI 提取失败'
      try { msg = (await res.json())?.message || msg } catch (_) { /* 忽略 */ }
      throw new Error(msg)
    }
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let finished = false
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const parts = buffer.split('\n\n')
      buffer = parts.pop() || ''
      for (const part of parts) {
        let event = ''
        let data = ''
        for (const line of part.split('\n')) {
          if (line.startsWith('event:')) event = line.slice(6).trim()
          else if (line.startsWith('data:')) data += line.slice(5).trim()
        }
        if (event === 'location') {
          try {
            const loc = JSON.parse(data)
            // 逐条追加：已有地点在前，新提取的按生成顺序排列（后端已按名称去重）
            list.value = [...list.value, { name: loc.name, type: loc.type, intro: loc.intro, importance: loc.importance }]
            extractCount.value++
          } catch (_) { /* 忽略单条解析失败 */ }
        } else if (event === 'done') {
          finished = true
          try {
            const d = JSON.parse(data)
            ElMessage.success(`AI 提取完成：提取 ${d.total} 个，新增 ${d.added} 个地点`)
          } catch (_) { ElMessage.success('AI 提取完成') }
        } else if (event === 'error') {
          let msg = 'AI 提取失败'
          try { msg = JSON.parse(data)?.message || msg } catch (_) { /* 忽略 */ }
          throw new Error(msg)
        }
      }
      if (finished) break
    }
    if (!finished && extractCount.value === 0) ElMessage.info('提取结束（未识别到新地点）')
  } catch (e) {
    ElMessage.error(e.message || 'AI 提取失败')
    // 失败时回读一次数据库，避免界面残留半截数据
    if (props.projectId) await load()
  } finally {
    extracting.value = false
  }
}

/** 重要度标签颜色 */
function impType(v) { return v >= 4 ? 'danger' : (v >= 3 ? 'warning' : 'info') }

onMounted(load)
</script>

<style scoped>
.world-location-table { width: 100%; }
.loc-toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.loc-toolbar-right { display: flex; align-items: center; gap: 8px; }
.loc-extracting { display: inline-flex; align-items: center; gap: 4px; font-size: 0.78rem; color: var(--text-secondary); }
.loc-name { font-weight: 600; color: var(--text-primary); }
.loc-intro { font-size: 0.82rem; color: var(--text-regular); line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.loc-empty { padding: 4px 0; }
.loc-add { margin-top: 10px; display: flex; align-items: center; gap: 10px; }
.loc-hint { font-size: 0.75rem; color: var(--text-secondary); }
</style>
