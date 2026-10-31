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
    <!-- 统一页头（内嵌设置弹窗时由弹窗标题与 Tab 标签承担） -->
    <PageHeader
      v-if="!embedded"
      title="Prompt 模板"
      :desc="projectId
        ? '项目级模板覆盖：解析顺序为项目覆盖 > 用户覆盖 > 内置，「恢复默认」删除项目覆盖回退低一级。'
        : '用户级模板覆盖（全局）：所有项目在无项目覆盖时回退使用此处保存的覆盖。'"
    />
    <!-- 项目级/用户级 Prompt 模板说明（双模式；内嵌设置弹窗时由弹窗顶部说明承担） -->
    <el-alert v-if="!embedded && projectId" type="success" :closable="false" show-icon
      title="Prompt 模板为项目级独立（随 .holzyn 包导入导出）。"
      description="解析规则：项目覆盖 > 用户覆盖 > 内置；「恢复默认」删除项目覆盖回退低一级。可编辑角色卡生成/对话系统/群聊编排/世界事件/行动生成等模板。" />
    <el-alert v-else-if="!embedded" type="success" :closable="false" show-icon
      title="Prompt 模板为「用户级覆盖」（全局，首页/未进入项目时配置）。"
      description="解析规则：项目覆盖 > 用户覆盖 > 内置；此处保存/恢复的是用户级覆盖，所有项目回退使用。项目级覆盖在对应项目空间 → 设置 → Prompt 模板 中配置。" />

    <div class="set-card">
      <!-- 横向功能分类 Tab：按 prompt 所属功能分组浏览（V3） -->
      <div class="tpl-tabs" role="tablist" aria-label="模板功能分类">
        <button
          v-for="c in categories"
          :key="c.key"
          class="tpl-tab"
          :class="{ active: activeCategory === c.key }"
          role="tab"
          :aria-selected="activeCategory === c.key"
          @click="activeCategory = c.key"
        >
          {{ c.label }}<span v-if="c.count" class="tpl-tab-count">{{ c.count }}</span>
        </button>
      </div>

      <el-table :data="filteredTemplates" border size="small" style="width: 100%" :key="activeCategory">
        <el-table-column prop="categoryLabel" label="分类" width="110">
          <template #default="{ row }">
            <span class="tpl-cat">{{ row.categoryLabel }}</span>
          </template>
        </el-table-column>
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
        <template #empty>
          <div class="set-tip">该分类下暂无模板。</div>
        </template>
      </el-table>
    </div>

    <!-- 模板编辑对话框（尺寸与其它设置弹窗统一：.settings-dialog；append-to-body 逃出设置弹窗正文的裁剪） -->
    <el-dialog v-model="tplDialog.visible" title="编辑 Prompt 模板" width="900px"
      align-center append-to-body class="h-dialog settings-dialog" :close-on-click-modal="false">
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
          <div class="set-tip" v-if="projectId">保存后将生成该模板的「项目覆盖」并立即生效（本项目优先）；「恢复默认」回退用户覆盖或内置。</div>
          <div class="set-tip" v-else>保存后将生成该模板的「用户级覆盖」并立即生效（所有项目回退使用）；「恢复默认」回退内置。</div>
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
 * 设置-Prompt 模板（V3：按功能分类横向 Tab 分组展示）。
 * <p>职责：项目/全局双模式 Prompt 模板管理——先按模板所属功能（对话交流/角色档案/世界观生成/…）
 * 分类成横向 Tab，选中某分类只展示该分类模板（编码/名称/版本/来源[项目覆盖/用户覆盖/内置]）+
 * 编辑视图（大文本域 + 占位符说明面板）+ 恢复默认（删除覆盖回退低一级）。</p>
 * <p>项目模式：查询/保存/重置均带 projectId（项目级覆盖，随 .holzyn 包导入导出）；
 * 全局模式（首页顶栏「设置」→ Prompt 模板）：projectId=null，即「用户级覆盖」（所有项目回退使用）。</p>
 * <p>数据来源：/api/prompt-templates?projectId=xxx 系列接口（不传=用户级）；分类来自
 * /api/prompt-templates/categories（与后端 CODE_CATEGORY 全量归类一致）。</p>
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchPromptTemplates, fetchPromptTemplateCategories, savePromptTemplate, resetPromptTemplate } from '@/shared/api'
import PageHeader from '@/shared/layout/PageHeader.vue'

const route = useRoute()
// 当前项目 ID：项目空间内存在；全局设置（首页 /settings）为 null（= 用户级覆盖）
const projectId = computed(() => (route.params.id ? Number(route.params.id) : null))

// embedded：内嵌到设置弹窗（GlobalSettingsDialog）时隐藏顶部模式说明，避免与弹窗说明重复
defineProps({ embedded: { type: Boolean, default: false } })

const templates = ref([])
/** 功能分类（有序：{key,label,count}；来自后端，与模板归类一致） */
const categories = ref([])
/** 当前选中的分类 Tab（null=全部） */
const activeCategory = ref(null)
const tplDialog = reactive({ visible: false, loading: false, code: '', name: '', template: '', systemMessage: '' })

/** 当前分类下的模板（null=全部） */
const filteredTemplates = computed(() =>
  activeCategory.value == null
    ? templates.value
    : templates.value.filter(t => (t.category || 'other') === activeCategory.value)
)

/** 常用占位符说明（与后端 PromptTemplateService 对齐） */
const placeholders = [
  '{{world_setting}}', '{{character_input}}', '{{recent_dialog}}', '{{character_card}}',
  '{{personality}}', '{{values}}', '{{quirks}}', '{{goals}}', '{{wounds}}',
  '{{crowd_snapshot}}', '{{environment_summary}}', '{{memory}}', '{{knowledge}}'
]

/** 加载模板列表 + 功能分类（项目级） */
async function loadTemplates() {
  try { templates.value = await fetchPromptTemplates(projectId.value) } catch (e) { ElMessage.error(e.message || '模板加载失败') }
  try {
    const cats = await fetchPromptTemplateCategories(projectId.value)
    categories.value = (cats || []).filter(c => c.count > 0)
  } catch (_) {
    // 分类接口不可用时回退为从模板行聚合（按 categoryLabel 分组）
    const seen = new Map()
    for (const t of templates.value) {
      const key = t.category || 'other'
      if (!seen.has(key)) seen.set(key, { key, label: t.categoryLabel || '其他', count: 0 })
      seen.get(key).count++
    }
    categories.value = [...seen.values()]
  }
  // 默认选中第一个有模板的分类（分类化展示，而非整体列表）
  if (activeCategory.value == null && categories.value.length) {
    activeCategory.value = categories.value[0].key
  }
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
/* V2.1：卡片/说明/提示走全局设置页规范（.settings-stack/.set-card/.set-tip） */
.tpl-placeholders { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.ph-tag { margin: 0; font-family: var(--font-mono, monospace); }

/* ===== 横向功能分类 Tab（V3） ===== */
.tpl-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding-bottom: 12px;
  margin-bottom: 4px;
  border-bottom: 1px solid var(--border-l1);
}
.tpl-tab {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 12px;
  border: 1px solid var(--border-l1);
  border-radius: 999px;
  background: var(--bg-layer-2);
  color: var(--text-secondary);
  font-size: 0.8rem;
  cursor: pointer;
  transition: color .15s var(--dsw-ease), background .15s var(--dsw-ease), border-color .15s var(--dsw-ease);
}
.tpl-tab:hover { color: var(--text-primary); border-color: var(--border-l2); }
.tpl-tab.active {
  color: var(--accent-text, var(--accent));
  background: var(--accent-soft);
  border-color: var(--accent-border, var(--accent));
  font-weight: 600;
}
.tpl-tab-count {
  min-width: 16px;
  height: 16px;
  padding: 0 5px;
  border-radius: 999px;
  background: var(--bg-layer-3);
  color: var(--text-tertiary);
  font-size: 0.68rem;
  line-height: 16px;
  text-align: center;
}
.tpl-tab.active .tpl-tab-count { background: var(--accent); color: #fff; }
.tpl-cat { font-size: 0.78rem; color: var(--text-tertiary); }
</style>
