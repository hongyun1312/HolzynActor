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
    title="配置与调参"
    desc="全部可调参数、已改项、审计与生效方式。"
  >
    <template #actions>
      <el-tag v-if="page.lastAt" size="small" effect="plain">数据于 {{ page.lastAt }}</el-tag>
    </template>
  </PageHeader>

  <div v-if="page.error" class="dev-notice is-error">
    <el-icon class="dev-notice-icon"><HIcon name="Warning" /></el-icon>
    <div>{{ page.error }}</div>
  </div>

  <PageTabs v-model="tab">
    <!-- ① 参数目录 -->
    <el-tab-pane label="参数目录" name="params" :lazy="true">
      <div class="dev-metrics">
        <MetricCard label="参数总数" :value="stats.total != null ? stats.total : '—'" unit="项" hint="配置 Bean + Environment + 只读系统项" />
        <MetricCard label="已改为非默认" :value="stats.overridden != null ? stats.overridden : '—'" unit="项" hint="actor_dev_param 行数" />
        <MetricCard label="待重启生效" :value="stats.pendingRestart != null ? stats.pendingRestart : '—'" unit="项" hint="覆盖文件比进程启动时间新" />
        <MetricCard label="覆盖文件" :value="stats.overrideFile ? '已生成' : '—'" hint="dev-param-overrides.properties（重启时由 EnvironmentPostProcessor 读入）" />
      </div>

      <DevCard title="全部参数" :desc="`按功能域筛选；搜索支持中文标签与键名。共 ${rows.length} 项。`" flush>
        <template #actions>
          <el-select v-model="domain" size="small" placeholder="全部功能域" clearable style="width: 150px" @change="reload">
            <el-option v-for="d in domains" :key="d.domain" :label="`${paramDomainName(d.domain)}（${d.total}）`" :value="d.domain" />
          </el-select>
          <el-input v-model="keyword" size="small" placeholder="搜索参数" clearable style="width: 200px" @keyup.enter="reload">
            <template #prefix><el-icon><HIcon name="Search" /></el-icon></template>
          </el-input>
          <el-button size="small" @click="reload">查询</el-button>
        </template>
        <el-skeleton v-if="paramsLoading" class="dev-param-skeleton" :rows="6" animated />
        <DevParamTable v-else :rows="rows" page="dev/config" @changed="reload" />
      </DevCard>
    </el-tab-pane>

    <!-- ② 已改参数 -->
    <el-tab-pane label="已改参数" name="changed" :lazy="true">
      <DevCard
        :title="`已改参数（${overridden.length}）`"
        desc="非默认值清单。全部恢复默认是破坏性操作，需要输入确认并在审计里留痕。"
        flush
      >
        <template #actions>
          <el-button size="small" type="danger" plain :disabled="!overridden.length" @click="resetAll">全部恢复默认</el-button>
        </template>
        <el-empty v-if="!overridden.length" description="所有参数都是默认值" :image-size="64" />
        <DevParamTable v-else :rows="overridden" page="dev/config" @changed="reload" />
      </DevCard>
    </el-tab-pane>

    <!-- ③ 审计日志 -->
    <el-tab-pane label="审计日志" name="audit" :lazy="true">
      <DevCard :title="`审计（${audit.total} 条）`" desc="谁、什么时候、把哪个参数从什么改成什么。排查「世界怎么突然变了」从这里开始。" flush>
        <template #actions>
          <el-select v-model="auditAction" size="small" placeholder="全部动作" clearable style="width: 150px" @change="reloadAudit">
            <el-option label="参数写入" value="param.save" />
            <el-option label="参数重置" value="param.reset" />
            <el-option label="全部重置" value="param.resetAll" />
            <el-option label="世界暂停" value="world.pause" />
            <el-option label="世界倍率" value="world.rate" />
            <el-option label="数据导出" value="db.export" />
            <el-option label="后端重启" value="system.restart" />
          </el-select>
          <el-input v-model="auditTarget" size="small" placeholder="按目标过滤" clearable style="width: 180px" @keyup.enter="reloadAudit" />
          <el-button size="small" @click="reloadAudit">查询</el-button>
        </template>
        <el-table :data="audit.list" class="dev-table" size="small" max-height="520">
          <el-table-column label="动作" width="130" show-overflow-tooltip>
            <template #default="{ row }">
              <el-tag size="small" :type="auditType(row.action)" effect="plain">{{ row.action }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="目标" min-width="200" show-overflow-tooltip>
            <template #default="{ row }"><span class="dev-mono">{{ row.target }}</span></template>
          </el-table-column>
          <el-table-column label="旧值" width="150" show-overflow-tooltip>
            <template #default="{ row }"><span class="dev-mono">{{ row.oldValue || '—' }}</span></template>
          </el-table-column>
          <el-table-column label="新值" width="150" show-overflow-tooltip>
            <template #default="{ row }"><span class="dev-mono">{{ row.newValue || '—' }}</span></template>
          </el-table-column>
          <el-table-column prop="operator" label="操作人" width="110" show-overflow-tooltip />
          <el-table-column prop="page" label="页面" width="110" show-overflow-tooltip />
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="备注" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.note || '—' }}</template>
          </el-table-column>
        </el-table>
        <div class="dev-toolbar dev-tab-pad dev-mt">
          <el-pagination
            v-model:current-page="auditPage"
            :page-size="auditSize"
            :total="audit.total"
            layout="prev, pager, next, total"
            size="small"
            @current-change="reloadAudit"
          />
        </div>
      </DevCard>
    </el-tab-pane>

    <!-- ④ 生效与重启 -->
    <el-tab-pane label="生效与重启" name="effect" :lazy="true">
      <DevCard
        title="生效方式"
        :desc="`需要重启的参数会写进覆盖文件，由 EnvironmentPostProcessor 在 Spring 启动早期读入，优先级高于 application.yml。`"
      >
        <DevKv :items="effectItems" />
      </DevCard>

      <DevCard
        title="保存并重启后端"
        desc="按顺序执行：世界 paused → 优雅停 JVM → Electron 重新拉起 → 回到本页。重启期间世界不推进，数据不丢。"
        danger
      >
        <template #actions>
          <el-button size="small" type="danger" :loading="restarting" @click="restartBackend">保存并重启后端</el-button>
        </template>
        <div class="dev-notice">
          <el-icon class="dev-notice-icon"><HIcon name="Warning" /></el-icon>
          <div>
            浏览器环境无法重启进程：该按钮只在桌面端（独立窗口）可用。
            当前环境：<b>{{ isDesktopShell() ? '桌面端' : '浏览器' }}</b>。
            浏览器请手动重启后端（<code>mvn spring-boot:run</code> 或重启应用），覆盖文件会在启动时自动生效。
          </div>
        </div>
      </DevCard>

      <DevCard title="最近一次覆盖写入" desc="覆盖文件的最后修改时间与条目数；「待重启」= 文件比进程启动更新。">
        <DevKv :items="pendingItems" />
      </DevCard>
    </el-tab-pane>
  </PageTabs>
</template>

<script setup>
/**
 * 开发者控制台 · 配置与调参域（核心页）。
 *
 * <p>三层信息结构：① 目录（有什么可调）② 已改（我改了什么）③ 审计（谁改的）。
 * 这是「参数可调」与「参数可控」的分界：只给写入能力而不给追溯能力，等于埋雷。</p>
 */
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/shared/layout/PageHeader.vue'
import PageTabs from '@/shared/layout/PageTabs.vue'
import MetricCard from '@/shared/components/MetricCard.vue'
import HIcon from '@/shared/components/HIcon.vue'
import DevCard from '../components/DevCard.vue'
import DevKv from '../components/DevKv.vue'
import DevParamTable from '../components/DevParamTable.vue'
import { fetchDevParams, fetchDevOverriddenParams, fetchDevAudit, resetAllDevParams, appendDevAudit, fetchDevSystem } from '@/shared/api/dev'
import { useDevPage, formatTime } from '../useDevPage'
import { paramDomainName } from '../devNav'
import { devOperator } from '../devContext'
import { isDesktopShell } from '@/shared/composables/useDevMode'

const tab = ref('params')
const domain = ref('')
const keyword = ref('')
const overridden = ref([])
const auditAction = ref('')
const auditTarget = ref('')
const auditPage = ref(1)
const auditSize = ref(20)
const audit = ref({ list: [], total: 0, page: 1, size: 20 })
const restarting = ref(false)
const system = ref(null)

const { state: page, load } = useDevPage(async () => {
  const safe = (p) => p.catch(() => null)
  const [params, changed, auditRes, sys] = await Promise.all([
    safe(fetchDevParams({ domain: domain.value || undefined, keyword: keyword.value || undefined })),
    safe(fetchDevOverriddenParams()),
    safe(fetchDevAudit({ page: auditPage.value, size: auditSize.value, action: auditAction.value || undefined, target: auditTarget.value || undefined })),
    safe(fetchDevSystem())
  ])
  overridden.value = Array.isArray(changed) ? changed : []
  system.value = sys
  if (auditRes) audit.value = auditRes
  return { params }
}, {
  requireWorld: false,
  // 本页要拉「全部参数」（数百行）+ 审计 + 系统信息：首帧先出页头/页签/骨架，
  // 取数挪到下一空闲帧，避免「点进配置页先卡一下」。
  defer: true
})

const rows = computed(() => page.data?.params?.rows || [])
const stats = computed(() => page.data?.params?.stats || {})
const domains = computed(() => stats.value.domains || [])

/** 参数目录首屏还没到（保存后的静默刷新不显示骨架，只首屏显示） */
const paramsLoading = computed(() => page.loading && rows.value.length === 0)

/** 生效与重启键值行 */
const effectItems = computed(() => [
  { label: '覆盖文件', value: stats.value.overrideFile || system.value?.overrideFile, mono: true },
  { label: '待重启项', value: stats.value.pendingRestart },
  { label: '已改项', value: stats.value.overridden },
  { label: '当前环境', value: isDesktopShell() ? '桌面端（可一键重启）' : '浏览器（需手动重启）' }
])

/** 待重启键值行 */
const pendingItems = computed(() => [
  { label: '文件路径', value: system.value?.overrideFile, mono: true },
  { label: '覆盖项数', value: system.value?.overrideCount },
  { label: '待重启', value: system.value?.pendingRestart },
  { label: '迁移版本', value: system.value?.schemaVersion },
  { label: '进程 PID', value: system.value?.pid, mono: true }
])

/** 审计动作 → 标签色 */
function auditType(action) {
  if (!action) return 'info'
  if (action.includes('restart') || action.includes('reset')) return 'danger'
  if (action.includes('export')) return 'warning'
  return 'primary'
}

/** 重新拉参数目录（保存/重置后） */
async function reload() {
  await load(true)
}

/** 重新拉审计 */
async function reloadAudit() {
  try {
    audit.value = await fetchDevAudit({
      page: auditPage.value, size: auditSize.value,
      action: auditAction.value || undefined, target: auditTarget.value || undefined
    })
  } catch (e) {
    ElMessage.error(e?.message || '读取审计失败')
  }
}

/** 全部恢复默认（破坏性：二次确认 + 审计） */
async function resetAll() {
  try {
    await ElMessageBox.confirm(
      `将清空全部 ${overridden.value.length} 项参数覆盖，恢复成 application.yml 的默认值。此操作不可撤销。`,
      '全部恢复默认',
      { type: 'error', confirmButtonText: '全部恢复', cancelButtonText: '取消' }
    )
  } catch (_) {
    return
  }
  try {
    await resetAllDevParams({ confirm: true, page: 'dev/config', operator: devOperator() })
    ElMessage.success('已全部恢复默认')
    await load(true)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '重置失败')
  }
}

/** 保存并重启后端（桌面端走 Electron IPC；浏览器给出明确指引） */
async function restartBackend() {
  if (!isDesktopShell() || !window.holzynDesktop?.dev?.restartBackend) {
    ElMessage.warning('当前为浏览器环境，无法重启后端；请手动重启后在启动日志里确认覆盖参数已生效')
    return
  }
  try {
    await ElMessageBox.confirm(
      '将暂停世界 → 优雅停止后端 → 重新拉起 → 回到本页。期间所有页面会短暂断开。确认继续？',
      '保存并重启后端',
      { type: 'warning', confirmButtonText: '继续重启', cancelButtonText: '取消' }
    )
  } catch (_) {
    return
  }
  restarting.value = true
  try {
    await appendDevAudit({
      action: 'system.restart', target: 'backend', oldValue: '', newValue: 'restart',
      effect: 'restart', page: 'dev/config', operator: devOperator(), note: '保存并重启后端'
    }).catch(() => {})
    const res = await window.holzynDesktop.dev.restartBackend()
    if (res && res.ok === false) ElMessage.error(res.reason || '重启失败')
    else ElMessage.success('已请求重启，页面将自动重连')
  } catch (e) {
    ElMessage.error(e?.message || '重启失败')
  } finally {
    restarting.value = false
  }
}
</script>

<style scoped>
.dev-mt { margin-top: var(--space-3, 12px); }
.dev-tab-pad { padding: 0 24px var(--density-card-pad, 16px); }
/* 骨架与表格同内边距，加载完成时内容不横向跳动 */
.dev-param-skeleton { padding: 16px 24px; }
</style>
