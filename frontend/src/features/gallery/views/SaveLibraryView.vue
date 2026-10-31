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
  <!-- 世界画廊（V1.1.2-beta.1 M5 起）：应用唯一落地页。
       底层世界数据以「存档目录」形式存储，界面仍按世界画廊呈现——用户看到的是世界，不是存档槽位。
       数据源 = 目录库 actor_project 摘要列（GET /api/worlds），**不打开任何世界库**，
       所以卡片能显示游戏内时间/角色数/体积/最后打开时间而无需挂载世界。 -->
  <AppShell mode="global" brand-label="HolzynActor" :show-sidebar="false" :show-back="false">
    <div class="library-scene">
      <div class="content-col">
        <PageHeader title="世界画廊" :desc="headerDesc">
          <template #actions>
            <SettingsButton @click="openSettingsDialog()" />
            <el-button plain :disabled="busy" @click="load">
              <el-icon><HIcon name="Refresh" /></el-icon>&nbsp;刷新
            </el-button>
            <!-- 导入世界：v3 包 = 世界目录 zip；导入后是一个新世界，不会覆盖已有世界 -->
            <el-button plain :disabled="busy || importing" :loading="importing" @click="pickImportFile">
              <el-icon><HIcon name="Upload" /></el-icon>&nbsp;导入世界
            </el-button>
            <!-- data-tour：新手引导第 2 步的聚光灯目标（改前该属性不存在，引导只能居中不挖洞） -->
            <el-button type="primary" :disabled="busy" data-tour="fr-add-project" @click="goNewWorld">
              <el-icon><HIcon name="Plus" /></el-icon>&nbsp;新建世界
            </el-button>
            <input
              ref="fileInputRef"
              type="file"
              accept=".holzyn,.zip"
              class="library-file-input"
              @change="onImportFilePicked"
            />
            <!-- 封面上传（菜单「上传封面 / 更换封面」唤起；与导入世界的 input 分开，避免互相清空 value） -->
            <input
              ref="coverInputRef"
              type="file"
              accept="image/png,image/jpeg,.png,.jpg,.jpeg"
              class="library-file-input"
              @change="onCoverPicked"
            />
          </template>
        </PageHeader>

        <!-- 未配置模型：只提示不阻断（与旧画廊同一条横幅，文案改成"世界不会自己动"） -->
        <el-alert
          v-if="showApiBanner"
          class="library-api-banner"
          type="warning"
          show-icon
          :closable="true"
          @close="dismissApiBanner"
          title="还没接上模型：角色暂时只会重复简单作息，不会自己思考和行动"
          description="接入一个 OpenAI 兼容的模型接口后，世界才会真正运转。"
        >
          <template #default>
            <el-button size="small" type="warning" plain @click="openSettingsDialog('apis')">去接入模型</el-button>
          </template>
        </el-alert>

        <div v-if="loading && worlds.length === 0" v-loading="true" class="library-loading" />
        <EmptyState
          v-else-if="worlds.length === 0"
          size="page"
          icon="ic_gallery_map_all"
          title="还没有世界"
          desc="每个世界都有自己的世界观、角色、时间与记忆，互不干扰。"
          primary-text="新建你的第一个世界"
          secondary-text="去设置里接上模型"
          @primary="goNewWorld"
          @secondary="openSettingsDialog('apis')"
        />
        <template v-else>
          <section class="library-section">
            <div class="section-head">
              <div>
                <div class="section-title">全部世界</div>
                <div class="section-sub">共 {{ worlds.length }} 个世界 · 打开即挂载，同一时刻只有一个世界在运行</div>
              </div>
              <el-input v-model="searchText" placeholder="搜索世界名称…" clearable class="all-search" :prefix-icon="Search" />
            </div>

            <div v-if="filteredWorlds.length === 0" class="section-empty">没有匹配的世界</div>
            <div v-else class="world-grid">
              <!-- data-tour / data-guide-demo：新手引导第 3 步的聚光灯目标。
                   示例世界那一条额外打 data-guide-demo（选取规则与引导同源于 pickGuideProject）。 -->
              <div
                v-for="w in filteredWorlds"
                :key="w.id"
                class="world-card tech-card"
                :class="{ 'is-broken': !w.mountable }"
                data-tour="project-card"
                :data-guide-demo="w.id === guideDemoId ? '1' : null"
              >
                <!-- 有封面用封面图；没有则铺「预备背面」（内置 SVG 卡背）。两种都压一层底部渐变，保证白字世界名可读 -->
                <div class="card-cover" :class="{ 'is-back': !worldCoverUrl(w) }" :style="coverStyle(w)" @click="continueWorld(w)">
                  <span class="cover-name">{{ w.name || '未命名世界' }}</span>
                  <el-tag v-if="w.mounted" size="small" type="success" class="cover-tag">运行中</el-tag>
                  <el-tag v-else-if="w.characterCount" size="small" class="cover-tag">{{ w.characterCount }} 角色</el-tag>
                </div>
                <div class="card-body">
                  <div class="card-name" @click="continueWorld(w)">
                    {{ w.name || '未命名世界' }}
                    <!-- 世界类别 · 世界程度：画廊一眼看出这个世界的题材与尺度（老世界没快照时不显示） -->
                    <el-tag v-if="classifyText(w)" size="small" type="info" effect="plain" class="name-tag">
                      {{ classifyText(w) }}
                    </el-tag>
                  </div>
                  <div class="card-summary" :title="w.summary">{{ w.summary || '暂无描述' }}</div>
                  <!-- 世界初始化状态：没初始化完的世界在这里直接给入口，不必先打开世界再找 -->
                  <div v-if="initState(w)" class="card-init">
                    <template v-if="initState(w) === 'pending'">
                      <el-tag size="small" type="warning" effect="plain">{{ initBriefText(w) }}</el-tag>
                      <el-button size="small" type="warning" link @click="goInit(w)">继续初始化 →</el-button>
                    </template>
                    <el-tag v-else-if="initState(w) === 'degraded'" size="small" type="info" effect="plain">
                      初始化完成 · 可选步骤跳过
                    </el-tag>
                    <el-tag v-else size="small" type="success" effect="plain">初始化完成</el-tag>
                  </div>
                  <div class="card-meta">
                    <span class="meta-item" :title="'世界时间（actor_world_clock.last_game_second）'">
                      <el-icon><HIcon name="AlarmClock" /></el-icon>{{ gameClockText(w) }}
                    </span>
                    <span class="meta-item" :title="'世界目录体积（含库与资源）'">
                      <el-icon><HIcon name="Document" /></el-icon>{{ sizeText(w.sizeBytes) }}
                    </span>
                    <span class="meta-item" :title="'最近一次打开时间'">
                      <el-icon><HIcon name="Clock" /></el-icon>{{ relativeText(w.lastOpenedAt) }}
                    </span>
                  </div>
                  <div class="card-foot">
                    <el-tag size="small" effect="light" :type="statusType(w.worldStatus)">{{ w.worldStatusLabel || w.worldStatus }}</el-tag>
                    <div class="foot-actions">
                      <el-button size="small" type="primary" :disabled="busy || !w.mountable && !w.mounted" @click="continueWorld(w)">
                        {{ w.mounted ? '继续游戏' : '打开世界' }}
                      </el-button>
                      <el-dropdown trigger="click" @command="(cmd) => onCommand(cmd, w)">
                        <el-button size="small" text :disabled="busy"><el-icon><HIcon name="MoreFilled" /></el-icon></el-button>
                        <template #dropdown>
                          <el-dropdown-menu>
                            <el-dropdown-item command="init">世界初始化…</el-dropdown-item>
                            <el-dropdown-item command="rename">世界设置（改名/概要）</el-dropdown-item>
                            <el-dropdown-item command="cover">{{ w.coverUrl ? '更换封面…' : '上传封面…' }}</el-dropdown-item>
                            <el-dropdown-item v-if="w.coverUrl" command="cover-remove">移除封面</el-dropdown-item>
                            <el-dropdown-item command="manifest">查看世界清单</el-dropdown-item>
                            <el-dropdown-item command="export" :disabled="!!w.mounted" divided>导出世界…</el-dropdown-item>
                            <el-dropdown-item command="delete">归档删除…</el-dropdown-item>
                          </el-dropdown-menu>
                        </template>
                      </el-dropdown>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </section>
        </template>
      </div>
    </div>

    <!-- 世界设置（只写目录库：改名/概要，不动世界库） -->
    <el-dialog v-model="metaDialog.visible" title="世界设置" width="520px" :close-on-click-modal="false">
      <el-form label-width="72px">
        <el-form-item label="世界名">
          <el-input v-model="metaDialog.name" maxlength="100" show-word-limit placeholder="世界名" />
        </el-form-item>
        <el-form-item label="概要">
          <el-input v-model="metaDialog.summary" type="textarea" :rows="4" maxlength="500" show-word-limit placeholder="一句话介绍这个世界" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="metaDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="saveMeta">保存</el-button>
      </template>
    </el-dialog>

    <!-- 世界清单（只读 JSON，排查"这个世界是哪个版本建的"） -->
    <el-dialog v-model="manifestDialog.visible" title="世界清单" width="720px">
      <pre class="manifest-pre">{{ manifestDialog.text }}</pre>
    </el-dialog>

    <GlobalSettingsDialog ref="settingsRef" @closed="checkApiReady" />
  </AppShell>
</template>

<script setup>
/**
 * 世界画廊（V1.1.2-beta.1 M5 起）。
 *
 * <p>与旧版画廊的区别不是改名，而是<b>数据来源</b>：旧画廊读旧的项目列表接口（项目表 + 逐项查角色数），
 * 分档后那套查询要么打在目录库的空表上、要么等于"为了看一眼列表把所有世界库都打开"。本页只读目录库摘要列
 * （{@link fetchWorlds}），任何卡片信息都不需要挂载世界。</p>
 *
 * <p>命名口径：底层世界数据分档存储（一个世界一个存档目录），界面文案一律用「世界」——
 * 用户打开的是世界，不是存档槽位。</p>
 *
 * <p>动作语义：</p>
 * <ul>
 *   <li><b>打开世界</b>：{@code POST /api/worlds/{id}/open} 成功后进世界沙盘（单活动世界，已在别处打开会被 409 拒绝）；</li>
 *   <li><b>新建世界</b>：跳 {@code /world/new}（创建 + 自动挂载 + 初始化在那边完成，避免两处创建入口）；</li>
 *   <li><b>归档删除</b>：后端把世界目录整体移进 {@code archive/deleted-worlds/}，索引软删；前端必须二次确认并说明可救回；</li>
 *   <li><b>世界设置</b>：只写目录库（改名/概要），世界里改才需要重开世界才生效的字段不在这里。</li>
 * </ul>
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import AppShell from '@/shared/layout/AppShell.vue'
import PageHeader from '@/shared/layout/PageHeader.vue'
import EmptyState from '@/shared/components/EmptyState.vue'
import HIcon from '@/shared/components/HIcon.vue'
import SettingsButton from '@/shared/components/SettingsButton.vue'
import GlobalSettingsDialog from '@/features/settings/views/GlobalSettingsDialog.vue'
import { fetchWorlds, openWorld, deleteWorld, updateWorldMeta, fetchWorldManifest, exportWorld, importWorld,
  worldCoverUrl, uploadWorldCover, removeWorldCover, fetchAiReadiness } from '@/shared/api'
import { pickGuideProject } from '@/shared/onboarding/guideProject'
import { formatCompactDayTime } from '@/shared/worldClock'
import cardBackUrl from '@/assets/world-card-back.svg'

const router = useRouter()
const settingsRef = ref(null)
const loading = ref(false)
const busy = ref(false)
const worlds = ref([])
const searchText = ref('')
/** 真实配置里「已接上模型」（后端 /api/model-apis/ready 判定，任意层级） */
const apiReady = ref(false)
/** 用户手动关过横幅（localStorage 记住，永久不再提示） */
const bannerDismissed = ref(false)
/** 「还没接上模型」横幅：未配置 且 没手动关过 */
const showApiBanner = computed(() => !apiReady.value && !bannerDismissed.value)

const metaDialog = ref({ visible: false, id: null, name: '', summary: '' })
const manifestDialog = ref({ visible: false, text: '' })

/** 导入世界中（禁用按钮 + loading） */
const importing = ref(false)

/** 隐藏的 file input（导入世界） */
const fileInputRef = ref(null)

/** 隐藏的 file input（上传封面） */
const coverInputRef = ref(null)

/** 当前正在换封面的世界 id（文件选择框是共用的，选完文件靠它找回目标世界） */
const coverTargetId = ref(null)

const headerDesc = computed(() => (
  worlds.value.length
    ? '打开一个世界即挂载它：同一时刻只有一个世界在运行，关闭时会把世界库压盘并回写摘要。'
    : '每个世界都有自己的世界观、角色、时间与记忆。新建之后会自动打开它，并进入世界初始化。'
))

const filteredWorlds = computed(() => {
  const key = searchText.value.trim().toLowerCase()
  if (!key) return worlds.value
  return worlds.value.filter((w) => (w.name || '').toLowerCase().includes(key))
})

/**
 * 新手引导要带入的示例世界 ID（给那张卡片打 `data-guide-demo`，让聚光灯瞄得准）。
 * <p>规则与 FirstRunGuide 共用 {@link pickGuideProject}——两边各写一份就会出现
 * 「引导说点青屿镇、高亮却在另一张卡片」。搜索过滤后目标卡片可能不在 DOM 里，
 * 引导会自动降级为「居中不挖洞」，不是 bug。</p>
 */
const guideDemoId = computed(() => pickGuideProject(worlds.value)?.id ?? null)

/** 读世界列表（只读目录库，绝不挂载世界） */
async function load() {
  loading.value = true
  try {
    const list = await fetchWorlds()
    worlds.value = Array.isArray(list) ? list : []
  } catch (e) {
    ElMessage.error('读取世界列表失败：' + (e?.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

/** 打开世界并进入沙盘（单活动世界：后端返回 409 时提示"先返回画廊"） */
async function continueWorld(w) {
  if (busy.value || !w) return
  if (w.mounted) {
    router.push(`/world/${w.id}/sandbox`)
    return
  }
  if (!w.mountable) {
    ElMessage.warning(`这个世界当前状态为「${w.worldStatusLabel || w.worldStatus}」，不能打开`)
    return
  }
  busy.value = true
  try {
    await openWorld(w.id)
    ElMessage.success(`已打开「${w.name || w.id}」`)
    router.push(`/world/${w.id}/sandbox`)
  } catch (e) {
    const msg = e?.message || '打开失败'
    ElMessage.error(msg)
    if (String(msg).includes('WORLD_BUSY')) {
      await load()
    }
  } finally {
    busy.value = false
  }
}

function goNewWorld() {
  router.push('/world/new')
}

/**
 * 世界初始化状态三态（null 表示"快照未知"——本次升级前建的老世界还没被任何打开动作刷新过）。
 *
 * <p>判据来自后端目录库快照（WorldSummaryVO.initStatus，由 WorldInitStatusService 在每次状态变化后刷新）：
 * DONE=必需步骤全满足；DEGRADED=必需满足但可选降级；NOT_STARTED/PARTIAL=没初始化完。
 * 未知时**不显示角标**：老世界多半早就跑起来了，硬报"未初始化完成"就是误报，用户会白跑一趟。</p>
 */
function initState(w) {
  const s = w?.initStatus
  if (!s) return ''
  if (s === 'DONE') return 'done'
  if (s === 'DEGRADED') return 'degraded'
  return 'pending'
}

/** 卡片上的初始化简述（DONE/DEGRADED 不需要简述，只有 pending 调它） */
function initBriefText(w) {
  const done = w?.initDone
  const total = w?.initTotal
  if (w?.initStatus === 'NOT_STARTED') return '未开始初始化'
  if (done == null || !total) return '初始化未完成'
  return `初始化未完成 ${done}/${total}`
}

/** 卡片上的「类别 · 程度」标签文案（两者都缺时不显示，避免出现一个孤零零的分隔点） */
function classifyText(w) {
  const genre = (w?.genre || '').trim()
  const tone = (w?.tone || '').trim()
  const toneLabel = TONE_LABELS[tone] || ''
  if (genre && toneLabel) return `${genre} · ${toneLabel}`
  return genre || toneLabel
}

/**
 * 世界程度的展示标签（与后端 WorldTone.label 一致；后端未返回时前端兜底）。
 * 只用于展示，真正的边界判定在后端 —— 前端不参与"该不该出势力斗争"的决策。
 */
const TONE_LABELS = { casual: '休闲', light: '轻度', standard: '标准', hard: '硬核' }

/** 直接进入该世界的初始化向导（只读状态，不会自动烧 AI） */
function goInit(w) {
  if (!w?.id) return
  router.push(`/world/${w.id}/init`)
}

/** 卡片菜单：初始化 / 改名 / 清单 / 导出 / 归档删除 */
async function onCommand(cmd, w) {
  if (cmd === 'init') {
    goInit(w)
    return
  }
  if (cmd === 'rename') {
    metaDialog.value = { visible: true, id: w.id, name: w.name || '', summary: w.summary || '' }
    return
  }
  if (cmd === 'cover') {
    coverTargetId.value = w.id
    coverInputRef.value?.click()
    return
  }
  if (cmd === 'cover-remove') {
    await removeCoverOf(w)
    return
  }
  if (cmd === 'export') {
    await exportWorldPackage(w)
    return
  }
  if (cmd === 'manifest') {
    try {
      const node = await fetchWorldManifest(w.id)
      manifestDialog.value = { visible: true, text: JSON.stringify(node, null, 2) }
    } catch (e) {
      ElMessage.error('读取世界清单失败：' + (e?.message || '未知错误'))
    }
    return
  }
  if (cmd === 'delete') {
    try {
      await ElMessageBox.confirm(
        `将把「${w.name || w.id}」的整个世界目录移入 归档目录（archive/deleted-worlds/），`
        + '并从世界列表移除。数据不会被真正删除，需要时仍可从归档目录找回。',
        '归档删除',
        { type: 'warning', confirmButtonText: '归档删除', cancelButtonText: '取消' }
      )
    } catch (_) {
      return
    }
    busy.value = true
    try {
      const r = await deleteWorld(w.id)
      ElMessage.success(`已归档删除${r?.archivedTo ? '：' + r.archivedTo : ''}`)
      await load()
    } catch (e) {
      ElMessage.error('归档删除失败：' + (e?.message || '未知错误'))
    } finally {
      busy.value = false
    }
  }
}

/**
 * 导出世界（.holzyn v3 = 世界目录 zip）。
 *
 * <p>后端要求"世界未挂载"才能导出（挂载中库文件被 H2 独占锁住，复制出来是事务中间态）。
 * 菜单项在挂载时会置灰，这里再兜一层提示——免得用户以为是应用卡了。</p>
 */
async function exportWorldPackage(w) {
  if (w.mounted) {
    ElMessage.warning('这个世界正在运行中，请先「返回画廊」关闭后再导出')
    return
  }
  busy.value = true
  const tip = ElMessage({ message: `正在打包「${w.name || w.id}」…`, duration: 0, type: 'info' })
  try {
    const { blob, fileName } = await exportWorld(w.id)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = fileName
    document.body.appendChild(a)
    a.click()
    a.remove()
    // 交还内存：几 GB 的图块包不释放会把标签页拖死
    setTimeout(() => URL.revokeObjectURL(url), 10000)
    ElMessage.success(`已导出 ${fileName}（${sizeText(blob.size)}）`)
  } catch (e) {
    ElMessage.error('导出失败：' + (e?.message || '未知错误'))
  } finally {
    tip.close()
    busy.value = false
  }
}

/** 打开文件选择框（导入世界） */
function pickImportFile() {
  fileInputRef.value?.click()
}

/** 选完文件 → 立即导入（失败保留原世界，后端会回滚已落位的目录） */
async function onImportFilePicked(ev) {
  const file = ev?.target?.files?.[0]
  if (ev?.target) ev.target.value = '' // 允许再次选同一个文件
  if (!file) return
  importing.value = true
  const tip = ElMessage({ message: `正在导入「${file.name}」…`, duration: 0, type: 'info' })
  try {
    const r = await importWorld(file)
    tip.close()
    ElMessage.success(
      `已导入「${r.name}」${r.uidRewritten ? '（世界标识冲突，已作为新世界）' : ''}`
      + `：角色 ${r.characterCount ?? 0} 位，世界时间 ${r.gameSecond ? formatCompactDayTime(r.gameSecond) : '未开始'}`
    )
    await load()
  } catch (e) {
    tip.close()
    ElMessage.error('导入失败：' + (e?.message || '未知错误'))
  } finally {
    importing.value = false
  }
}

/**
 * 选完封面文件 → 立即上传（后端校验格式/大小并等比缩小，成功后回写索引）。
 *
 * <p>先清空 input 的 value，否则用户第二次选同一张图不会触发 change——现象是"点了没反应"。</p>
 */
async function onCoverPicked(ev) {
  const file = ev?.target?.files?.[0]
  if (ev?.target) ev.target.value = ''
  const id = coverTargetId.value
  if (!file || !id) return
  busy.value = true
  const tip = ElMessage({ message: '正在保存封面…', duration: 0, type: 'info' })
  try {
    await uploadWorldCover(id, file)
    tip.close()
    ElMessage.success('封面已更新')
    await load()
  } catch (e) {
    tip.close()
    ElMessage.error('上传封面失败：' + (e?.message || '未知错误'))
  } finally {
    busy.value = false
    coverTargetId.value = null
  }
}

/** 移除世界封面（删文件 + 清索引，卡片回落到预备背面） */
async function removeCoverOf(w) {
  try {
    await ElMessageBox.confirm(
      `将移除「${w.name || w.id}」的封面，卡片改用默认背面显示。封面文件会从世界目录删除（随包导出时也不再携带）。`,
      '移除封面',
      { type: 'warning', confirmButtonText: '移除', cancelButtonText: '取消' }
    )
  } catch (_) {
    return
  }
  busy.value = true
  try {
    await removeWorldCover(w.id)
    ElMessage.success('封面已移除')
    await load()
  } catch (e) {
    ElMessage.error('移除封面失败：' + (e?.message || '未知错误'))
  } finally {
    busy.value = false
  }
}

/** 保存世界设置（只写目录库） */async function saveMeta() {
  const { id, name, summary } = metaDialog.value
  busy.value = true
  try {
    const updated = await updateWorldMeta(id, { name, summary })
    const idx = worlds.value.findIndex((w) => w.id === id)
    if (idx >= 0 && updated) worlds.value[idx] = { ...worlds.value[idx], ...updated }
    metaDialog.value.visible = false
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error('保存失败：' + (e?.message || '未知错误'))
  } finally {
    busy.value = false
  }
}

/** 世界时间文本（秒 → "第 N 天 HH:mm"；0 表示这个世界还没跑过） */
function gameClockText(w) {
  if (w?.gameSecond == null) return '未开始'
  if (!w.gameSecond) return '未开始'
  try {
    return formatCompactDayTime(w.gameSecond)
  } catch (_) {
    return String(w.gameSecond)
  }
}

/** 体积文本 */
function sizeText(bytes) {
  if (bytes == null) return '—'
  const mb = bytes / (1024 * 1024)
  if (mb >= 1) return mb.toFixed(1) + ' MB'
  return Math.max(1, Math.round(bytes / 1024)) + ' KB'
}

/** 相对时间（最后打开） */
function relativeText(iso) {
  if (!iso) return '从未打开'
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return ''
  const diff = Date.now() - t
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return Math.floor(diff / 60_000) + ' 分钟前'
  if (diff < 86_400_000) return Math.floor(diff / 3_600_000) + ' 小时前'
  return Math.floor(diff / 86_400_000) + ' 天前'
}

/** 状态标签色 */
function statusType(status) {
  switch (status) {
    case 'mounted': return 'success'
    case 'broken': return 'danger'
    case 'missing': return 'warning'
    case 'incompatible': return 'info'
    default: return ''
  }
}

/**
 * 卡片封面：有封面图就用封面，否则铺内置「预备背面」。
 *
 * <p>为什么在 JS 里拼 URL 而不是直接用 w.coverUrl：索引里存的是相对文件名（cover.png），
 * 由 {@link worldCoverUrl} 结合 id 现拼接口地址，导入换 id 后也不会指错世界。</p>
 */
function coverStyle(w) {
  return { backgroundImage: `url("${worldCoverUrl(w) || cardBackUrl}")` }
}

function dismissApiBanner() {
  bannerDismissed.value = true
  try { localStorage.setItem('holzyn.apiBannerDismissed', '1') } catch (_) { /* 隐私模式忽略 */ }
}

function openSettingsDialog(tab = 'general') {
  settingsRef.value?.open?.(tab)
}

/**
 * 查真实配置：任意层级（用户级或某个世界的项目级）有可用文本/决策模型 → 横幅不出现。
 *
 * <p>为什么要问后端：先前横幅只看 localStorage 的「关掉了」标记，从来不看配置，
 * 于是「填完 API 再进画廊」照样弹（用户反馈的缺陷）。判定合并了用户级 + 全部项目级供应商，
 * 前端在这里拉 /api/model-apis 只能看到用户级，会误判。</p>
 */
async function checkApiReady() {
  try {
    const res = await fetchAiReadiness()
    apiReady.value = res?.configured === true
  } catch (_) {
    // 查询失败按「未配置」处理，但已手动关闭过的不再打扰（bannerDismissed 仍生效）
    apiReady.value = false
  }
}

/** 窗口重新获得焦点时复查（在别处配好模型、切回来就不该再看到横幅） */
function onWindowFocus() {
  checkApiReady()
}

onMounted(async () => {
  try { bannerDismissed.value = localStorage.getItem('holzyn.apiBannerDismissed') === '1' } catch (_) { bannerDismissed.value = false }
  window.addEventListener('focus', onWindowFocus)
  await checkApiReady()
  load()
})

onBeforeUnmount(() => {
  window.removeEventListener('focus', onWindowFocus)
})
</script>

<style scoped>
/* 导入世界用的隐藏文件选择框（放在工具栏里，靠 .click() 唤起；不能 display:none 掉父级） */
.library-file-input {
  display: none;
}
.library-scene {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: auto;
}
.content-col {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 4px 4px 24px;
  min-height: 0;
}
.library-loading {
  min-height: 320px;
}
.library-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.section-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
}
.section-title {
  font-size: 15px;
  font-weight: 600;
}
.section-sub {
  /* 12px 小字压在半透明内容层上（背后是动态画布），--el-text-color-secondary 实测糊成一片 */
  font-size: 12px;
  color: var(--text-regular);
  margin-top: 2px;
}
.all-search {
  max-width: 240px;
}
.section-empty {
  padding: 24px;
  text-align: center;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.world-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 14px;
}
.world-card {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-radius: 12px;
  background: var(--hz-card-bg, var(--el-bg-color-overlay));
  border: 1px solid var(--el-border-color-lighter);
  transition: transform .16s ease, box-shadow .16s ease;
}
.world-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--el-box-shadow-light);
}
.world-card.is-broken {
  opacity: .72;
}
.card-cover {
  position: relative;
  height: 108px;
  display: flex;
  align-items: flex-end;
  padding: 10px;
  /* 兜底底色：封面图加载前 / 无封面时先铺深底，避免白闪 */
  background-color: #141d33;
  background-size: cover;
  background-position: center;
  cursor: pointer;
  isolation: isolate;
}
/* 底部渐变压暗：封面可能是亮色照片，白字世界名需要可读的底 */
.card-cover::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, rgba(8, 12, 22, 0) 38%, rgba(8, 12, 22, 0.62) 100%);
  pointer-events: none;
}
.cover-name {
  font-size: 14px;
  font-weight: 600;
  color: #fff;
  text-shadow: 0 1px 3px rgba(0, 0, 0, 0.65);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  position: relative;
  z-index: 1;
}
.cover-tag {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 1;
}
.card-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px 12px;}
.card-name {
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: flex;
  align-items: center;
  gap: 6px;
}
/* 类别 · 程度小标签：跟随世界名右侧，世界名过长时优先压缩名字 */
.card-name .name-tag { flex: 0 0 auto; font-weight: 400; }
.card-name .name-tag :deep(.el-tag__content) { font-size: 0.72rem; }
.card-summary {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  height: 32px;
  line-height: 16px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.card-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
/* 初始化未完成行：一行内放状态标签 + 直接入口，避免用户「打开世界后才被告知还没初始化」 */
.card-init {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 4px 6px;
  border-radius: var(--el-border-radius-base, 4px);
  background: var(--el-color-warning-light-9, rgba(230, 162, 60, 0.1));
}
.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 2px;
}
.foot-actions {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.manifest-pre {
  max-height: 60vh;
  overflow: auto;
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
