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
    title="工具"
    desc="实时日志、Unity 连接状态与系统信息。"
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
    <!-- ① 实时日志 -->
    <el-tab-pane label="实时日志" name="logs" :lazy="true">
      <DevCard title="日志流" :desc="`已接收 ${logs.length} 行（上限 ${MAX_LINES} 行）。订阅的是应用自写日志文件对应的 SSE，断线会自动重连。`">
        <template #actions>
          <el-select v-model="level" size="small" clearable placeholder="全部级别" style="width: 130px" @change="restartStream">
            <el-option label="ERROR" value="ERROR" />
            <el-option label="WARN" value="WARN" />
            <el-option label="INFO" value="INFO" />
            <el-option label="DEBUG" value="DEBUG" />
          </el-select>
          <el-input v-model="logKeyword" size="small" placeholder="关键字" clearable style="width: 160px" @keyup.enter="restartStream" />
          <el-checkbox v-model="autoScroll" size="small">自动滚动</el-checkbox>
          <el-button size="small" :type="streaming ? 'danger' : 'primary'" @click="toggleStream">
            {{ streaming ? '停止' : '开始订阅' }}
          </el-button>
          <el-button size="small" @click="clearLogs">清空</el-button>
        </template>

        <div ref="logBox" class="dev-log-box">
          <div v-if="!logs.length" class="dev-toolbar-note">还没有日志：点「开始订阅」</div>
          <div v-for="(line, i) in logs" :key="i" class="dev-log-line" :class="levelClass(line)">
            <span class="dev-log-time">{{ line.time || '' }}</span>
            <span class="dev-log-level">{{ line.level || '' }}</span>
            <span class="dev-log-text">{{ line.text || line.message || line.raw || '' }}</span>
          </div>
        </div>
      </DevCard>
    </el-tab-pane>

    <!-- ② Unity 对接 -->
    <el-tab-pane label="Unity 对接" name="unity" :lazy="true">
      <DevCard title="Unity 桥接状态" desc="Unity 侧连接、存活、消息吞吐。未接 Unity 时显示未连接属正常。">
        <DevAuto v-if="unity" :value="unity" />
        <div v-else class="dev-toolbar-note">未返回 Unity 状态</div>
      </DevCard>
    </el-tab-pane>

    <!-- ③ 系统信息 -->
    <el-tab-pane label="系统信息" name="system" :lazy="true">
      <div class="dev-grid-2">
        <DevCard title="运行时" desc="版本、JDK、操作系统、内存与进程信息。">
          <DevKv :items="runtimeItems" />
        </DevCard>
        <DevCard title="路径与端口" desc="数据目录、日志、上传目录与覆盖文件。">
          <DevKv :items="pathItems" />
        </DevCard>
      </div>
      <DevCard title="原始返回" desc="字段随版本变化，这里始终显示后端真实结构。">
        <DevJson :value="system || {}" title="dev/system" :max-height="360" />
      </DevCard>
    </el-tab-pane>
  </PageTabs>
</template>

<script setup>
/**
 * 开发者控制台 · 工具域。
 *
 * <p>日志页用 EventSource 订阅 `/api/debug/logs/stream`：这条流补发最近 500 条后持续推送，
 * 是排查「刚才那一下发生了什么」最快的路径。行数上限 3000，防止长时间挂着吃内存。</p>
 */
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import PageHeader from '@/shared/layout/PageHeader.vue'
import PageTabs from '@/shared/layout/PageTabs.vue'
import HIcon from '@/shared/components/HIcon.vue'
import DevCard from '../components/DevCard.vue'
import DevKv from '../components/DevKv.vue'
import DevAuto from '../components/DevAuto.vue'
import DevJson from '../components/DevJson.vue'
import { fetchDevSystem, fetchUnityStatus, fetchLogBuffer, debugLogStreamUrl } from '@/shared/api/dev'
import { useDevPage } from '../useDevPage'

/** 日志行数上限 */
const MAX_LINES = 3000

const tab = ref('logs')
const level = ref('')
const logKeyword = ref('')
const autoScroll = ref(true)
const logs = ref([])
const logBox = ref(null)
const streamSource = ref(null)
const streaming = computed(() => !!streamSource.value)
const system = ref(null)
const unity = ref(null)

const { state: page } = useDevPage(async ({ worldId }) => {
  const safe = (p) => p.catch(() => null)
  const [sys, unityStatus, buffer] = await Promise.all([
    safe(fetchDevSystem()),
    // 没有挂载世界时不发 Unity 状态请求：`/api/worlds/null/unity/status` 恒 400，
    // 只会往控制台刷一条「Failed to load resource」，让人误以为页面坏了。
    worldId ? safe(fetchUnityStatus(worldId)) : Promise.resolve(null),
    safe(fetchLogBuffer({ limit: 200 }))
  ])
  system.value = sys
  unity.value = unityStatus
  // 初始补发：日志缓冲接口返回结构可能是数组或 {items}，两种都兼容
  const list = Array.isArray(buffer) ? buffer : (buffer?.items || buffer?.lines || [])
  if (Array.isArray(list) && !logs.value.length) logs.value = list.map(normalizeLine)
  return { sys, unityStatus }
}, { requireWorld: false })

/** 运行时键值行 */
const runtimeItems = computed(() => [
  { label: '应用版本', value: system.value?.appVersion },
  { label: 'Java', value: system.value?.javaVersion },
  { label: '操作系统', value: system.value?.os },
  { label: 'CPU 核数', value: system.value?.cpuCores },
  { label: '进程 PID', value: system.value?.pid, mono: true },
  { label: '时区', value: system.value?.timezone },
  { label: '激活 Profile', value: system.value?.profile || '（默认）' },
  { label: '迁移版本', value: system.value?.schemaVersion }
])

/** 路径键值行 */
const pathItems = computed(() => [
  { label: '数据目录', value: system.value?.dataDir, mono: true },
  { label: '日志文件', value: system.value?.logFile, mono: true },
  { label: '上传目录', value: system.value?.uploadDir, mono: true },
  { label: '覆盖文件', value: system.value?.overrideFile, mono: true },
  { label: '服务端口', value: system.value?.serverPort },
  { label: '内存上限', value: system.value?.memory?.maxMb != null ? `${system.value.memory.maxMb} MB` : null }
])

/** 日志行归一化（后端行可能是 {time,level,text} 或纯字符串） */
function normalizeLine(raw) {
  if (typeof raw === 'string') return { level: '', time: '', text: raw }
  return {
    time: raw.time || raw.timestamp || raw.ts || '',
    level: raw.level || '',
    text: raw.text || raw.message || raw.line || raw.raw || JSON.stringify(raw)
  }
}

/** 级别 → 颜色类 */
function levelClass(line) {
  const lv = String(line.level || '').toUpperCase()
  if (lv.includes('ERROR')) return 'is-error'
  if (lv.includes('WARN')) return 'is-warn'
  if (lv.includes('DEBUG')) return 'is-debug'
  return ''
}

/** 打开 / 关闭订阅 */
function toggleStream() {
  if (streamSource.value) {
    streamSource.value.close()
    streamSource.value = null
    return
  }
  const src = new EventSource(debugLogStreamUrl({ level: level.value || undefined, keyword: logKeyword.value || undefined }))
  const onData = (ev) => {
    if (!ev?.data) return
    try {
      logs.value = [...logs.value, normalizeLine(JSON.parse(ev.data))].slice(-MAX_LINES)
    } catch (_) {
      logs.value = [...logs.value, { level: '', time: '', text: ev.data }].slice(-MAX_LINES)
    }
    if (autoScroll.value) {
      nextTick(() => {
        if (logBox.value) logBox.value.scrollTop = logBox.value.scrollHeight
      })
    }
  }
  src.addEventListener('log', onData)
  src.onmessage = onData
  streamSource.value = src
}

/** 重启订阅（筛选条件变化时） */
function restartStream() {
  if (streamSource.value) {
    streamSource.value.close()
    streamSource.value = null
    toggleStream()
  }
}

/** 清空本地已收日志 */
function clearLogs() {
  logs.value = []
}

onBeforeUnmount(() => {
  if (streamSource.value) {
    streamSource.value.close()
    streamSource.value = null
  }
})
</script>

<style scoped>
.dev-log-box {
  height: 440px;
  overflow: auto;
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-md, 10px);
  background: var(--bg-layer-2, var(--bg-hover));
  padding: 8px 10px;
  font-family: var(--font-mono, ui-monospace, 'Cascadia Mono', Consolas, monospace);
  font-size: 0.76rem;
  line-height: 1.6;
}
.dev-log-line { display: flex; gap: 8px; white-space: pre-wrap; word-break: break-word; }
.dev-log-time { flex: 0 0 74px; color: var(--text-tertiary); }
.dev-log-level { flex: 0 0 48px; color: var(--text-tertiary); }
.dev-log-text { flex: 1; min-width: 0; color: var(--text-primary); }
.dev-log-line.is-error .dev-log-level, .dev-log-line.is-error .dev-log-text { color: var(--state-error); }
.dev-log-line.is-warn .dev-log-level { color: var(--state-warn); }
.dev-log-line.is-debug .dev-log-text { color: var(--text-secondary); }
</style>
