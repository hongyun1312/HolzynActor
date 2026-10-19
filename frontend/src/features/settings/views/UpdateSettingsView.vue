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
  <div class="settings-stack">
    <!-- 统一页头 -->
    <PageHeader
      title="更新与版本"
      :desc="store.portable
        ? '便携版不支持自动安装：发现新版本后引导您下载最新便携包，关闭应用后覆盖旧目录即可；您的世界数据（%LOCALAPPDATA%\\HolzynActor）不受影响。'
        : '桌面端自动更新：每次更新都是「世界真实性」的一次跃迁。安装版可自动检查、下载并静默升级；更新前自动备份您的世界数据，失败自动恢复。'"
    />

    <!-- ① 更新检查与状态 -->
    <div class="set-card">
      <div class="set-card-title">检查更新</div>
      <div class="set-card-desc">{{ store.portable ? '便携版通过 GitHub Releases 检查最新版本，发现新版本后引导您手动下载覆盖更新。' : '启动时自动在后台检查更新（可在下方关闭）；也可手动检查。发现新版本后会先下载，是否安装由您确认。' }}</div>

      <div class="set-row">
        <span class="set-row-label">当前版本</span>
        <b class="uv-value">{{ store.currentVersion || '—' }}</b>
        <el-tag v-if="store.channel === 'beta'" size="small" type="warning">beta 预发布</el-tag>
        <el-tag v-else size="small" type="info">stable 正式</el-tag>
        <el-tag v-if="store.portable" size="small" type="info">便携版</el-tag>
        <el-tag v-if="store.isDev" size="small" type="danger">开发模式</el-tag>
      </div>

      <div class="set-row">
        <el-button type="primary" :loading="store.checking" @click="onCheck">
          <el-icon v-if="!store.checking"><HIcon name="Refresh" /></el-icon>&nbsp;检查更新
        </el-button>
      </div>

      <!-- 状态区 -->
      <el-alert
        v-if="store.status === 'not-available'"
        type="success"
        :closable="false"
        show-icon
        title="已是最新版本"
        :description="`您正在使用当前最新版本 v${store.currentVersion}，世界已处于最真实的状态。`"
        class="uv-alert"
      />
      <el-alert
        v-else-if="store.status === 'available' && store.portable"
        type="info"
        :closable="false"
        show-icon
        title="发现新版本 v{{ store.newVersion }}"
        description="请前往下载页获取最新便携包：关闭本应用后，解压覆盖原目录即可完成更新（世界数据不受影响）。"
        class="uv-alert"
      />
      <el-alert
        v-else-if="store.status === 'available'"
        type="info"
        :closable="false"
        show-icon
        title="发现新版本，正在下载…"
        :description="`v${store.newVersion} 已找到，正在后台下载（下载完成后由您确认安装）。`"
        class="uv-alert"
      />
      <template v-else-if="store.downloading">
        <div class="uv-progress-head">
          <span>正在下载 v{{ store.newVersion }}</span>
          <span>{{ store.progressPercent }}%</span>
        </div>
        <el-progress :percentage="store.progressPercent" :stroke-width="10" class="uv-progress" />
      </template>
      <el-alert
        v-else-if="store.downloaded"
        type="success"
        :closable="false"
        show-icon
        title="新版本已就绪"
        :description="`v${store.newVersion} 已下载完成。确认后将：保存世界 → 优雅退出 → 备份数据 → 自动安装并重启。`"
        class="uv-alert"
      />
      <el-alert
        v-else-if="store.status === 'error'"
        type="warning"
        :closable="false"
        show-icon
        title="检查更新失败"
        :description="store.error || '网络不可用或更新源异常，可稍后重试。'"
        class="uv-alert"
      />
      <el-alert
        v-else-if="store.status === 'installing'"
        type="info"
        :closable="false"
        show-icon
        title="正在准备更新…"
        description="正在保存世界并备份数据，应用即将重启进入新版本。"
        class="uv-alert"
      />

      <div v-if="store.downloaded" class="set-row">
        <el-button type="primary" size="large" @click="onConfirmInstall">
          <el-icon><HIcon name="Download" /></el-icon>&nbsp;下载并重启更新
        </el-button>
        <span class="set-hint">更新前会自动备份 data + uploads（滚动保留 3 份）</span>
      </div>

      <!-- 便携版：引导下载（设计文档 §12） -->
      <div v-if="store.portable && store.status === 'available'" class="set-row">
        <el-button type="primary" size="large" @click="store.openReleasePage()">
          <el-icon><HIcon name="Download" /></el-icon>&nbsp;打开下载页
        </el-button>
        <span class="set-hint">下载最新便携包后：关闭本应用 → 解压覆盖原目录即可；世界数据在 %LOCALAPPDATA% 不受影响</span>
      </div>

      <div v-if="store.mock" class="set-tip uv-mock-hint">
        ⚠️ 开发冒烟模式（HOLZYN_UPDATER_MOCK=1）：本次检查/下载为模拟数据，确认安装仅演练「备份」流程，不真正退出应用。
      </div>
    </div>

    <!-- ② 本次世界更真实（更新说明） -->
    <div class="set-card">
      <div class="set-card-title">本次「世界更真实」</div>
      <div class="set-card-desc">每次发布都在 GitHub Release 中写明「世界真实性」的进化点：角色决策更贴近真实动机、记忆更连贯、群聊更自然…</div>
      <div v-if="notesLoading" class="set-tip uv-notes-loading">正在拉取更新说明…</div>
      <div v-else-if="store.releaseNotes" class="uv-notes">
        <div class="uv-notes-tag">{{ store.releaseNotes.tagName }}</div>
        <div class="uv-notes-body">{{ store.releaseNotes.body || '（暂无正文）' }}</div>
      </div>
      <div v-else class="set-tip uv-notes-empty">
        尚无已发布的更新说明；可前往 GitHub Releases 查看完整发布记录。
      </div>
      <el-button plain size="small" class="uv-notes-btn" @click="store.loadReleaseNotes()">刷新说明</el-button>
      <el-button text size="small" class="uv-notes-btn" @click="store.openReleasePage()">前往 GitHub Releases…</el-button>
    </div>

    <!-- ③ 数据与安全（备份 / 回滚说明） -->
    <div class="set-card">
      <div class="set-card-title">数据与安全</div>
      <div class="set-card-desc">
        您的世界数据位于 <code>%LOCALAPPDATA%\HolzynActor</code>（与安装目录分离），更新天然不丢数据；
        更新前还会自动全量备份 <code>data</code> + <code>uploads</code> 到 <code>backups\</code>（保留最近 3 份）；
        新版本启动失败时自动恢复备份，并引导手动重装上一版本。
      </div>
      <div class="set-row">
        <el-button plain @click="store.openDataDir()"><el-icon><HIcon name="FolderOpened" /></el-icon>&nbsp;打开数据目录</el-button>
        <el-button text @click="store.openReleasePage()">下载页 / 版本记录…</el-button>
      </div>
    </div>

    <!-- ④ 更新偏好 -->
    <div class="set-card">
      <div class="set-card-title">更新偏好</div>
      <el-form label-width="140px" class="set-form">
        <el-form-item label="启动时自动检查更新">
          <el-switch v-model="autoCheckModel" @change="onAutoCheckChange" />
          <span class="set-hint">{{ autoCheckModel ? '每次启动后在后台静默检查，不打断您与世界连接' : '关闭后仅手动检查' }}</span>
        </el-form-item>
        <el-form-item label="更新通道">
          <el-radio-group :model-value="store.channel" @change="onChannelChange">
            <el-radio-button value="latest">stable 正式（默认）</el-radio-button>
            <el-radio-button value="beta">beta 预发布</el-radio-button>
          </el-radio-group>
          <span class="set-hint">
            {{ store.channel === 'beta'
              ? 'beta 通道会收到预发布版本（可能不稳定），让您更早体验「世界真实性」的新进展'
              : '仅接收正式版本，稳定优先，绝不用不稳定版本打断世界' }}
          </span>
        </el-form-item>
        <el-alert
          v-if="store.channel === 'beta'"
          type="warning"
          :closable="false"
          show-icon
          title="已开启 beta 预发布通道"
          description="将收到尚未正式发布的版本，可能包含不稳定的新特性；切换回 stable 属「降级」，数据不受影响（更新前仍会自动备份）。"
          class="uv-alert"
        />
      </el-form>
    </div>

    <!-- ⑤ 后台运行（桌面端行为） -->
    <div class="set-card">
      <div class="set-card-title">后台运行</div>
      <div class="set-card-desc">
        关闭窗口后程序可驻留系统托盘，世界继续在后台推进（无人值守 · 世界不停止）；
        随时点击系统托盘图标回到主界面，从托盘菜单「退出」才真正结束程序。
      </div>
      <el-form label-width="140px" class="set-form">
        <el-form-item label="关闭窗口时">
          <el-radio-group :model-value="closeBehavior" @change="onCloseBehaviorChange">
            <el-radio-button value="tray">最小化到托盘（后台运行）</el-radio-button>
            <el-radio-button value="quit">直接退出程序</el-radio-button>
          </el-radio-group>
          <span class="set-hint">
            {{ closeBehavior === 'tray'
              ? '关闭窗口后世界继续在后台运行；点击托盘图标回到主界面，托盘菜单「退出」结束程序'
              : '关闭窗口即退出程序（世界停止）；如需后台运行请切回「最小化到托盘」' }}
          </span>
        </el-form-item>
      </el-form>
      <el-alert
        v-if="!desktopBridge"
        type="info"
        :closable="false"
        show-icon
        title="该设置仅在桌面端生效"
        description="当前为浏览器/开发环境，无系统托盘；安装版（Setup）中此设置才会生效。"
        class="uv-alert"
      />
    </div>
  </div>
</template>

<script setup>
/**
 * 「更新与版本」设置子页（U1 更新地基）。
 * <p>全局与项目设置共用本页；桌面壳（window.holzynDesktop.updater）不存在时（纯浏览器）
 * 各动作 no-op，仅展示当前版本与说明。</p>
 */
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/shared/layout/PageHeader.vue'
import HIcon from '@/shared/components/HIcon.vue'
import { useUpdaterStore } from '@/shared/store/updater'

const store = useUpdaterStore()
const autoCheckModel = ref(store.autoCheck)
const notesLoading = ref(false)
/** 桌面端桥是否存在（安装版/桌面开发模式为 true，纯浏览器为 false） */
const desktopBridge = !!window.holzynDesktop?.desktop
const closeBehavior = ref('tray') // 'tray'=关闭→最小化到托盘后台运行；'quit'=关闭→直接退出

onMounted(async () => {
  await store.init()
  autoCheckModel.value = store.autoCheck
  // 读取桌面端「后台运行」设置
  if (desktopBridge) {
    try {
      const ds = await window.holzynDesktop.desktop.getSettings()
      if (ds) closeBehavior.value = ds.closeToTray ? 'tray' : 'quit'
    } catch (_) { /* 忽略读取失败，保持默认 */ }
  }
  // 有更新或首次进入时顺带拉取说明（失败静默）
  notesLoading.value = true
  await store.loadReleaseNotes()
  notesLoading.value = false
})

async function onCloseBehaviorChange(v) {
  if (!desktopBridge) return
  try {
    const res = await window.holzynDesktop.desktop.setCloseBehavior(v)
    if (res) closeBehavior.value = res.closeToTray ? 'tray' : 'quit'
    ElMessage.success(v === 'tray' ? '已开启后台运行：关闭窗口后将最小化到系统托盘，世界继续运行' : '已关闭后台运行：关闭窗口将直接退出程序')
  } catch (_) {
    ElMessage.error('保存后台运行设置失败，请重试')
  }
}

async function onCheck() {
  await store.check()
  // 检查完成后顺带拉取说明
  if (store.status === 'available' || store.status === 'downloaded' || store.status === 'downloading') {
    await store.loadReleaseNotes()
  }
}

async function onAutoCheckChange(v) {
  await store.setAutoCheck(v)
  ElMessage.success(v ? '已开启启动自动检查' : '已关闭启动自动检查')
}

async function onChannelChange(v) {
  const isBeta = v === 'beta'
  try {
    await ElMessageBox.confirm(
      isBeta
        ? '切换到 beta 预发布通道后，将收到尚未正式发布的版本（可能不稳定），以便更早体验「世界真实性」新进展。是否继续？'
        : '切换回 stable 正式通道后，将只接收正式版本。若当前版本高于 stable 最新版，会进入「降级」流程（更新前仍自动备份数据，不影响您的世界）。是否继续？',
      '切换更新通道',
      { confirmButtonText: '切换', cancelButtonText: '取消', type: isBeta ? 'warning' : 'info' }
    )
  } catch (_) {
    return // 玩家取消 → 保持原通道（model-value 受控，不回弹需手动刷新）
  }
  await store.setChannel(v)
  ElMessage.success(isBeta ? '已切换到 beta 预发布通道，正在检查该通道的更新…' : '已切换回 stable 正式通道，正在检查正式更新…')
}

async function onConfirmInstall() {
  try {
    await ElMessageBox.confirm(
      '应用将保存当前世界并优雅退出，随后自动备份数据、安装新版本并重启。是否继续？',
      '确认更新',
      { confirmButtonText: '保存并更新', cancelButtonText: '稍后', type: 'info' }
    )
  } catch (_) {
    return // 玩家取消
  }
  const res = await store.confirmInstall()
  if (res && res.ok) {
    if (res.dev) {
      ElMessage.success(`冒烟演练完成：备份已生成（${res.backupDir}）。开发模式不执行真实安装。`)
    } else {
      // 主进程即将退出并重启，无需更多提示
    }
  } else {
    ElMessage.error(`更新未能启动：${(res && res.reason) || '未知原因'}`)
  }
}
</script>

<style scoped>
/* V2.1：卡片/标题/描述/行/提示走全局设置页规范（.set-card/.set-card-title/.set-card-desc/.set-row/.set-row-label/.set-hint/.set-tip），
   此处仅保留更新状态区的本页特有样式；颜色一律走令牌，深浅主题自适应。 */
.uv-value {
  font-size: var(--density-font, 0.9rem);
}
.uv-alert {
  margin-top: var(--space-3, 12px);
}
.uv-progress-head {
  display: flex;
  justify-content: space-between;
  margin-top: var(--space-3, 12px);
  font-size: var(--density-font-sm, 0.82rem);
  color: var(--text-secondary);
}
.uv-progress {
  margin-top: 6px;
  width: 100%;
}
.uv-mock-hint {
  margin-top: var(--space-3, 12px);
}
.uv-notes-loading {
  padding: 8px 0;
}
.uv-notes {
  margin-top: var(--space-2, 8px);
  padding: 10px 12px;
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-md, 10px);
  background: var(--bg-layer-2);
  white-space: pre-wrap;
  word-break: break-word;
  font-size: var(--density-font-sm, 0.82rem);
  line-height: 1.7;
}
.uv-notes-tag {
  font-weight: 600;
  color: var(--accent-text);
  margin-bottom: 4px;
}
.uv-notes-empty {
  padding: 8px 0;
}
.uv-notes-btn {
  margin-top: var(--space-3, 12px);
  margin-right: var(--space-2, 8px);
}
</style>
