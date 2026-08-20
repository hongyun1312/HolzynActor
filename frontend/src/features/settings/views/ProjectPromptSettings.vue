<template>
  <div class="project-prompt-settings">
    <!-- 项目级/用户级 Prompt 模板说明（双模式；内嵌设置弹窗时由弹窗顶部说明承担） -->
    <el-alert v-if="!embedded && projectId" type="success" :closable="false" show-icon class="pt-alert"
      title="Prompt 模板为项目级独立（随 .holzyn 包导入导出）。"
      description="解析规则：项目覆盖 > 用户覆盖 > 内置；「恢复默认」删除项目覆盖回退低一级。可编辑角色卡生成/对话系统/群聊编排/世界事件/行动生成等模板。" />
    <el-alert v-else-if="!embedded" type="success" :closable="false" show-icon class="pt-alert"
      title="Prompt 模板为「用户级覆盖」（全局，首页/未进入项目时配置）。"
      description="解析规则：项目覆盖 > 用户覆盖 > 内置；此处保存/恢复的是用户级覆盖，所有项目回退使用。项目级覆盖在对应项目空间 → 设置 → Prompt 模板 中配置。" />

    <div class="pt-table tech-card">
      <el-table :data="templates" border size="small" style="width: 100%">
        <el-table-column prop="name" label="模板名称" min-width="130" />
        <el-table-column prop="code" label="编码" min-width="150" />
        <el-table-column prop="version" label="版本" width="70" />
        <el-table-column label="来源" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.projectScope" size="small" type="success" effect="plain">项目覆盖</el-tag>
            <el-tag v-else-if="row.isOverride" size="small" type="warning" effect="plain">用户覆盖</el-tag>
            <el-tag v-else size="small" type="info" effect="plain">内置</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button size="small" type="primary" text @click="openTemplateEdit(row)">编辑</el-button>
            <el-button v-if="row.isOverride || row.projectScope" size="small" type="danger" text @click="doResetTemplate(row)">恢复默认</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 模板编辑对话框 -->
    <el-dialog v-model="tplDialog.visible" title="编辑 Prompt 模板" width="680px">
      <el-form label-width="70px">
        <el-form-item label="名称">
          <el-input v-model="tplDialog.name" maxlength="100" />
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="tplDialog.template" type="textarea" :rows="14" placeholder="模板内容，占位符如 {{world_setting}} {{character_input}} 会在渲染时替换" />
        </el-form-item>
        <el-form-item label="系统提示词">
          <el-input v-model="tplDialog.systemMessage" type="textarea" :rows="2"
            placeholder="可选：随模板落库的系统提示词（如「你只输出严格的 JSON，不输出任何其他文字。」），代码不再硬编码" />
        </el-form-item>
        <el-form-item label="占位符">
          <div class="tpl-placeholders">
            <el-tag v-for="p in placeholders" :key="p" size="small" effect="plain" class="ph-tag">{{ p }}</el-tag>
          </div>
          <div class="tpl-tip" v-if="projectId">保存后将生成该模板的「项目覆盖」并立即生效（本项目优先）；「恢复默认」回退用户覆盖或内置。</div>
          <div class="tpl-tip" v-else>保存后将生成该模板的「用户级覆盖」并立即生效（所有项目回退使用）；「恢复默认」回退内置。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="tplDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="tplDialog.loading" @click="saveTemplate">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 设置-Prompt 模板（前端布局重构 V1.0，设计文档 §3.10.3；后端项目化改造 V2.0；双模式改造 V1.2）。
 * <p>职责：项目/全局双模式 Prompt 模板管理——模板列表（编码/名称/版本/来源[项目覆盖/用户覆盖/内置]）+
 * 编辑视图（大文本域 + 占位符说明面板）+ 恢复默认（删除覆盖回退低一级）。
 * 项目模式：查询/保存/重置均带 projectId（项目级覆盖，随 .holzyn 包导入导出）；
 * 全局模式（首页顶栏「设置」→ Prompt 模板）：projectId=null，即「用户级覆盖」（所有项目回退使用）。</p>
 * <p>数据来源：/api/prompt-templates?projectId=xxx 系列接口（不传=用户级）。</p>
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchPromptTemplates, savePromptTemplate, resetPromptTemplate } from '@/shared/api'

const route = useRoute()
// 当前项目 ID：项目空间内存在；全局设置（首页 /settings）为 null（= 用户级覆盖）
const projectId = computed(() => (route.params.id ? Number(route.params.id) : null))

// embedded：内嵌到设置弹窗（GlobalSettingsDialog）时隐藏顶部模式说明，避免与弹窗说明重复
defineProps({ embedded: { type: Boolean, default: false } })

const templates = ref([])
const tplDialog = reactive({ visible: false, loading: false, code: '', name: '', template: '', systemMessage: '' })

/** 常用占位符说明（与后端 PromptTemplateService 对齐） */
const placeholders = [
  '{{world_setting}}', '{{character_input}}', '{{recent_dialog}}', '{{character_card}}',
  '{{personality}}', '{{values}}', '{{quirks}}', '{{goals}}', '{{wounds}}',
  '{{crowd_snapshot}}', '{{environment_summary}}', '{{memory}}', '{{knowledge}}'
]

/** 加载模板列表（项目级） */
async function loadTemplates() {
  try { templates.value = await fetchPromptTemplates(projectId.value) } catch (e) { ElMessage.error(e.message || '模板加载失败') }
}

/** 打开模板编辑 */
function openTemplateEdit(row) {
  tplDialog.code = row.code
  tplDialog.name = row.name
  tplDialog.template = row.template
  tplDialog.systemMessage = row.systemMessage || ''
  tplDialog.visible = true
}

/** 保存模板（创建项目级覆盖） */
async function saveTemplate() {
  if (!tplDialog.template || !tplDialog.template.trim()) return ElMessage.warning('模板内容不能为空')
  tplDialog.loading = true
  try {
    await savePromptTemplate(tplDialog.code, {
      name: tplDialog.name,
      template: tplDialog.template,
      systemMessage: tplDialog.systemMessage || undefined
    }, projectId.value)
    ElMessage.success(projectId.value ? '模板已保存（项目覆盖生效）' : '模板已保存（用户级覆盖生效）')
    tplDialog.visible = false
    await loadTemplates()
  } catch (e) { ElMessage.error(e.message || '保存失败') }
  finally { tplDialog.loading = false }
}

/** 恢复默认（删除项目覆盖，回退用户覆盖/内置） */
async function doResetTemplate(row) {
  try {
    await ElMessageBox.confirm(`确认将「${row.name}」恢复默认？项目覆盖将被删除。`, '恢复确认', { type: 'warning' })
    await resetPromptTemplate(row.code, projectId.value)
    ElMessage.success('已恢复默认')
    await loadTemplates()
  } catch (_) { /* 用户取消或失败 */ }
}

onMounted(loadTemplates)
</script>

<style scoped>
.project-prompt-settings { }
.pt-alert { margin-bottom: 14px; }
.pt-table { padding: 8px; }
.tpl-placeholders { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.ph-tag { margin: 0; font-family: var(--font-mono, monospace); }
.tpl-tip { font-size: 0.78rem; color: var(--text-secondary); }
</style>
