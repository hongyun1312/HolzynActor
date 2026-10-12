<template>
  <div class="tuning-view">
    <div class="head-row">
      <h2 class="page-title">NPC 状态调参（M4）</h2>
      <div class="head-controls">
        <el-button size="small" type="danger" plain :loading="resetAllLoading" :disabled="!anyOverridden" @click="onResetAll">
          一键全部恢复默认{{ anyOverridden ? `（${overriddenCount} 项）` : '' }}
        </el-button>
        <el-button size="small" type="primary" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <el-alert type="info" :closable="false" class="hint-bar"
      title="衰减率/阈值/恢复量等全量参数运行时调参：保存后写入 actor_setting 覆盖表并立即生效（生效值 = 覆盖值 ∪ 默认值，覆盖优先）；重启后仍保留。已覆盖参数显示「已覆盖」标签，可单键恢复默认。" />

    <el-card v-if="rows.length" shadow="never" class="block-card">
      <template #header>
        <div class="card-title">全量可调参数（{{ rows.length }} 项 · {{ overriddenCount }} 项已覆盖）</div>
      </template>

      <el-collapse v-model="activeGroups">
        <el-collapse-item v-for="g in groups" :key="g" :name="g">
          <template #title>
            <span class="group-title">{{ groupLabel(g) }}</span>
            <span v-if="groupCount(g)" class="group-count dim">（{{ groupCount(g) }} 项覆盖）</span>
          </template>
          <el-table :data="rows.filter(r => r.group === g)" size="small" stripe>
            <el-table-column label="参数键" min-width="230">
              <template #default="{ row }">
                <span class="mono">{{ row.key }}</span>
              </template>
            </el-table-column>
            <el-table-column label="名称" min-width="170">
              <template #default="{ row }">{{ row.label }}</template>
            </el-table-column>
            <el-table-column label="类型" width="80">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{ row.type }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="当前生效值" min-width="130">
              <template #default="{ row }">
                <el-input v-model="row.value" size="small" :class="{ 'input-overridden': row.overridden }"
                  :placeholder="row.defaultValue" @change="onChange(row)" />
              </template>
            </el-table-column>
            <el-table-column label="默认值" width="110">
              <template #default="{ row }"><span class="mono dim">{{ row.defaultValue }}</span></template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.overridden" size="small" type="warning">已覆盖</el-tag>
                <span v-else class="dim">默认</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120" align="center">
              <template #default="{ row }">
                <el-button v-if="row.overridden" size="small" text type="danger" :loading="row.saving" @click="onReset(row)">恢复默认</el-button>
                <el-button v-else size="small" text type="primary" :loading="row.saving" :disabled="!row.dirty" @click="onSave(row)">保存</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-collapse-item>
      </el-collapse>
    </el-card>
    <el-card v-else shadow="never" class="block-card"><div class="side-empty">暂无可调参数（后端未就绪？）</div></el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { get, put, del } from '../api'

const rows = ref([])
const loading = ref(false)
const resetAllLoading = ref(false)
const activeGroups = ref([])

const GROUP_LABELS = {
  decay: '衰减率 decay', urgency: '紧急阈值 urgency', health: '健康联动 health',
  recovery: '行动恢复 recovery', emotion: '情绪模型 emotion', inject: '状态注入 inject',
  drive: '分层驱动 drive', history: '历史快照 history', misc: '其他 misc'
}

const groups = computed(() => [...new Set(rows.value.map(r => r.group))])

function groupLabel(g) { return GROUP_LABELS[g] || g }
function groupCount(g) { return rows.value.filter(r => r.group === g && r.overridden).length }
const anyOverridden = computed(() => rows.value.some(r => r.overridden))
const overriddenCount = computed(() => rows.value.filter(r => r.overridden).length)

async function load() {
  loading.value = true
  try {
    const r = await get('/npc-state-config')
    rows.value = (r?.data?.rows || []).map(x => ({
      ...x,
      value: x.value ?? x.defaultValue ?? '',
      dirty: false,
      saving: false
    }))
    // 分组展开：默认展开有覆盖的分组
    activeGroups.value = [...new Set(rows.value.filter(x => x.overridden).map(x => x.group))]
  } catch (e) {
    console.error('调参配置加载失败', e)
  } finally {
    loading.value = false
  }
}

function onChange(row) {
  row.dirty = row.value !== (row.defaultValue ?? '')
}

async function onSave(row) {
  row.saving = true
  try {
    const r = await put(`/npc-state-config/${encodeURIComponent(row.key)}`, { value: row.value })
    row.value = r?.data?.value ?? row.value
    row.overridden = true
    row.dirty = false
  } catch (e) {
    console.error('保存失败', e)
    row.dirty = true
    alert(e.message || '保存失败')
  } finally {
    row.saving = false
  }
}

async function onReset(row) {
  row.saving = true
  try {
    const r = await del(`/npc-state-config/${encodeURIComponent(row.key)}`)
    row.value = r?.data?.value ?? row.defaultValue
    row.overridden = false
    row.dirty = false
  } catch (e) {
    console.error('重置失败', e)
    alert(e.message || '重置失败')
  } finally {
    row.saving = false
  }
}

async function onResetAll() {
  if (!confirm('确认一键全部恢复默认？将清除全部 npc-state 覆盖。')) return
  resetAllLoading.value = true
  try {
    const r = await del('/npc-state-config')
    await load()
    alert(`已恢复默认（清除 ${r?.data?.reset ?? 0} 项覆盖）`)
  } catch (e) {
    console.error('全量重置失败', e)
    alert(e.message || '全量重置失败')
  } finally {
    resetAllLoading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page-title { margin: 0 0 14px; font-size: 18px; color: var(--text-primary); }
.head-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.head-controls { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.hint-bar { margin-bottom: 12px; }
.block-card { margin-bottom: 14px; background: var(--bg-layer-1); border: 1px solid var(--border-l1); border-radius: var(--radius-lg); box-shadow: var(--shadow-lv1); }
.card-title { font-weight: 600; font-size: 13px; color: var(--text-primary); }
.group-title { font-weight: 600; font-size: 13px; }
.group-count { margin-left: 8px; font-weight: 400; font-size: 12px; }
.dim { color: var(--text-tertiary); }
.mono { font-family: var(--font-mono, monospace); font-size: 12px; }
.side-empty { padding: 14px 0; color: var(--text-tertiary); font-size: 12px; }
.input-overridden :deep(.el-input__inner) { color: #e6a23c; font-weight: 600; }
</style>
