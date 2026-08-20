<template>
  <div class="world-edit-view">
    <!-- 头部：返回世界详情 + 标题 + 保存 -->
    <div class="page-header">
      <div>
        <div class="page-title">修改世界 · {{ project?.name || '' }}</div>
        <div class="page-desc">与「世界详情」同款分节展示，各区块就地编辑；地点详情以表格维护（增删改查）。保存后覆盖更新当前版本。</div>
      </div>
      <div class="header-ops">
        <el-button type="primary" :loading="saving" @click="saveWorld">
          <el-icon><HIcon name="Check" /></el-icon>&nbsp;保存世界观
        </el-button>
        <el-button @click="$router.push(`/project/${projectId}/world`)">
          <el-icon><HIcon name="Back" /></el-icon>&nbsp;返回世界详情
        </el-button>
      </div>
    </div>

    <!-- 分节编辑（与详情页同款左侧 Tab 导航，各区块就地编辑） -->
    <div v-if="!worldForm.name && !loadingDone" class="empty-card tech-card">
      <el-empty description="加载中…" />
    </div>
    <div v-else class="edit-body">
      <el-tabs v-model="activeSection" tab-position="left" class="world-tabs">
        <el-tab-pane label="基本信息" name="basic">
          <div class="world-card tech-card">
            <el-form label-width="90px" class="section-form">
              <el-form-item label="世界观名称" required>
                <el-input v-model="worldForm.name" placeholder="世界名称（必填）" maxlength="100" show-word-limit />
              </el-form-item>
              <el-row :gutter="16">
                <el-col :span="12">
                  <el-form-item label="题材">
                    <el-input v-model="worldForm.genre" placeholder="奇幻/科幻/都市/历史…" maxlength="50" />
                  </el-form-item>
                </el-col>
                <el-col :span="12">
                  <el-form-item label="时代背景">
                    <el-input v-model="worldForm.era" placeholder="如：蒸汽纪元" maxlength="50" />
                  </el-form-item>
                </el-col>
              </el-row>
            </el-form>
          </div>
        </el-tab-pane>

        <el-tab-pane label="地理设定" name="geography">
          <div class="world-card tech-card">
            <div class="section-title-row">
              <span class="section-title">地理设定</span>
              <span class="section-desc">AI 从该文本识别地点（地点详情 Tab 可一键提取）</span>
            </div>
            <el-input v-model="worldForm.geography" type="textarea" :rows="12" maxlength="5000" show-word-limit placeholder="地理/地图设定（≤5000 字）" />
          </div>
        </el-tab-pane>

        <el-tab-pane label="地点详情" name="locations">
          <div class="world-card tech-card">
            <div class="section-title-row">
              <span class="section-title">世界观地点表</span>
              <el-tag size="small" type="info" effect="plain">AI 提取 · 增删改查</el-tag>
            </div>
            <WorldLocationTable :project-id="projectId" editable extractable />
          </div>
        </el-tab-pane>

        <el-tab-pane label="势力阵营" name="factions">
          <div class="world-card tech-card">
            <div class="section-title-row"><span class="section-title">势力阵营</span></div>
            <el-input v-model="worldForm.factions" type="textarea" :rows="12" maxlength="5000" show-word-limit placeholder="主要势力/阵营（≤5000 字）" />
          </div>
        </el-tab-pane>

        <el-tab-pane label="规则体系" name="magic">
          <div class="world-card tech-card">
            <div class="section-title-row"><span class="section-title">规则体系</span></div>
            <el-input v-model="worldForm.magicSystem" type="textarea" :rows="12" maxlength="5000" show-word-limit placeholder="魔法/科技/规则体系（≤5000 字）" />
          </div>
        </el-tab-pane>

        <el-tab-pane label="文化风俗" name="culture">
          <div class="world-card tech-card">
            <div class="section-title-row"><span class="section-title">文化风俗</span></div>
            <el-input v-model="worldForm.culture" type="textarea" :rows="12" maxlength="5000" show-word-limit placeholder="文化/风俗（≤5000 字）" />
          </div>
        </el-tab-pane>

        <el-tab-pane label="历史背景" name="history">
          <div class="world-card tech-card">
            <div class="section-title-row"><span class="section-title">历史背景</span></div>
            <el-input v-model="worldForm.history" type="textarea" :rows="12" maxlength="5000" show-word-limit placeholder="历史背景（≤5000 字）" />
          </div>
        </el-tab-pane>

        <el-tab-pane label="完整世界观" name="freeText">
          <div class="world-card tech-card">
            <div class="section-title-row"><span class="section-title">自由文本（完整世界观）</span></div>
            <el-input v-model="worldForm.freeText" type="textarea" :rows="14" maxlength="5000" show-word-limit placeholder="完整世界观自由文本（角色卡生成的知识库注入源，越详细角色卡越贴合，≤5000 字）" />
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
/**
 * 世界详情修改页（模块化重构 V1.0；第十二轮重构：与详情页同款左 Tab 分节 + 就地编辑）。
 * <p>职责：世界观专属编辑页——采用与「世界详情」一致的左侧 Tab 分节展示（基本信息/地理设定/
 * 地点详情/势力阵营/规则体系/文化风俗/历史背景/完整世界观），每个区块就地编辑；
 * 地点详情以表格维护（WorldLocationTable：AI 提取 + 修改增删改查）。
 * 保存走现有 /api/projects/{id}/world-setting 覆盖更新接口；地点表由表格组件独立保存。</p>
 */
import { ref, reactive, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { fetchProject, fetchWorldSetting, saveWorldSetting } from '@/shared/api'
import WorldLocationTable from '@/shared/components/WorldLocationTable.vue'

const route = useRoute()
const router = useRouter()
const projectId = Number(route.params.id)

const project = ref(null)
const saving = ref(false)
const loadingDone = ref(false)
// 左侧导航当前分块（与详情页一致，地点详情在地理设定之后）
const activeSection = ref('basic')
const worldForm = reactive({ name: '', genre: '', era: '', geography: '', factions: '', magicSystem: '', culture: '', history: '', freeText: '' })

/** 加载项目详情 + 世界观（回填表单） */
async function loadAll() {
  try { project.value = await fetchProject(projectId) } catch (_) { /* 忽略 */ }
  try {
    const w = await fetchWorldSetting(projectId)
    if (w) {
      Object.assign(worldForm, {
        name: w.name, genre: w.genre, era: w.era, geography: w.geography, factions: w.factions,
        magicSystem: w.magicSystem, culture: w.culture, history: w.history, freeText: w.freeText
      })
    }
  } catch (_) { /* 无世界观时保持空表单 */ }
  loadingDone.value = true
}

/** 保存世界观（覆盖更新） */
async function saveWorld() {
  if (!worldForm.name.trim()) return ElMessage.warning('请填写世界观名称')
  saving.value = true
  try {
    await saveWorldSetting(projectId, { ...worldForm })
    ElMessage.success('世界观已保存（覆盖更新）')
    router.push(`/project/${projectId}/world`)
  } catch (e) { ElMessage.error(e.message || '保存失败') }
  finally { saving.value = false }
}

onMounted(loadAll)
</script>

<style scoped>
.world-edit-view { max-width: 1200px; margin: 0 auto; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; }
.page-title { font-size: 1.3rem; font-weight: 700; color: var(--text-primary); }
.page-desc { font-size: 0.85rem; color: var(--text-secondary); margin-top: 4px; }
.header-ops { display: flex; gap: 8px; flex-shrink: 0; }
.empty-card { padding: 40px 0; }
.edit-body { min-height: 360px; }
/* 与详情页同款左侧 Tab 分节 */
.world-tabs { width: 100%; }
.world-tabs :deep(.el-tabs__header) { margin-right: 0; }
.world-tabs :deep(.el-tabs__nav-wrap) { width: 128px; }
.world-tabs :deep(.el-tabs__content) { overflow: visible; }
.world-card { padding: 20px 24px; }
.section-form { padding-top: 4px; }
.section-title-row { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.section-title { font-weight: 700; color: var(--text-primary); }
.section-desc { font-size: 0.8rem; color: var(--text-secondary); }
</style>
