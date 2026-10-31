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
    决策模型设置页（用户级 · 本机部署）。
    三段式：① 运行形态（本机进程 / 远程 API）② 本机部署（三个模型卡片，双源下载）③ 运行信息与排障。
    决策模型与文本模型是两类东西：它只输出「概率分布 / 布尔 / 档位」，不产长文本，
    所以入口放在 API 配置旁边而不是塞进供应商模型行里（那里只负责「填哪个接口哪个模型名」）。
  -->
  <div class="settings-stack">
    <PageHeader
      title="决策模型"
      desc="NPC 的每一次判定（是否出手、选哪个、评价几档）由决策模型给出概率分布，比让文本模型「猜一个词」稳定得多。它默认不随安装包分发，可按需下载到本机离线运行，也可调用远程 API。"
    />

    <!-- ① 运行形态 -->
    <div class="set-card">
      <div class="set-card-title">运行形态</div>
      <div class="set-card-desc">
        本机运行：权重与运行时下载到本机数据目录，只监听 127.0.0.1，不走网络、不产生调用费用，首次启动较慢；
        远程 API：自己部署或已有的决策服务，填地址即可（与「API 配置」里的文本/向量模型互不影响）。
      </div>

      <div class="dm-modes">
        <button
          v-for="m in MODES"
          :key="m.value"
          type="button"
          class="dm-mode"
          :class="{ 'is-active': form.mode === m.value }"
          @click="form.mode = m.value"
        >
          <span class="dm-mode-radio" aria-hidden="true"><i v-if="form.mode === m.value" /></span>
          <span class="dm-mode-body">
            <span class="dm-mode-head">
              <b>{{ m.name }}</b>
              <el-tag v-if="m.value === 'local' && localReady" size="small" type="success" effect="plain">本机就绪</el-tag>
              <el-tag v-else-if="m.value === 'api' && remoteConfigured" size="small" type="success" effect="plain">已配置</el-tag>
              <el-tag v-else size="small" type="info" effect="plain">{{ m.value === 'local' ? '可离线' : '免下载' }}</el-tag>
            </span>
            <span class="dm-mode-desc">{{ m.desc }}</span>
          </span>
        </button>
      </div>

      <!-- 远程 API 形态：只需地址（协议与模型名由适配器决定） -->
      <el-form v-if="form.mode === 'api'" label-width="120px" class="set-form dm-form">
        <el-form-item label="服务地址">
          <el-input v-model="form.baseUrl" placeholder="http://127.0.0.1:9100/v1" clearable />
          <span class="set-hint">决策服务的根地址（不带具体端点路径，端点由适配器自动拼接）。</span>
        </el-form-item>
        <el-form-item label="适配器">
          <el-select v-model="form.adapter" class="dm-select">
            <el-option v-for="a in adapters" :key="a.id" :label="`${a.name}（${a.protocol}）`" :value="a.id" />
          </el-select>
          <span class="set-hint">决定请求/响应结构：不同开源实现的字段并不通用，选错会解析失败并回退。</span>
        </el-form-item>
      </el-form>

      <!-- 本机形态：端口 + 说明 -->
      <el-form v-else label-width="120px" class="set-form dm-form">
        <el-form-item label="本机端口">
          <el-input-number v-model="form.localPort" :min="1024" :max="65535" :step="1" controls-position="right" />
          <span class="set-hint">默认 {{ activeAdapter?.defaultLocalPort || '—' }}；被占用时换一个空闲端口即可。</span>
        </el-form-item>
        <el-form-item label="模型">
          <el-select v-model="form.adapter" class="dm-select">
            <el-option
              v-for="a in adapters"
              :key="a.id"
              :label="`${a.name}${a.installed ? '' : '（未安装）'}`"
              :value="a.id"
            />
          </el-select>
          <span class="set-hint">同时只驻留一个本机决策服务；切换模型会先停掉旧进程再启动新进程。</span>
        </el-form-item>
      </el-form>

      <div class="set-row dm-actions">
        <el-button type="primary" :loading="applying" @click="applyMode">
          <el-icon><HIcon name="Check" /></el-icon>&nbsp;{{ form.mode === 'local' ? '启用本机模型' : '保存并启用' }}
        </el-button>
        <el-button v-if="form.mode === 'local' && localRunning" @click="onStop">停止本机服务</el-button>
        <span class="set-hint dm-endpoint">
          {{ endpointText }}
        </span>
      </div>
    </div>

    <!-- ② 本机部署（三模型清单） -->
    <div class="set-card">
      <div class="set-card-head">
        <div>
          <div class="set-card-title">本机部署</div>
          <div class="set-card-desc">
            下载即安装：权重来自 Hugging Face，运行时（Python 虚拟环境）装在本机数据目录，与安装包分离，卸载不留残留。
            国内网络优先用镜像站，两个按钮装的是同一份文件。
          </div>
        </div>
        <el-button :loading="loading" @click="load">
          <el-icon v-if="!loading"><HIcon name="Refresh" /></el-icon>&nbsp;刷新
        </el-button>
      </div>

      <el-alert
        v-if="localInfo && !localInfo.enabled"
        type="warning"
        :closable="false"
        show-icon
        title="本机部署已被服务端关闭"
        description="配置文件里 holzyn.actor.ai.decision.local.enabled=false，此时无法下载与启动。"
        class="dm-alert"
      />

      <div v-if="loading && !adapters.length" class="dm-loading">
        <el-icon class="is-loading"><HIcon name="Loading" /></el-icon>
        <span>正在读取模型清单…</span>
      </div>

      <div v-else class="dm-grid">
        <div
          v-for="a in adapters"
          :key="a.id"
          class="dm-card"
          :class="{ 'is-running': a.running, 'is-current': isCurrent(a) }"
        >
          <div class="dm-card-head">
            <div class="dm-card-name">
              <b>{{ a.name }}</b>
              <el-tag v-if="a.running" size="small" type="success">运行中</el-tag>
              <el-tag v-else-if="a.installed" size="small" type="info">已安装</el-tag>
              <el-tag v-else size="small" effect="plain">未安装</el-tag>
            </div>
            <span class="dm-card-vendor">{{ a.vendor }}</span>
          </div>

          <p class="dm-card-desc">{{ a.description }}</p>

          <div class="dm-meta">
            <span><em>大小</em>{{ fmtBytes(a.approxWeightsBytes) }}</span>
            <span><em>协议</em>{{ a.protocol }}</span>
            <span><em>许可</em>{{ a.license }}</span>
            <span><em>端口</em>{{ a.state?.port || a.defaultLocalPort }}</span>
          </div>

          <!-- 下载 / 安装进度 -->
          <div v-if="busyOf(a)" class="dm-progress">
            <div class="dm-progress-head">
              <span>{{ stateLabel(a.state.status) }}</span>
              <span v-if="a.state.progressPercent >= 0">{{ a.state.progressPercent }}%</span>
            </div>
            <el-progress
              :percentage="Math.max(0, Math.min(100, a.state.progressPercent))"
              :indeterminate="a.state.progressPercent < 0"
              :show-text="false"
              :stroke-width="6"
            />
            <div class="dm-progress-meta">
              <span>{{ fmtBytes(a.state.downloadedBytes) }} / {{ fmtBytes(a.state.totalBytes) }}</span>
              <span v-if="a.state.message" class="dm-progress-msg">{{ a.state.message }}</span>
            </div>
            <el-button size="small" text type="danger" @click="onCancel(a)">取消</el-button>
          </div>

          <!-- 排障：失败原因 -->
          <el-alert
            v-if="a.state?.status === 'error' && a.state.lastError"
            type="error"
            :closable="false"
            show-icon
            :title="a.state.lastError"
            class="dm-alert"
          />

          <!-- 已安装：启停 / 卸载 -->
          <div v-if="a.installed && !busyOf(a)" class="dm-card-actions">
            <el-button
              v-if="!a.running"
              size="small"
              type="primary"
              :loading="startingId === a.id"
              @click="onEnableLocal(a)"
            >启动</el-button>
            <el-button v-else size="small" @click="onStop">停止</el-button>
            <el-button size="small" text @click="onUninstall(a)">卸载</el-button>
            <span v-if="isCurrent(a)" class="dm-current-hint">当前使用</span>
          </div>

          <!-- 未安装：双源下载 -->
          <div v-else-if="!a.installed && !busyOf(a)" class="dm-card-actions">
            <el-button size="small" type="primary" :disabled="!canInstall" @click="onInstall(a, 'hf-mirror')">
              <el-icon><HIcon name="Download" /></el-icon>&nbsp;国内镜像下载
            </el-button>
            <el-button size="small" :disabled="!canInstall" @click="onInstall(a, 'huggingface')">官方源下载</el-button>
            <a class="dm-link" :href="a.docsUrl" target="_blank" rel="noopener">项目主页</a>
          </div>

          <div v-if="!a.localDeployable" class="dm-na">该模型不支持本机部署，请改用远程 API。</div>
        </div>
      </div>
    </div>

    <!-- ③ 运行信息与排障 -->
    <div class="set-card">
      <div class="set-card-title">运行信息</div>
      <div class="set-card-desc">下载与运行日志都留在本机数据目录，出问题先看日志尾部。</div>
      <dl class="dm-info">
        <div>
          <dt>数据目录</dt>
          <dd class="dm-mono">{{ localInfo?.dir || '—' }}</dd>
        </div>
        <div>
          <dt>占位空间</dt>
          <dd>{{ fmtBytes(localInfo?.rootSizeBytes) }}</dd>
        </div>
        <div>
          <dt>当前形态</dt>
          <dd>{{ catalog?.mode === 'local' ? '本机运行' : '远程 API' }}<template v-if="catalog?.adapter"> · {{ adapterName(catalog.adapter) }}</template></dd>
        </div>
        <div>
          <dt>生效端点</dt>
          <dd class="dm-mono">{{ catalog?.endpoint?.ready ? catalog.endpoint.evaluateUrl : '未就绪（判定层会回退）' }}</dd>
        </div>
      </dl>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="本机部署需要本机已有 Python 3.10+"
        description="安装时会优先使用系统 Python 创建独立虚拟环境（不污染全局环境）。若找不到 Python，请先安装，或退回「远程 API」形态。首次下载约 1~5 GB，取决于所选模型。"
        class="dm-alert"
      />
      <el-collapse v-if="currentState" class="dm-collapse">
        <el-collapse-item title="当前模型的安装详情与自定义命令" name="detail">
          <div class="dm-detail">
            <div><em>权重目录</em><span class="dm-mono">{{ currentState.weightsDir || '—' }}</span></div>
            <div><em>虚拟环境</em><span class="dm-mono">{{ currentState.venvDir || '—' }}</span></div>
            <div><em>Python</em><span class="dm-mono">{{ currentState.pythonExe || '—' }}</span></div>
            <div><em>启动次数</em><span>{{ currentState.installedAt ? '已安装' : '未安装' }}</span></div>
          </div>
          <div class="dm-custom">
            <span class="set-hint">
              自定义启动命令（留空用默认）：官方脚本改名或参数变化时，用这里拼一条能跑起来的命令兜底，
              形如 <code>python -m your_pkg serve &lt;权重目录&gt; --port &lt;端口&gt;</code>。
            </span>
            <el-input v-model="customCommand" type="textarea" :rows="2" placeholder="留空 = 使用适配器内置命令" />
            <el-button size="small" :loading="savingCommand" @click="onSaveCommand">保存命令</el-button>
          </div>
        </el-collapse-item>
      </el-collapse>
    </div>
  </div>
</template>

<script setup>
/**
 * 「决策模型」设置子页（本机部署 + 运行形态）。
 *
 * <p>与「API 配置」的分工：那一页管「供应商 / Key / 模型名」（文本、向量、决策三类都能填远程接口）；
 * 本页只管**决策模型的本机安装与进程生命周期**，以及「用本机还是用远程」这个总开关。
 * 两者通过后端 selection.json 汇合，前端不重复保存同一份配置。</p>
 *
 * <p>轮询策略：只在「有安装任务」或「刚点过启动」时每 2 秒拉一次单适配器状态，
 * 其余时间不动——设置页停留很久也不该持续打接口。</p>
 */
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/shared/layout/PageHeader.vue'
import HIcon from '@/shared/components/HIcon.vue'
import {
  fetchDecisionModelCatalog,
  fetchDecisionModelStatus,
  installDecisionModel,
  cancelDecisionModelInstall,
  stopDecisionModel,
  uninstallDecisionModel,
  saveDecisionModelSelection,
  saveDecisionModelCustomCommand
} from '@/shared/api/settings'

/** 两种运行形态（顺序即展示顺序：本机优先，用户不必先理解「远程」） */
const MODES = [
  { value: 'local', name: '本机运行', desc: '权重与运行时下载到本机，离线推理，不产生调用费用' },
  { value: 'api', name: '调用远程 API', desc: '使用已部署的决策服务，无需下载，按请求计费' }
]

/** 状态文案（后端 DecisionLocalState.STATUS_*） */
const STATUS_LABELS = {
  'not-installed': '未安装',
  downloading: '下载权重',
  verifying: '校验文件',
  provisioning: '安装运行时',
  installed: '已就绪',
  starting: '启动中',
  running: '运行中',
  stopped: '已停止',
  error: '失败'
}

const loading = ref(true)
const applying = ref(false)
const catalog = ref(null)
const startingId = ref('')
const savingCommand = ref(false)
const customCommand = ref('')
/** 正在安装的适配器 ID 集合（轮询用） */
const polling = ref([])
/** 安装完成后自动启用（用户点「下载」的意图就是「装好就能用」） */
const pendingEnable = ref('')
let timer = null

const form = reactive({ mode: 'api', adapter: '', baseUrl: '', localPort: 0 })

const adapters = computed(() => catalog.value?.adapters || [])
const localInfo = computed(() => catalog.value?.local || null)
/** 本机进程是否在跑（任一模模型 running） */
const localRunning = computed(() => adapters.value.some(a => a.running))
/** 本机形态是否就绪（当前模型已安装且进程在跑） */
const localReady = computed(() => localRunning.value && adapters.value.some(a => a.running && isCurrent(a)))
const remoteConfigured = computed(() => !!(catalog.value?.remote?.configuredBaseUrl))
const activeAdapter = computed(() => adapters.value.find(a => a.id === form.adapter) || adapters.value[0] || null)
const currentState = computed(() => {
  const id = catalog.value?.adapter || form.adapter
  const a = adapters.value.find(x => x.id === id)
  return a?.state || null
})
const canInstall = computed(() => !!localInfo.value?.enabled)
const endpointText = computed(() => {
  const ep = catalog.value?.endpoint
  if (form.mode === 'local') {
    return localRunning.value && ep?.ready
      ? `已就绪：${ep.evaluateUrl}`
      : '本机服务未运行——判定层会回退到文本模型，不会静默使用错误结果'
  }
  const url = catalog.value?.remote?.configuredBaseUrl
  return url ? `远程：${url}` : '尚未填写服务地址——判定层不可用'
})

onMounted(load)
onUnmounted(stopPolling)

/** 拉全量清单（catalog 含三模型状态 + 选择 + 端点） */
async function load() {
  loading.value = true
  try {
    const data = await fetchDecisionModelCatalog()
    catalog.value = data
    syncForm(data)
  } catch (e) {
    ElMessage.error(e?.message || '读取决策模型清单失败')
  } finally {
    loading.value = false
  }
}

/** 用后端的选择回填表单（仅在首次与显式刷新时，避免轮询覆盖用户正在编辑的值） */
function syncForm(data) {
  const sel = data?.selection || {}
  const adapterId = sel.adapter || data?.adapter || data?.adapters?.[0]?.id || ''
  form.mode = (sel.mode || data?.mode || 'api') === 'local' ? 'local' : 'api'
  form.adapter = adapterId
  form.baseUrl = sel.baseUrl || data?.remote?.configuredBaseUrl || ''
  const a = data?.adapters?.find(x => x.id === adapterId)
  form.localPort = sel.localPort || a?.state?.port || a?.defaultLocalPort || 0
  customCommand.value = a?.state?.customCommand || ''
}

function isCurrent(a) {
  return (catalog.value?.adapter || '') === a.id
}

function busyOf(a) {
  return a.state && ['downloading', 'verifying', 'provisioning', 'starting'].includes(a.state.status)
}

function stateLabel(status) {
  return STATUS_LABELS[status] || status || ''
}

function adapterName(id) {
  return adapters.value.find(a => a.id === id)?.name || id
}

/** 字节 → 人类可读（大模型按 GB 展示更直观） */
function fmtBytes(n) {
  const v = Number(n)
  if (!v || v <= 0) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let x = v
  while (x >= 1024 && i < units.length - 1) { x /= 1024; i += 1 }
  return `${x >= 100 || i === 0 ? Math.round(x) : x.toFixed(1)} ${units[i]}`
}

/** 应用运行形态：本地=启动进程并写选择；远程=停进程并写地址 */
async function applyMode() {
  if (form.mode === 'api' && !form.baseUrl.trim()) {
    ElMessage.warning('请先填写决策服务地址（例如 http://127.0.0.1:9100/v1）')
    return
  }
  applying.value = true
  try {
    await saveDecisionModelSelection({
      mode: form.mode,
      adapter: form.adapter,
      baseUrl: form.baseUrl.trim(),
      localPort: form.mode === 'local' ? form.localPort : 0
    })
    ElMessage.success(form.mode === 'local' ? '已切换到本机运行' : '已切换到远程 API')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '切换运行形态失败')
  } finally {
    applying.value = false
  }
}

/** 下载并安装；装完自动启用（省掉「再点一次启用」这一跳） */
async function onInstall(a, source) {
  if (source === 'huggingface') {
    try {
      await ElMessageBox.confirm(
        '官方源在国内网络常连不通或很慢（可能长时间无进度）。若卡住可取消后改用「国内镜像下载」。是否继续？',
        '使用官方源下载',
        { confirmButtonText: '继续', cancelButtonText: '改用镜像', type: 'warning' }
      )
    } catch (_) {
      return
    }
  }
  try {
    await installDecisionModel(a.id, source)
    pendingEnable.value = a.id
    startPolling(a.id)
    ElMessage.success(`已开始下载 ${a.name}，可在下方查看进度`)
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '启动下载失败')
  }
}

async function onCancel(a) {
  try {
    await cancelDecisionModelInstall(a.id)
    pendingEnable.value = ''
    ElMessage.info('已请求取消，正在停止任务…')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '取消失败')
  }
}

/** 启用本机模型（= 切到 local + 启动进程，一步完成） */
async function onEnableLocal(a) {
  startingId.value = a.id
  try {
    await saveDecisionModelSelection({ mode: 'local', adapter: a.id, baseUrl: form.baseUrl.trim(), localPort: form.localPort || 0 })
    ElMessage.success(`${a.name} 已在本机启动`)
    startPolling(a.id)
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '启动失败')
  } finally {
    startingId.value = ''
  }
}

async function onStop() {
  try {
    await stopDecisionModel()
    ElMessage.info('已停止本机决策服务')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '停止失败')
  }
}

async function onUninstall(a) {
  try {
    await ElMessageBox.confirm(
      `卸载 ${a.name} 会删除本机权重与虚拟环境（约 ${fmtBytes(a.approxWeightsBytes)}），之后需重新下载。是否继续？`,
      '卸载模型',
      { confirmButtonText: '卸载', cancelButtonText: '取消', type: 'warning' }
    )
  } catch (_) {
    return
  }
  try {
    await uninstallDecisionModel(a.id)
    ElMessage.success('已卸载')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '卸载失败')
  }
}

async function onSaveCommand() {
  const id = activeAdapter.value?.id
  if (!id) return
  savingCommand.value = true
  try {
    await saveDecisionModelCustomCommand(id, customCommand.value.trim())
    ElMessage.success(customCommand.value.trim() ? '已保存自定义命令' : '已恢复默认启动命令')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    savingCommand.value = false
  }
}

// ==================== 轮询：仅在安装/启动期间 ====================

function startPolling(adapterId) {
  if (adapterId && !polling.value.includes(adapterId)) polling.value = [...polling.value, adapterId]
  if (timer) return
  timer = setInterval(tick, 2000)
}

function stopPolling() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
  polling.value = []
}

async function tick() {
  if (!polling.value.length) {
    stopPolling()
    return
  }
  const ids = [...polling.value]
  for (const id of ids) {
    try {
      const st = await fetchDecisionModelStatus(id)
      const a = adapters.value.find(x => x.id === id)
      if (a) a.state = st
      // 安装完成 → 自动启用一次，然后停止跟踪该模型
      if (st?.status === 'installed' && pendingEnable.value === id) {
        pendingEnable.value = ''
        polling.value = polling.value.filter(x => x !== id)
        await autoEnable(id)
        continue
      }
      // 进入失败/停止态 → 不再轮询该模型
      if (st && ['error', 'not-installed', 'stopped'].includes(st.status)) {
        polling.value = polling.value.filter(x => x !== id)
      }
    } catch (_) { /* 单次失败忽略，下次再试 */ }
  }
  if (!polling.value.length) {
    stopPolling()
    await load()
  }
}

/** 安装完成后自动「安装即用」：切到本机形态并启动进程 */
async function autoEnable(adapterId) {
  const a = adapters.value.find(x => x.id === adapterId)
  try {
    await saveDecisionModelSelection({ mode: 'local', adapter: adapterId, baseUrl: form.baseUrl.trim(), localPort: form.localPort || 0 })
    form.mode = 'local'
    form.adapter = adapterId
    ElMessage.success(`${a?.name || '决策模型'} 安装完成，已在本机启动`)
  } catch (e) {
    ElMessage.warning(`${a?.name || '决策模型'} 已安装，但自动启动失败：${e?.message || '请手动点「启动」'}`)
  } finally {
    await load()
  }
}
</script>

<style scoped>
/* 运行形态选择：两块并排的可点卡片（比 radio 更能说清差异） */
.dm-modes {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 12px;
  margin: 4px 0 14px;
}

.dm-mode {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  text-align: left;
  padding: 12px 14px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
  background: var(--el-fill-color-blank);
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.dm-mode:hover { border-color: var(--el-color-primary-light-5); }
.dm-mode.is-active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.dm-mode-radio {
  flex: none;
  width: 14px;
  height: 14px;
  margin-top: 3px;
  border: 1px solid var(--el-border-color);
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--el-fill-color-blank);
}

.dm-mode-radio i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--el-color-primary);
}

.dm-mode-body { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.dm-mode-head { display: flex; align-items: center; gap: 8px; font-size: 0.9rem; }
.dm-mode-desc { font-size: 0.8rem; color: var(--el-text-color-secondary); line-height: 1.6; }

.dm-form { padding-top: 0; }
.dm-select { width: 260px; }
.dm-actions { flex-wrap: wrap; }
.dm-endpoint { flex: 1 1 320px; word-break: break-all; }

/* 模型卡片网格 */
.dm-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 14px;
  margin-top: 12px;
}

.dm-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--el-border-radius-base);
  background: var(--el-fill-color-blank);
}

.dm-card.is-current { border-color: var(--el-color-primary-light-5); }
.dm-card.is-running { box-shadow: inset 3px 0 0 var(--el-color-success); }

.dm-card-head { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
.dm-card-name { display: flex; align-items: center; gap: 8px; font-size: 0.95rem; }
.dm-card-vendor { font-size: 0.78rem; color: var(--el-text-color-secondary); }
.dm-card-desc { margin: 0; font-size: 0.82rem; color: var(--el-text-color-secondary); line-height: 1.7; }

.dm-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  font-size: 0.78rem;
  color: var(--el-text-color-regular);
}

.dm-meta em, .dm-detail em {
  font-style: normal;
  color: var(--el-text-color-placeholder);
  margin-right: 4px;
}

.dm-progress { display: flex; flex-direction: column; gap: 6px; }
.dm-progress-head { display: flex; justify-content: space-between; font-size: 0.8rem; color: var(--el-text-color-regular); }
.dm-progress-meta { display: flex; gap: 10px; font-size: 0.78rem; color: var(--el-text-color-secondary); }
.dm-progress-msg { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.dm-card-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.dm-link { font-size: 0.8rem; color: var(--el-color-primary); text-decoration: none; }
.dm-link:hover { text-decoration: underline; }
.dm-current-hint { font-size: 0.78rem; color: var(--el-color-success); }
.dm-na { font-size: 0.78rem; color: var(--el-text-color-placeholder); }

.dm-alert { margin-top: 10px; }
.dm-loading { display: flex; align-items: center; gap: 8px; padding: 18px 0; color: var(--el-text-color-secondary); }

.dm-info { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 10px 24px; margin: 12px 0 0; }
.dm-info > div { display: flex; gap: 10px; align-items: baseline; }
.dm-info dt { flex: none; width: 76px; font-size: 0.82rem; color: var(--el-text-color-secondary); }
.dm-info dd { margin: 0; font-size: 0.84rem; word-break: break-all; }
.dm-mono { font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace); font-size: 0.8rem; }

.dm-collapse { margin-top: 12px; }
.dm-detail { display: flex; flex-direction: column; gap: 6px; font-size: 0.82rem; }
.dm-detail > div { display: flex; gap: 8px; }
.dm-custom { display: flex; flex-direction: column; gap: 8px; align-items: flex-start; margin-top: 12px; }
.dm-custom code { background: var(--el-fill-color-light); padding: 1px 4px; border-radius: 3px; }
</style>
