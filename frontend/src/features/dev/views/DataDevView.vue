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
  <PageHeader
    title="数据"
    desc="左边是系统库（账户/设置/供应商等全局数据），右边是某个存档自己的世界库。两者互不相干，只读。"
  />

  <div v-if="error" class="dev-notice is-error">
    <el-icon class="dev-notice-icon"><HIcon name="Warning" /></el-icon>
    <div>{{ error }}</div>
  </div>

  <PageTabs v-model="tab">
    <!-- ① 表清单：两库并列，各自独立 -->
    <el-tab-pane label="表清单" name="tables" :lazy="true">
      <div class="db-split">
        <!-- 系统库（目录库） -->
        <DevCard title="系统库（应用）" :desc="systemDesc" flush>
          <template #actions>
            <el-input v-model="systemKeyword" size="small" placeholder="搜索表名" clearable style="width: 150px">
              <template #prefix><el-icon><HIcon name="Search" /></el-icon></template>
            </el-input>
            <el-button size="small" :loading="loading.tables" @click="reloadAll">刷新</el-button>
          </template>
          <el-table
            :data="filteredSystem"
            class="dev-table"
            size="small"
            max-height="430"
            highlight-current-row
            empty-text="该表没有数据"
            @row-click="(row) => openTable(row, 'system')"
          >
            <el-table-column label="表名" min-width="180">
              <template #default="{ row }"><span class="dev-mono">{{ row.table }}</span></template>
            </el-table-column>
            <el-table-column label="行数" width="86" align="right">
              <template #default="{ row }">
                <span class="dev-mono">{{ row.rows >= 0 ? row.rows : '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="列" width="56" align="right">
              <template #default="{ row }">
                <span class="dev-mono">{{ row.columns >= 0 ? row.columns : '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="66" align="right">
              <template #default="{ row }">
                <el-button link size="small" type="primary" @click.stop="openTable(row, 'system')">浏览</el-button>
              </template>
            </el-table-column>
          </el-table>
        </DevCard>

        <!-- 存档库（世界库） -->
        <DevCard title="存档库（世界）" :desc="archiveDesc" flush>
          <template #actions>
            <el-input v-model="archiveKeyword" size="small" placeholder="搜表" clearable style="width: 112px">
              <template #prefix><el-icon><HIcon name="Search" /></el-icon></template>
            </el-input>
            <el-select
              v-model="worldId"
              size="small"
              filterable
              placeholder="选择存档"
              style="width: 168px"
              @change="onWorldChange"
            >
              <el-option v-for="a in archives" :key="a.worldId" :value="a.worldId" :label="archiveLabel(a)">
                <span class="db-opt">
                  <span class="db-opt-name">{{ a.name }}</span>
                  <el-tag v-if="a.mounted" size="small" type="success" effect="plain">已挂载</el-tag>
                  <span class="db-opt-size">{{ formatBytes(a.sizeBytes) }}</span>
                </span>
              </el-option>
            </el-select>
            <el-button size="small" :loading="loading.tables" :disabled="!worldId" @click="reloadAll">刷新</el-button>
          </template>
          <el-table
            :data="filteredArchive"
            class="dev-table"
            size="small"
            max-height="430"
            highlight-current-row
            empty-text="未选择存档，或该存档没有可浏览的表"
            @row-click="(row) => openTable(row, 'world')"
          >
            <el-table-column label="表名" min-width="180">
              <template #default="{ row }"><span class="dev-mono">{{ row.table }}</span></template>
            </el-table-column>
            <el-table-column label="行数" width="86" align="right">
              <template #default="{ row }">
                <span class="dev-mono">{{ row.rows >= 0 ? row.rows : '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="列" width="56" align="right">
              <template #default="{ row }">
                <span class="dev-mono">{{ row.columns >= 0 ? row.columns : '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="66" align="right">
              <template #default="{ row }">
                <el-button link size="small" type="primary" @click.stop="openTable(row, 'world')">浏览</el-button>
              </template>
            </el-table-column>
          </el-table>
        </DevCard>
      </div>

      <div class="db-hint">
        行数为 <span class="dev-mono">—</span> 表示统计失败（表可能刚被迁移脚本创建，或该库没有这张表）。
        系统库只有一个（<span class="dev-mono">{{ systemDbPath || 'holzyn-registry' }}</span>）；
        每个存档各自一套世界库，互不影响。
        <span v-if="loadedAt" class="db-hint-time">数据于 {{ loadedAt }}</span>
      </div>
    </el-tab-pane>

    <!-- ② 表浏览 -->
    <el-tab-pane label="表浏览" name="browse" :lazy="true">
      <DevCard
        :title="currentTable ? `浏览：${currentTable}` : '浏览：未选择表'"
        :desc="browseDesc"
        flush
      >
        <template #actions>
          <el-tag size="small" :type="browseScope === 'system' ? 'info' : 'success'" effect="plain">
            {{ browseScope === 'system' ? '系统库' : '存档库' }}
          </el-tag>
          <el-select
            v-if="browseScope === 'world'"
            v-model="browseWorldId"
            size="small"
            filterable
            placeholder="存档"
            style="width: 170px"
            @change="onBrowseWorldChange"
          >
            <el-option v-for="a in archives" :key="a.worldId" :value="a.worldId" :label="a.name" />
          </el-select>
          <el-select v-model="currentTable" size="small" filterable placeholder="选择表" style="width: 210px" @change="onTableChange">
            <el-option v-for="t in browseTableOptions" :key="t.table" :label="t.table" :value="t.table" />
          </el-select>
          <el-input v-model="browseKeyword" size="small" placeholder="全列模糊搜索" clearable style="width: 160px" @keyup.enter="reloadBrowse" />
          <el-button size="small" :disabled="!currentTable" @click="reloadBrowse">查询</el-button>
          <el-button size="small" :disabled="!browse.items.length" @click="exportCsv">导出 CSV</el-button>
          <el-button size="small" :disabled="!browse.items.length" @click="exportJson">导出 JSON</el-button>
        </template>

        <el-table v-if="currentTable" :data="browse.items" class="dev-table" size="small" max-height="460" empty-text="该表没有数据">
          <el-table-column
            v-for="col in browse.columns"
            :key="col"
            :label="col"
            :min-width="colWidth(col)"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <span :class="{ 'dev-mono': isLong(row[col]) }">{{ cellText(row[col]) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="未选择表" :image-size="64" />

        <div class="dev-toolbar dev-tab-pad dev-mt">
          <span class="dev-toolbar-note">
            共 <b class="dev-mono">{{ browse.total }}</b> 行 · 第 <b class="dev-mono">{{ browsePage }}</b>/<b class="dev-mono">{{ browsePages }}</b> 页
          </span>
          <el-select v-model="browseSize" size="small" style="width: 108px" @change="onBrowseSizeChange">
            <el-option :value="20" label="20 行/页" />
            <el-option :value="50" label="50 行/页" />
            <el-option :value="100" label="100 行/页" />
          </el-select>
          <el-pagination
            v-model:current-page="browsePage"
            :page-size="browseSize"
            :total="browse.total"
            layout="prev, pager, next"
            size="small"
            @current-change="reloadBrowse"
          />
          <span class="dev-toolbar-spacer"></span>
          <span class="dev-toolbar-note">导出只包含当前页数据（服务端未提供全量导出接口）</span>
        </div>
      </DevCard>
    </el-tab-pane>

    <!-- ③ 存档目录结构（存档形态：库 + 清单 + 资源目录） -->
    <el-tab-pane label="目录结构" name="files" :lazy="true">
      <DevCard title="存档目录结构" :desc="filesDesc" flush>
        <template #actions>
          <el-select v-model="worldId" size="small" filterable placeholder="选择存档" style="width: 190px" @change="onWorldChange">
            <el-option v-for="a in archives" :key="a.worldId" :value="a.worldId" :label="archiveLabel(a)" />
          </el-select>
          <el-button size="small" :loading="loading.files" :disabled="!worldId" @click="loadFiles">刷新</el-button>
        </template>
        <el-table :data="files.entries" class="dev-table" size="small" max-height="440" empty-text="目录不存在或该存档没有资源文件">
          <el-table-column label="路径" min-width="320">
            <template #default="{ row }">
              <span class="dev-mono db-tree" :style="{ paddingLeft: `${(row.depth - 1) * 16}px` }">
                <el-icon class="db-tree-icon"><HIcon :name="row.dir ? 'FolderOpened' : 'Document'" /></el-icon>
                {{ row.path }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="80">
            <template #default="{ row }">{{ row.dir ? '目录' : '文件' }}</template>
          </el-table-column>
          <el-table-column label="大小" width="110" align="right">
            <template #default="{ row }">
              <span class="dev-mono">{{ row.dir ? '—' : formatBytes(row.sizeBytes) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </DevCard>
    </el-tab-pane>

    <!-- ④ 迁移历史 -->
    <el-tab-pane label="迁移历史" name="schema" :lazy="true">
      <DevCard title="Schema 版本" desc="Flyway 是 schema 的唯一权威（ddl-auto=none）。版本不符通常意味着该库是旧版本启动的。">
        <DevAuto v-if="schema" :value="schema" />
        <div v-else class="dev-toolbar-note">未返回 schema 状态</div>
      </DevCard>
    </el-tab-pane>
  </PageTabs>
</template>

<script setup>
/**
 * 开发者控制台 · 数据域。
 *
 * <p><b>为什么按「库」分开</b>：本项目的存储是分档的——目录库（{@code holzyn-registry}）
 * 存应用级数据，每个存档各有独立的世界库（{@code worlds/<uid>/world.mv.db}）。
 * 旧页面对 {@code /api/debug/db/*} 只发一次请求，查哪个库取决于「此刻有没有挂载世界」，
 * 于是同一页在挂载前后显示的表清单完全不同，排查时根本分不清「这是系统的数据还是世界的数据」。</p>
 *
 * <p>现在两侧各查各的：左系统库固定走目录库，右存档库显式带 worldId；
 * 未挂载的存档由后端以只读连接打开，不会建库也不会跑迁移。</p>
 *
 * <p>只读是硬约束：所有写操作都必须经过领域服务（AGENTS §8.1.4「单一写入口」），
 * 一个能直接 UPDATE 的调试页会在一天之内制造出「世界数据对不上」的事故。</p>
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/shared/layout/PageHeader.vue'
import PageTabs from '@/shared/layout/PageTabs.vue'
import HIcon from '@/shared/components/HIcon.vue'
import DevCard from '../components/DevCard.vue'
import DevAuto from '../components/DevAuto.vue'
import {
  fetchDevDbScopes, fetchDevDbTables, fetchDevDbTable, fetchDevDbFiles,
  fetchSystemSchema, appendDevAudit
} from '@/shared/api/dev'
import { devContext, devOperator } from '../devContext'

const tab = ref('tables')
const error = ref('')
const loadedAt = ref('')

const scopes = ref(null)
const archives = computed(() => scopes.value?.archives || [])
const systemDbPath = computed(() => scopes.value?.system?.dbPath || '')

const worldId = ref(null)
const systemKeyword = ref('')
const archiveKeyword = ref('')

const systemTables = ref([])
const archiveTables = ref([])
const schema = ref(null)

const loading = ref({ tables: false, files: false })

const browseScope = ref('system')
const browseWorldId = ref(null)
const currentTable = ref('')
const browseKeyword = ref('')
const browsePage = ref(1)
const browseSize = ref(50)
const browse = ref({ table: '', page: 1, size: 50, total: 0, columns: [], items: [] })

const files = ref({ entries: [], dirPath: '', dbPath: '', sizeBytes: null, dirPresent: false })

/* ---------------- 描述文案 ---------------- */

const currentArchive = computed(() => archives.value.find((a) => a.worldId === worldId.value) || null)

const systemDesc = computed(() => {
  const s = scopes.value?.system
  if (!s) return '读取中…'
  return `账户 / 设置 / 供应商 / Prompt / 用量等全局数据 · ${s.tableCount} 张表${s.exists ? '' : ' · 库文件缺失'}`
})

const archiveDesc = computed(() => {
  const a = currentArchive.value
  if (!a) return '未选择存档'
  const parts = [`${archiveTables.value.length} 张表`, a.statusLabel || a.status || '']
  if (!a.dbPresent) parts.push('世界库缺失')
  if (a.lockPresent) parts.push('lock 文件残留')
  parts.push(formatBytes(a.sizeBytes))
  return parts.filter(Boolean).join(' · ')
})

const browseDesc = computed(() => {
  if (!currentTable.value) return '先在「表清单」里点一张表；或在这里用下拉选择。'
  const lib = browseScope.value === 'system' ? '系统库' : '存档库'
  return `${lib} · 共 ${browse.value.total} 行（第 ${browse.value.page}/${browsePages.value} 页，每页 ${browseSize.value} 行）。仅只读。`
})

const filesDesc = computed(() => {
  if (!worldId.value) return '未选择存档'
  const a = currentArchive.value
  if (!a) return '未选择存档'
  return `${a.dirPath || ''} · ${a.dbPresent ? '世界库存在' : '世界库缺失'} · ${formatBytes(a.sizeBytes)}`
})

const browsePages = computed(() => Math.max(1, Math.ceil((browse.value.total || 0) / (browseSize.value || 1))))

const browseTableOptions = computed(() => (browseScope.value === 'system' ? systemTables.value : archiveTables.value))

const filteredSystem = computed(() => filterByKeyword(systemTables.value, systemKeyword.value))
const filteredArchive = computed(() => filterByKeyword(archiveTables.value, archiveKeyword.value))

/* ---------------- 加载 ---------------- */

/** 表名关键字过滤（前端过滤，避免每次输入都打后端） */
function filterByKeyword(list, keyword) {
  const kw = String(keyword || '').trim().toLowerCase()
  if (!kw) return list
  return list.filter((t) => String(t.table || '').toLowerCase().includes(kw))
}

/** 存档下拉文案 */
function archiveLabel(a) {
  return `${a.name}${a.mounted ? ' · 已挂载' : ''}`
}

/** 首次进入：拉范围清单，默认落到「当前挂载的存档」（没有则第一个） */
async function loadScopes() {
  try {
    scopes.value = await fetchDevDbScopes()
    if (worldId.value == null) {
      const list = archives.value
      const mounted = list.find((a) => a.mounted)
      worldId.value = mounted ? mounted.worldId : (list[0] ? list[0].worldId : null)
    }
    if (browseWorldId.value == null) browseWorldId.value = worldId.value
  } catch (e) {
    error.value = e?.message || '读取库范围失败'
  }
}

/** 拉两侧表清单（互不阻断：一侧失败另一侧照常显示） */
async function loadTables() {
  loading.value.tables = true
  error.value = ''
  try {
    const [sys, world] = await Promise.all([
      fetchDevDbTables({ scope: 'system' }).catch((e) => { error.value = e?.message || '读取系统库失败'; return [] }),
      worldId.value
        ? fetchDevDbTables({ scope: 'world', worldId: worldId.value }).catch((e) => { error.value = e?.message || '读取存档库失败'; return [] })
        : Promise.resolve([])
    ])
    systemTables.value = Array.isArray(sys) ? sys : []
    archiveTables.value = Array.isArray(world) ? world : []
    loadedAt.value = nowText()
  } finally {
    loading.value.tables = false
  }
}

/** 整页刷新（范围 + 两侧表 + 当前浏览页） */
async function reloadAll() {
  await loadScopes()
  await loadTables()
  if (currentTable.value) await reloadBrowse()
}

/** 切换存档：表清单、目录结构、当前浏览（若正在看存档库）一起换 */
async function onWorldChange() {
  archiveKeyword.value = ''
  await loadTables()
  if (currentTable.value && browseScope.value === 'world') {
    browseWorldId.value = worldId.value
    currentTable.value = ''
    browse.value = { table: '', page: 1, size: browseSize.value, total: 0, columns: [], items: [] }
  }
  if (tab.value === 'files') await loadFiles()
}

/** 浏览页切换存档：清空当前表（不同存档的表可能不存在） */
async function onBrowseWorldChange() {
  currentTable.value = ''
  browseKeyword.value = ''
  browse.value = { table: '', page: 1, size: browseSize.value, total: 0, columns: [], items: [] }
}

/** 点表名 → 切到浏览页并加载 */
function openTable(row, scope) {
  browseScope.value = scope
  browseWorldId.value = scope === 'world' ? worldId.value : null
  currentTable.value = row.table
  browsePage.value = 1
  browseKeyword.value = ''
  tab.value = 'browse'
  reloadBrowse()
}

/** 下拉切换表 */
function onTableChange() {
  browsePage.value = 1
  reloadBrowse()
}

/** 重新拉当前页 */
async function reloadBrowse() {
  if (!currentTable.value) return
  try {
    const res = await fetchDevDbTable(currentTable.value, {
      scope: browseScope.value,
      worldId: browseScope.value === 'world' ? browseWorldId.value : undefined,
      page: browsePage.value,
      size: browseSize.value,
      keyword: browseKeyword.value || undefined
    })
    browse.value = {
      table: res.table || currentTable.value,
      page: res.page || browsePage.value,
      size: res.size || browseSize.value,
      total: res.total || 0,
      columns: sortColumns(res.columns || []),
      items: res.items || []
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '查询失败')
  }
}

/** 每页条数变化：回第一页 */
function onBrowseSizeChange() {
  browsePage.value = 1
  reloadBrowse()
}

/** 读存档目录结构 */
async function loadFiles() {
  if (!worldId.value) return
  loading.value.files = true
  try {
    const res = await fetchDevDbFiles(worldId.value)
    files.value = {
      entries: res.entries || [],
      dirPath: res.dirPath || '',
      dbPath: res.dbPath || '',
      sizeBytes: res.sizeBytes,
      dirPresent: !!res.dirPresent
    }
  } catch (e) {
    ElMessage.error(e?.message || '读取存档目录失败')
  } finally {
    loading.value.files = false
  }
}

/* ---------------- 展示工具 ---------------- */

/** 是否长文本（用等宽字体，便于核对 ID/JSON） */
function isLong(v) { return typeof v === 'string' && v.length > 40 }

/**
 * 列顺序：主键 → 外键 → 普通字段（字母序）→ 时间戳。
 *
 * <p>H2 返回的是建表顺序（比如 actor_model_provider 的第一个列是 embedding_enabled），
 * 调试时最想先看到的 id 被挤到中间、每张表的位置还不一样。这里按语义重排一次：
 * 主键永远在最左，时间戳收在最右，中间按字母序稳定排列。</p>
 *
 * @param {string[]} columns 原始列名
 * @returns {string[]} 重排后的列名
 */
function sortColumns(columns) {
  const weight = (col) => {
    if (col === 'id') return 0
    if (col.endsWith('_id')) return 1
    if (col.endsWith('_at') || col.endsWith('_time')) return 3
    return 2
  }
  return [...columns].sort((a, b) => weight(a) - weight(b) || a.localeCompare(b))
}

/** 单元格文本 */
function cellText(v) {
  if (v === null || v === undefined) return '—'
  if (typeof v === 'object') {
    const s = JSON.stringify(v)
    return s.length > 200 ? s.slice(0, 200) + '…' : s
  }
  return String(v)
}

/** 列宽估算：ID/短字段窄，长字段宽 */
function colWidth(col) {
  if (col === 'id' || col.endsWith('_id')) return 90
  if (col.endsWith('_at') || col.endsWith('time')) return 170
  return 140
}

/** 字节数格式化 */
function formatBytes(n) {
  if (n === null || n === undefined || Number.isNaN(Number(n))) return '—'
  const units = ['B', 'KB', 'MB', 'GB']
  let v = Number(n)
  let i = 0
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i += 1 }
  return `${v >= 100 || i === 0 ? Math.round(v) : v.toFixed(1)} ${units[i]}`
}

/** 当前时间文本（hh:mm:ss） */
function nowText() {
  const d = new Date()
  const p = (v) => String(v).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/* ---------------- 导出 ---------------- */

/** 触发浏览器下载（Blob，不依赖后端导出接口） */
function download(filename, text, mime) {
  const blob = new Blob([text], { type: `${mime};charset=utf-8` })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

/** 导出 CSV（下载动作也留审计：导出数据属于「把数据带出系统」） */
function exportCsv() {
  const cols = browse.value.columns
  const esc = (v) => {
    const s = cellText(v)
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
  }
  const lines = [cols.join(',')].concat(browse.value.items.map((row) => cols.map((c) => esc(row[c])).join(',')))
  download(`${browse.value.table}-p${browsePage.value}.csv`, lines.join('\n'), 'text/csv')
  auditExport('csv')
}

/** 导出 JSON */
function exportJson() {
  download(`${browse.value.table}-p${browsePage.value}.json`,
    JSON.stringify(browse.value.items, null, 2), 'application/json')
  auditExport('json')
}

/** 导出留痕（失败不阻断下载） */
function auditExport(format) {
  appendDevAudit({
    action: 'db.export',
    target: `${browseScope.value}:${browse.value.table}`,
    oldValue: '',
    newValue: `${browse.value.items.length} 行 / ${format}`,
    effect: 'immediate',
    page: 'dev/data',
    operator: devOperator(),
    note: `导出第 ${browsePage.value} 页`
  }).catch(() => {})
}

/* ---------------- 生命周期 ---------------- */

onMounted(async () => {
  await loadScopes()
  await loadTables()
  fetchSystemSchema().then((s) => { schema.value = s }).catch(() => {})
})

// Tab 懒渲染：切到「目录结构」时才真正读目录
watch(tab, async (v) => {
  if (v === 'files' && !files.value.entries.length) await loadFiles()
})

// 顶栏「刷新」：两侧表 + 当前页一起刷新
watch(() => devContext.refreshTick, () => { reloadAll() })
</script>

<style scoped>
/* 两库并列：各占一半，视觉上「一眼看出是两个不同的库」；窄屏堆叠 */
.db-split {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: var(--space-4, 16px);
  align-items: start;
}
@media (max-width: 1100px) {
  .db-split { grid-template-columns: minmax(0, 1fr); }
}
.db-hint {
  margin-top: var(--space-3, 12px);
  padding: 8px 14px;
  border-radius: var(--radius-md, 10px);
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l1);
  font-size: var(--density-font-sm, 0.82rem);
  color: var(--text-tertiary);
  line-height: 1.7;
}
.db-hint-time {
  float: right;
  font-size: var(--density-font-xs, 0.76rem);
  color: var(--text-quaternary, var(--text-tertiary));
}
.db-opt { display: inline-flex; align-items: center; gap: 8px; width: 100%; }
.db-opt-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; }
.db-opt-size { font-size: 0.75rem; color: var(--text-tertiary); }
.db-tree { display: inline-flex; align-items: center; gap: 6px; }
.db-tree-icon { font-size: 13px; color: var(--text-tertiary); }
.dev-mt { margin-top: var(--space-3, 12px); }
.dev-tab-pad { padding: 0 24px var(--density-card-pad, 16px); }
</style>
