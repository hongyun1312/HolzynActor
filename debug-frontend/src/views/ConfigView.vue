<template>
  <div class="config">
    <h2 class="page-title">配置查看（只读）</h2>

    <el-card shadow="never" class="block-card">
      <template #header><div class="card-title">模型 API 配置（Key 已脱敏）</div></template>
      <el-table :data="providers" size="small" stripe>
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column prop="baseUrl" label="Base URL" min-width="220" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono">{{ row.baseUrl }}</span></template>
        </el-table-column>
        <el-table-column prop="model" label="模型" min-width="140" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono">{{ row.model || '—' }}</span></template>
        </el-table-column>
        <el-table-column prop="purpose" label="用途" width="90">
          <template #default="{ row }">{{ purposeLabel(row.purpose) }}</template>
        </el-table-column>
        <el-table-column prop="embeddingModel" label="Embedding 模型" min-width="120">
          <template #default="{ row }"><span class="mono">{{ row.embeddingModel || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="Key" width="110">
          <template #default="{ row }"><span class="mono mask">{{ row.apiKeyMasked }}</span></template>
        </el-table-column>
        <el-table-column label="默认 / 启用" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.isDefault === 1" size="small" type="warning">默认</el-tag>
            <el-tag :type="row.enabled === 1 ? 'success' : 'danger'" size="small" style="margin-left:4px">
              {{ row.enabled === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="范围" width="90">
          <template #default="{ row }">{{ row.projectId ? '项目级' : '用户级' }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="block-card">
      <template #header><div class="card-title">Prompt 模板（{{ templates.length }}）</div></template>
      <el-table :data="templates" size="small" stripe>
        <el-table-column prop="code" label="编码" min-width="170">
          <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column label="System 提示词" width="110">
          <template #default="{ row }">
            <el-tag :type="row.hasSystemMessage ? 'success' : 'info'" size="small">
              {{ row.hasSystemMessage ? '有' : '无' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="70" />
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled === 1 ? 'success' : 'danger'" size="small">{{ row.enabled === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="覆盖情况" width="110">
          <template #default="{ row }">{{ scopeLabel(row.scope) }}</template>
        </el-table-column>
        <el-table-column label="正文长度" width="90">
          <template #default="{ row }"><span class="mono">{{ row.templateLength }}</span></template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { get } from '../api'

const providers = ref([])
const templates = ref([])

function purposeLabel(p) {
  return { chat: '主 AI 对话', embedding: '向量化专用', both: '两者兼用' }[p] || p || '—'
}

function scopeLabel(s) {
  return { project: '项目级覆盖', user: '用户覆盖', builtin: '内置' }[s] || s || '—'
}

async function load() {
  try {
    const r = await get('/debug/config')
    providers.value = r.providers || []
    templates.value = r.promptTemplates || []
  } catch (e) {
    console.error('配置加载失败', e)
  }
}

onMounted(load)
</script>

<style scoped>
.page-title {
  margin: 0 0 14px;
  font-size: 18px;
  color: var(--text-primary);
}
.block-card {
  margin-bottom: 14px;
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lv1);
}
.card-title {
  font-weight: 600;
  font-size: 13px;
  color: var(--text-primary);
}
.mask {
  letter-spacing: 1px;
  color: var(--text-tertiary);
}
</style>
