<template>
  <div class="manual-add-panel">
    <!-- 顶部说明 -->
    <div class="page-header">
      <div>
        <div class="page-title">手动添加</div>
        <div class="page-desc">在本页一次性填写项目信息与完整世界观设定（均非必填）；创建后可选是否进行默认世界初始化。</div>
      </div>
      <div class="header-ops">
        <el-button type="primary" :loading="creating" @click="doCreate">
          <el-icon><HIcon name="Plus" /></el-icon>&nbsp;创建项目
        </el-button>
      </div>
    </div>

    <!-- 项目基本信息 -->
    <div class="block-card tech-card">
      <div class="block-title">项目信息</div>
      <el-form :model="projectForm" label-width="90px" class="proj-form" @submit.prevent>
        <el-row :gutter="16">
          <el-col :span="10">
            <el-form-item label="项目名称">
              <el-input v-model="projectForm.name" placeholder="如：星陨之都（建议填写）" maxlength="100" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="项目编码">
              <el-input v-model="projectForm.code" placeholder="可选" maxlength="50" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="项目概要">
          <el-input v-model="projectForm.summary" type="textarea" :rows="2" placeholder="一句话说明这个世界/作品（可选）" />
        </el-form-item>
      </el-form>
    </div>

    <!-- 世界观设定：左侧 Tab 分块，布局参考世界详情页 -->
    <div class="block-card tech-card world-card">
      <div class="block-title">世界观设定<span class="block-tip">（均非必填，可留空稍后在项目空间补充）</span></div>
      <el-tabs v-model="activeSection" tab-position="left" class="world-tabs">
        <el-tab-pane label="基本信息" name="basic">
          <div class="wc-grid">
            <div class="wc-cell">
              <div class="wc-label">世界观名称</div>
              <el-input v-model="worldForm.name" placeholder="如：星陨之都世界观" maxlength="100" />
            </div>
            <div class="wc-cell">
              <div class="wc-label">题材</div>
              <el-input v-model="worldForm.genre" placeholder="奇幻 / 科幻 / 都市 / 历史…" maxlength="50" />
            </div>
            <div class="wc-cell">
              <div class="wc-label">时代背景</div>
              <el-input v-model="worldForm.era" placeholder="如：上古诸神时代 / 近未来" maxlength="50" />
            </div>
          </div>
        </el-tab-pane>
        <el-tab-pane label="地理设定" name="geography">
          <div class="wc-cell">
            <div class="wc-label">地理设定</div>
            <el-input v-model="worldForm.geography" type="textarea" :rows="10" placeholder="地理/地图/地貌/生态/版图…" />
          </div>
        </el-tab-pane>
        <el-tab-pane label="势力格局" name="factions">
          <div class="wc-cell">
            <div class="wc-label">势力格局</div>
            <el-input v-model="worldForm.factions" type="textarea" :rows="10" placeholder="势力/阵营/组织/政治格局…" />
          </div>
        </el-tab-pane>
        <el-tab-pane label="规则体系" name="magic">
          <div class="wc-cell">
            <div class="wc-label">规则体系</div>
            <el-input v-model="worldForm.magicSystem" type="textarea" :rows="10" placeholder="规则/体系/能力/法则/魔法/科技…" />
          </div>
        </el-tab-pane>
        <el-tab-pane label="社会文化" name="culture">
          <div class="wc-cell">
            <div class="wc-label">社会文化</div>
            <el-input v-model="worldForm.culture" type="textarea" :rows="10" placeholder="文化/风俗/民俗/社会/传统/信仰…" />
          </div>
        </el-tab-pane>
        <el-tab-pane label="历史脉络" name="history">
          <div class="wc-cell">
            <div class="wc-label">历史脉络</div>
            <el-input v-model="worldForm.history" type="textarea" :rows="10" placeholder="历史/脉络/纪元/大事/时间线…" />
          </div>
        </el-tab-pane>
        <el-tab-pane label="补充设定" name="supplement">
          <div class="wc-cell">
            <div class="wc-label">补充设定 / 自由文本</div>
            <el-input v-model="worldForm.freeText" type="textarea" :rows="10" placeholder="上述分类之外的其他设定、补充内容…" />
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
/**
 * 手动添加面板（2026-08-19 新建项目页重构）。
 * <p>职责：一次性填写项目信息 + 完整世界观设定（均非必填，布局参考项目空间世界详情页左侧 Tab 分块）；
 * 创建项目 + 落世界观表后，弹窗询问是否进行默认世界初始化（是→跳专属世界初始化页 / 否→进项目空间仪表盘）。</p>
 */
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createProject, saveWorldSetting } from '@/shared/api'

const router = useRouter()

const creating = ref(false)
// 左侧 Tab 当前分块（基本信息/地理设定/势力格局/规则体系/社会文化/历史脉络/补充设定）
const activeSection = ref('basic')

const projectForm = reactive({ name: '', code: '', summary: '' })
const worldForm = reactive({
  name: '', genre: '', era: '',
  geography: '', factions: '', magicSystem: '', culture: '', history: '', freeText: ''
})

/** 创建项目 + 落世界观表 → 弹窗询问是否默认世界初始化 */
async function doCreate() {
  creating.value = true
  try {
    const p = await createProject({
      name: projectForm.name.trim() || '未命名项目',
      code: projectForm.code || null,
      summary: projectForm.summary || null
    })
    // 世界观字段有任意填写才落库（避免创建空世界观行）
    const w = worldForm
    if (w.name || w.genre || w.era || w.geography || w.factions || w.magicSystem
      || w.culture || w.history || w.freeText) {
      await saveWorldSetting(p.id, {
        name: w.name.trim() || p.name, genre: w.genre || null, era: w.era || null,
        geography: w.geography || null, factions: w.factions || null, magicSystem: w.magicSystem || null,
        culture: w.culture || null, history: w.history || null, freeText: w.freeText || null
      })
    }
    ElMessage.success('项目已创建')
    askInit(p.id, p.name)
  } catch (e) {
    ElMessage.error(e.message || '创建失败')
  } finally {
    creating.value = false
  }
}

/** 弹窗询问是否进行默认世界初始化（是→初始化页 / 否→项目空间仪表盘） */
function askInit(projectId, projectName) {
  ElMessageBox.confirm(
    `项目「${projectName}」已创建。是否进行默认世界初始化？\n\n默认初始化将自动执行 6 步：世界观地点 → 角色卡 → 字段字典与普通 NPC → 关系拓扑 → 世界时间 → 知识向量化（全部 AI 自动，可在独立初始化页实时查看进度）。`,
    '默认世界初始化',
    {
      confirmButtonText: '进行初始化',
      cancelButtonText: '暂不初始化',
      distinguishCancelAndClose: true,
      type: 'info'
    }
  ).then(() => {
    router.replace(`/project/${projectId}/init`)
  }).catch((action) => {
    if (action === 'cancel') {
      router.replace(`/project/${projectId}/dashboard`)
    }
    // close（点 X）留在本页
  })
}
</script>

<style scoped>
.manual-add-panel { max-width: 1100px; margin: 0 auto; display: flex; flex-direction: column; gap: 14px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; }
.page-title { font-size: 1.25rem; font-weight: 700; color: var(--text-primary); }
.page-desc { font-size: 0.82rem; color: var(--text-secondary); margin-top: 4px; }
.header-ops { display: flex; gap: 8px; }
.block-card { padding: 18px 22px; }
.block-title { font-weight: 700; color: var(--text-primary); margin-bottom: 12px; }
.block-tip { font-size: 0.75rem; color: var(--text-tertiary); font-weight: 400; margin-left: 8px; }
.proj-form { max-width: 760px; }
.world-card { min-height: 420px; }
.world-tabs :deep(.el-tabs__header) { margin-right: 0; }
.world-tabs :deep(.el-tabs__nav-wrap) { width: 128px; }
.world-tabs :deep(.el-tabs__content) { overflow: visible; }
.wc-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 16px; }
.wc-cell { min-width: 0; }
.wc-label { font-size: 0.78rem; color: var(--text-secondary); font-weight: 600; margin-bottom: 6px; }
</style>
