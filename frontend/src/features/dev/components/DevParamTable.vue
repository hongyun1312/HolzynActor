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
  <!--
    参数表：每行 = 一个可调参数。

    三列信息不可省略（否则用户不敢改）：
      ① 生效方式（即时 / 需重启）——决定「改完要不要重启后端」；
      ② 默认值——决定「怎么恢复」；
      ③ 危险标记——危险键写入前必须显式勾选确认。
  -->
  <el-table :data="pagedRows" class="dev-table" size="small" max-height="600">
    <el-table-column label="参数" min-width="240">
      <template #default="{ row }">
        <div class="dev-param-name">
          <span class="dev-param-label">{{ row.label || row.key }}</span>
          <el-tag v-if="row.danger" size="small" type="danger" effect="plain" class="dev-param-tag">危险</el-tag>
          <el-tag v-if="row.overridden" size="small" type="warning" effect="plain" class="dev-param-tag">已改</el-tag>
        </div>
        <div class="dev-mono dev-param-key" :title="row.key">{{ row.key }}</div>
      </template>
    </el-table-column>

    <el-table-column label="生效" width="92">
      <template #default="{ row }">
        <el-tag size="small" :type="effectInfo(row.effect).type" effect="plain">{{ effectInfo(row.effect).text }}</el-tag>
      </template>
    </el-table-column>

    <el-table-column label="当前值" width="220">
      <template #default="{ row }">
        <!-- Boolean 参数只能取 true/false：给输入框等于让人手打，打错就是 400。
             后端 simpleTypeName() 对 boolean/Boolean 字段返回 "boolean"，写入侧由
             ConversionService 转成布尔，所以这里存 'true'/'false' 字符串即可。 -->
        <el-select
          v-if="row.writable && isBoolParam(row)"
          v-model="drafts[row.key]"
          size="small"
          class="dev-param-bool"
          :placeholder="String(row.defaultValue ?? '')"
        >
          <el-option label="true" value="true" />
          <el-option label="false" value="false" />
        </el-select>
        <el-input
          v-else-if="row.writable"
          v-model="drafts[row.key]"
          size="small"
          :placeholder="String(row.defaultValue ?? '')"
          @keyup.enter="save(row)"
        />
        <span v-else class="dev-toolbar-note">{{ row.value != null ? row.value : '只读' }}</span>
      </template>
    </el-table-column>

    <el-table-column label="默认值" width="150">
      <template #default="{ row }">
        <span class="dev-mono">{{ row.defaultValue != null && row.defaultValue !== '' ? row.defaultValue : '—' }}</span>
      </template>
    </el-table-column>

    <el-table-column prop="type" label="类型" width="90" show-overflow-tooltip />
    <el-table-column label="来源" width="120" show-overflow-tooltip>
      <template #default="{ row }">
        <span class="dev-toolbar-note">{{ sourceName(row.source) }}</span>
      </template>
    </el-table-column>

    <el-table-column label="操作" width="120" fixed="right">
      <template #default="{ row }">
        <template v-if="row.writable">
          <el-button link size="small" type="primary" @click="save(row)">保存</el-button>
          <el-button link size="small" :disabled="!row.overridden" @click="reset(row)">重置</el-button>
        </template>
        <span v-else class="dev-toolbar-note">只读</span>
      </template>
    </el-table-column>
  </el-table>

  <!-- 分页：参数目录有数百行，一次性建 DOM（每行还有输入框/下拉）会让「进入页面」直接卡住。
       每页 50 行是实测手感：翻页无感，首屏建表 < 50ms。 -->
  <div v-if="total > pageSize" class="dev-param-pager">
    <el-pagination
      v-model:current-page="page"
      :page-size="pageSize"
      :total="total"
      :pager-count="5"
      layout="prev, pager, next, total"
      size="small"
      background
    />
  </div>
</template>

<script setup>
/**
 * 参数表（可写行内编辑 + 危险确认 + 审计）。
 *
 * <p>写路径只有两条 API：PUT /api/dev/params（单键）与 DELETE /api/dev/params（单键重置）。
 * 全量重置在「生效与重启」页单独做，避免误触。</p>
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { saveDevParam, resetDevParam } from '@/shared/api/dev'
import { effectInfo } from '../devNav'
import { devOperator } from '../devContext'

const props = defineProps({
  /** 参数行（后端 ParamRow） */
  rows: { type: Array, default: () => [] },
  /** 审计归属页面标识 */
  page: { type: String, default: 'dev/config' }
})
const emit = defineEmits(['changed'])

/** 每页行数：参数目录可达数百行，分页是「进入不卡」的关键（见模板注释） */
const pageSize = 50
/** 当前页（1 起） */
const page = ref(1)

const total = computed(() => (props.rows || []).length)
const pagedRows = computed(() => {
  const start = (page.value - 1) * pageSize
  return (props.rows || []).slice(start, start + pageSize)
})

/** 行数变化后页码越界 → 收回最后一页（不粗暴跳回第 1 页，保存参数时不会丢失浏览位置） */
watch(total, (n) => {
  const maxPage = Math.max(1, Math.ceil(n / pageSize))
  if (page.value > maxPage) page.value = maxPage
})

/** 是否布尔参数（后端 type 为 "boolean"） */
function isBoolParam(row) {
  return String(row && row.type ? row.type : '').toLowerCase() === 'boolean'
}

/** 草稿归一：布尔统一小写，避免 "TRUE" 之类的大小写对不上下拉选项 */
function normalizeDraft(row, raw) {
  const text = raw == null ? '' : String(raw)
  return isBoolParam(row) ? text.toLowerCase() : text
}

/** 编辑草稿（键 → 输入值）：与后端值分离，保存失败不会污染显示 */
const drafts = reactive({})

watch(() => props.rows, (list) => {
  for (const row of list || []) {
    if (drafts[row.key] === undefined) drafts[row.key] = normalizeDraft(row, row.value)
  }
}, { immediate: true, deep: false })

/** 来源中文名 */
function sourceName(source) {
  if (source === 'bean') return '配置 Bean'
  if (source === 'env') return 'Environment'
  return '系统'
}

/** 保存单键（危险键需二次确认；后端也会再校验 confirm） */
async function save(row) {
  const value = drafts[row.key]
  if (value == null || value === '') {
    ElMessage.warning('值不能为空（恢复默认请点「重置」）')
    return
  }
  let confirm = false
  if (row.danger) {
    try {
      await ElMessageBox.confirm(
        `「${row.label || row.key}」是危险参数：${row.key}\n新值：${value}\n改错可能导致世界数据损坏或后端起不来。确认写入？`,
        '写入危险参数',
        { type: 'warning', confirmButtonText: '确认写入', cancelButtonText: '取消' }
      )
      confirm = true
    } catch (_) {
      return
    }
  }
  try {
    await saveDevParam({ key: row.key, value: String(value), confirm, page: props.page, operator: devOperator() })
    ElMessage.success(row.effect === 'restart' ? '已保存，重启后端后生效' : '已保存并即时生效')
    emit('changed')
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '保存失败')
  }
}

/** 重置单键为默认值 */
async function reset(row) {
  try {
    await ElMessageBox.confirm(
      `把「${row.label || row.key}」恢复默认值？\n当前值：${row.value ?? '—'}\n默认值：${row.defaultValue ?? '—'}`,
      '恢复默认',
      { type: 'warning', confirmButtonText: '恢复', cancelButtonText: '取消' }
    )
  } catch (_) {
    return
  }
  try {
    await resetDevParam({ key: row.key, page: props.page, operator: devOperator() })
    drafts[row.key] = normalizeDraft(row, row.defaultValue)
    ElMessage.success('已恢复默认')
    emit('changed')
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '重置失败')
  }
}
</script>

<style scoped>
.dev-param-name { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; min-width: 0; }
.dev-param-label { font-weight: 600; color: var(--text-primary); }
.dev-param-tag { flex-shrink: 0; }
.dev-param-key { margin-top: 2px; }
/* 布尔下拉与输入框同宽，行高一致（表格不会因为换成下拉而变高） */
.dev-param-bool { width: 100%; }
.dev-param-pager {
  display: flex;
  justify-content: flex-end;
  padding: var(--space-2, 8px) var(--space-4, 16px) 0;
}
</style>
