<template>
  <div class="db">
    <h2 class="page-title">DB 浏览（只读）</h2>

    <div class="db-body">
      <!-- 左侧表清单 -->
      <div class="table-list">
        <div class="list-title">白名单表（{{ tables.length }}）</div>
        <div v-for="t in tables" :key="t.table" class="table-item" :class="{ active: t.table === current }"
          @click="selectTable(t.table)">
          <span class="t-name mono">{{ t.table }}</span>
          <span class="t-rows mono">{{ t.rows < 0 ? '?' : t.rows }}</span>
        </div>
      </div>

      <!-- 右侧数据区 -->
      <div class="table-data">
        <template v-if="current">
          <div class="data-toolbar">
            <span class="current-name mono">{{ current }}</span>
            <el-input v-model="keyword" size="small" placeholder="关键字过滤（文本列）" clearable style="width: 220px"
              @keyup.enter="loadData(1)" @clear="loadData(1)" />
            <el-button size="small" type="primary" @click="loadData(1)">查询</el-button>
            <span class="total mono">共 {{ total }} 行</span>
          </div>

          <div class="table-scroll">
            <el-table :data="items" size="small" border>
              <el-table-column v-for="col in columns" :key="col" :prop="col" :label="col" min-width="120"
                show-overflow-tooltip>
                <template #default="{ row }">
                  <span class="cell-value">{{ cellText(row[col]) }}</span>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="pager">
            <el-pagination layout="total, prev, pager, next, sizes" :total="total" :page-size="size"
              :current-page="page" :page-sizes="[20, 50, 100]" @current-change="(p) => loadData(p)"
              @size-change="(s) => { size = s; loadData(1) }" />
          </div>
        </template>
        <el-empty v-else description="从左侧选择一张白名单表" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { get } from '../api'

const tables = ref([])
const current = ref('')
const columns = ref([])
const items = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(50)
const keyword = ref('')

async function loadTables() {
  try {
    tables.value = await get('/debug/db/tables')
  } catch (e) {
    ElMessage.error('表清单加载失败：' + e.message)
  }
}

function selectTable(name) {
  current.value = name
  page.value = 1
  keyword.value = ''
  loadData(1)
}

async function loadData(p) {
  if (!current.value) return
  page.value = p
  try {
    const r = await get(`/debug/db/${current.value}`, {
      page: p,
      size: size.value,
      keyword: keyword.value || undefined
    })
    columns.value = r.columns || []
    items.value = r.items || []
    total.value = r.total || 0
  } catch (e) {
    ElMessage.error('数据加载失败：' + e.message)
  }
}

function cellText(v) {
  if (v === null || v === undefined) return '—'
  if (typeof v === 'object') return JSON.stringify(v)
  const s = String(v)
  return s.length > 300 ? s.slice(0, 300) + '…' : s
}

onMounted(loadTables)
</script>

<style scoped>
.page-title {
  margin: 0 0 14px;
  font-size: 18px;
  color: var(--text-primary);
}
.db-body {
  display: flex;
  gap: 14px;
  height: calc(100vh - 150px);
  min-height: 400px;
}
.table-list {
  width: 260px;
  flex-shrink: 0;
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-md);
  padding: 10px;
  overflow-y: auto;
  box-shadow: var(--shadow-lv1);
}
.list-title {
  font-size: 12px;
  color: var(--text-tertiary);
  margin-bottom: 8px;
}
.table-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 7px 10px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: 12px;
  color: var(--text-secondary);
}
.table-item:hover {
  background: var(--bg-hover);
}
.table-item.active {
  background: var(--accent-soft);
  color: var(--accent-text);
  font-weight: 600;
}
.t-rows {
  color: var(--text-tertiary);
  font-size: 11px;
}
.table-data {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.data-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
}
.current-name {
  font-weight: 600;
  font-size: 14px;
  color: var(--text-primary);
}
.total {
  margin-left: auto;
  color: var(--text-tertiary);
  font-size: 12px;
}
.table-scroll {
  flex: 1;
  overflow: auto;
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-md);
  background: var(--bg-layer-1);
  box-shadow: var(--shadow-lv1);
}
.cell-value {
  font-size: 12px;
  font-family: var(--dsw-font-family-code);
}
.pager {
  display: flex;
  justify-content: flex-end;
}
</style>
