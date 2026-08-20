<template>
  <div class="knowledge-view">
    <!-- 页头：说明 + 未配置 embedding 降级提示 -->
    <div class="page-header">
      <div>
        <div class="page-title">知识库</div>
        <div class="page-desc">项目知识（项目级）+ 角色知识（角色级）文档，保存时自动分块向量化；对话前自动 RAG 检索 top-k 注入。未配置 embedding 时自动降级文本关键词检索。</div>
      </div>
    </div>
    <el-alert v-if="!vectorAvailable" class="vec-alert" type="warning" :closable="false" show-icon>
      <template #title>未配置 embedding 供应商——当前为<b>文本关键词检索</b>降级模式。请在「设置 · API 配置」中为某供应商开启 embedding 并填写模型名（如 doubao-embedding / bge-m3），保存后点文档「重新向量化」。</template>
    </el-alert>

    <!-- 三 Tab：知识文档 / 检索预览 / 项目大事记 -->
    <el-tabs v-model="activeTab" class="kb-tabs" @tab-change="onTabChange">
      <!-- ============ Tab 1 · 知识文档 ============ -->
      <el-tab-pane label="知识文档" name="docs">
        <div class="tab-ops">
          <el-button type="primary" @click="openCreate">
            <el-icon><HIcon name="Plus" /></el-icon>&nbsp;新建文档
          </el-button>
          <el-button type="primary" plain @click="openUpload">
            <el-icon><HIcon name="Upload" /></el-icon>&nbsp;上传 txt/md
          </el-button>
        </div>
        <div v-loading="loading" class="table-card">
          <el-table :data="docs" empty-text="还没有知识文档，新建或上传 txt/md 开始建立知识库">
            <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
            <el-table-column label="归属" width="120">
              <template #default="{ row }">
                <el-tag size="small" :type="row.characterId ? 'primary' : 'info'" effect="plain">
                  {{ row.characterId ? (row.characterName || '角色级') : '项目级' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="字数" width="90" align="right">
              <template #default="{ row }">{{ (row.content || '').length }}</template>
            </el-table-column>
            <el-table-column label="向量化" width="170">
              <template #default="{ row }">
                <el-tag v-if="row.vectorized" size="small" type="success" effect="light">
                  {{ row.chunkCount }} 块 · {{ row.embeddingModel || '向量' }}
                </el-tag>
                <el-tag v-else size="small" type="warning" effect="plain">未向量化</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="updatedAt" label="更新时间" width="160">
              <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openView(row)">查看</el-button>
                <el-button link type="primary" @click="doReindex(row)">重新向量化</el-button>
                <el-button link type="danger" @click="doDelete(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>

      <!-- ============ Tab 2 · 检索预览 ============ -->
      <el-tab-pane label="检索预览" name="search">
        <div class="search-panel">
          <div class="search-row">
            <el-input v-model="searchQuery" placeholder="输入要检索的内容（将注入对话的知识片段）" @keyup.enter="doSearch" />
            <el-select v-model="searchCharacterId" clearable placeholder="角色级过滤" style="width: 160px">
              <el-option v-for="ch in characters" :key="ch.id" :label="ch.name" :value="ch.id" />
            </el-select>
            <el-button type="primary" :loading="searching" @click="doSearch">
              <el-icon><HIcon name="Search" /></el-icon>&nbsp;检索
            </el-button>
          </div>
          <div v-if="searchMode" class="search-mode-tag">
            <el-tag size="small" :type="searchMode === 'vector' ? 'success' : 'warning'">
              {{ searchMode === 'vector' ? '向量检索' : '文本关键词降级' }}
            </el-tag>
          </div>
          <div v-if="searched && searchHits.length === 0" class="search-empty">无命中结果</div>
          <div v-for="(h, i) in searchHits" :key="i" class="hit-card tech-card">
            <div class="hit-head">
              <span class="hit-title">【{{ h.title }}】</span>
              <el-tag size="small" type="info" effect="plain">相关度 {{ (h.score * 100).toFixed(1) }}</el-tag>
            </div>
            <div class="hit-text">{{ h.text }}</div>
          </div>
        </div>
      </el-tab-pane>

      <!-- ============ Tab 3 · 项目大事记（项目级长期记忆集中展示） ============ -->
      <el-tab-pane label="项目大事记" name="memory">
        <div class="mem-hint">项目级记忆集中展示：所有角色知晓的世界大事记，由对话抽取 + 世界模拟自动生成（P4）。此 Tab 仅展示项目级（角色为空）的记忆。</div>
        <div v-loading="memLoading" class="mem-list">
          <div v-if="projectMemories.length === 0" class="mem-empty">暂无项目级记忆</div>
          <div v-for="m in projectMemories" :key="m.id" class="mem-item">
            <div class="mem-head">
              <el-tag size="small" :type="m.kind === 'summary' ? 'primary' : 'info'" effect="plain">
                {{ m.kind === 'summary' ? '摘要' : '事实' }}
              </el-tag>
              <el-tag size="small" :type="m.importance >= 4 ? 'danger' : (m.importance >= 3 ? 'warning' : 'info')" effect="light">
                重要度 {{ m.importance }}
              </el-tag>
              <span class="mem-time">{{ (m.createdAt || '').slice(0, 16).replace('T', ' ') }}</span>
              <el-button class="mem-del" size="small" text type="danger" @click="doDeleteProjectMemory(m)" title="删除该记忆">
                <el-icon><HIcon name="Delete" /></el-icon>
              </el-button>
            </div>
            <div class="mem-content">{{ m.content }}</div>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 新建/编辑文档对话框 -->
    <el-dialog v-model="docDialog" :title="form.id ? '编辑文档' : '新建知识文档'" width="640px" :close-on-click-modal="false">
      <el-form ref="docFormRef" :model="form" :rules="docRules" label-width="90px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="form.title" maxlength="100" />
        </el-form-item>
        <el-form-item label="归属角色">
          <el-select v-model="form.characterId" clearable placeholder="不选 = 项目级（所有角色可检索）" style="width: 100%">
            <el-option v-for="ch in characters" :key="ch.id" :label="`${ch.name}（角色级）`" :value="ch.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="内容" prop="content">
          <el-input v-model="form.content" type="textarea" :rows="10" placeholder="粘贴知识内容（世界设定、事件记录、角色资料等），保存时自动分块向量化" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="docDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveDoc">保存</el-button>
      </template>
    </el-dialog>

    <!-- 查看文档对话框 -->
    <el-dialog v-model="viewDialog" :title="viewDoc?.title" width="720px">
      <div class="doc-meta">
        <el-tag size="small" :type="viewDoc?.characterId ? 'primary' : 'info'" effect="plain">
          {{ viewDoc?.characterId ? (viewDoc.characterName || '角色级') : '项目级' }}
        </el-tag>
        <el-tag v-if="viewDoc?.vectorized" size="small" type="success" effect="light">{{ viewDoc.chunkCount }} 块</el-tag>
        <el-tag v-else size="small" type="warning" effect="plain">未向量化</el-tag>
      </div>
      <pre class="doc-content">{{ viewDoc?.content }}</pre>
    </el-dialog>

    <!-- 上传文件对话框 -->
    <el-dialog v-model="uploadDialog" title="上传知识文档（txt / md）" width="520px">
      <el-form label-width="90px">
        <el-form-item label="归属角色">
          <el-select v-model="uploadCharacterId" clearable placeholder="不选 = 项目级" style="width: 100%">
            <el-option v-for="ch in characters" :key="ch.id" :label="`${ch.name}（角色级）`" :value="ch.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="选择文件">
          <el-upload drag :auto-upload="false" :limit="1" accept=".txt,.md,.markdown" :on-change="onFileChange" :on-remove="() => (uploadFile = null)">
            <el-icon class="el-icon--upload"><HIcon name="UploadFilled" /></el-icon>
            <div class="el-upload__text">拖拽文件到此处，或 <em>点击选择</em></div>
            <template #tip><div class="el-upload__tip">仅支持 txt / md / markdown 文本文件（≤5MB），文件名作为文档标题</div></template>
          </el-upload>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uploadDialog = false">取消</el-button>
        <el-button type="primary" :loading="uploading" :disabled="!uploadFile" @click="doUpload">上传并入库</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 知识库页（前端布局重构 V1.0，设计文档 §3.9）。
 * <p>职责：以三个 Tab 组织知识库全部能力——
 * ① 知识文档：文档列表（归属/字数/向量化状态/更新时间/操作）+ 新建/上传；
 * ② 检索预览：检索输入 + 角色级过滤 + RAG 命中片段（向量/文本降级标记）；
 * ③ 项目大事记：项目级长期记忆集中展示（与世界详情页项目级记忆同源）。</p>
 * <p>数据来源：/api/projects/{id}/knowledge-docs、memories 系列接口。</p>
 */
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchKnowledgeDocs, createKnowledgeDoc, updateKnowledgeDoc, deleteKnowledgeDoc,
  reindexKnowledgeDoc, uploadKnowledgeDoc, searchKnowledgeDoc, fetchCharacters,
  fetchModelApis, fetchMemories, deleteMemory
} from '@/shared/api'

const route = useRoute()
const projectId = Number(route.params.id)

// ===== Tab 状态 =====
const activeTab = ref('docs')

// ===== 列表 =====
const docs = ref([])
const loading = ref(false)
const characters = ref([])
const vectorAvailable = ref(false)

// ===== 新建/编辑 =====
const docDialog = ref(false)
const saving = ref(false)
const docFormRef = ref(null)
const form = reactive({ id: null, title: '', content: '', characterId: null })
const docRules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  content: [{ required: true, message: '请输入内容', trigger: 'blur' }]
}

// ===== 查看 =====
const viewDialog = ref(false)
const viewDoc = ref(null)

// ===== 上传 =====
const uploadDialog = ref(false)
const uploading = ref(false)
const uploadFile = ref(null)
const uploadCharacterId = ref(null)

// ===== 检索 =====
const searchQuery = ref('')
const searchCharacterId = ref(null)
const searching = ref(false)
const searched = ref(false)
const searchHits = ref([])
const searchMode = ref('')

// ===== 项目大事记（P4-1 项目级长期记忆） =====
const memLoading = ref(false)
const projectMemories = ref([])

onMounted(async () => {
  await Promise.all([loadDocs(), loadCharacters(), loadVectorAvailable()])
})

/** 加载文档列表 */
async function loadDocs() {
  loading.value = true
  try {
    docs.value = await fetchKnowledgeDocs(projectId)
  } catch (e) {
    ElMessage.error(e.message || '加载知识库失败')
  } finally {
    loading.value = false
  }
}

/** 加载项目角色（归属选择/过滤用） */
async function loadCharacters() {
  try {
    characters.value = await fetchCharacters(projectId)
  } catch (_) {
    characters.value = []
  }
}

/** 探测是否配置了 embedding 供应商（项目级优先/用户级回退） */
async function loadVectorAvailable() {
  try {
    const apis = await fetchModelApis(projectId)
    vectorAvailable.value = apis.some((a) => a.embeddingEnabled === 1 && a.embeddingModel)
  } catch (_) {
    vectorAvailable.value = false
  }
}

/** 进入大事记 Tab 并加载项目级记忆（角色为空） */
async function loadProjectMemories() {
  memLoading.value = true
  try {
    const page = await fetchMemories(projectId, { page: 1, size: 100 })
    projectMemories.value = (page.list || []).filter((m) => !m.characterId)
  } catch (e) {
    ElMessage.error(e.message || '加载大事记失败')
    projectMemories.value = []
  } finally {
    memLoading.value = false
  }
}

/**
 * Tab 切换回调：切到「项目大事记」Tab 时重新拉取项目级记忆。
 * <p>背景：项目级记忆由对话抽取/世界模拟异步生成，若仅在页面挂载时加载一次，
 * 后续新增的大记事不会自动出现。切 Tab 时重新拉取保证所见即最新。</p>
 *
 * @param name 切换到的 Tab 名称（docs/search/memory）
 */
function onTabChange(name) {
  if (name === 'memory') loadProjectMemories()
}

/** 删除项目级记忆（软删） */
async function doDeleteProjectMemory(m) {
  try {
    await ElMessageBox.confirm(`确认删除该条记忆？删除后不再注入所有角色对话。`, '删除确认', { type: 'warning' })
    await deleteMemory(m.id)
    ElMessage.success('已删除')
    projectMemories.value = projectMemories.value.filter((x) => x.id !== m.id)
  } catch (_) { /* 用户取消 */ }
}

/** 打开新建对话框 */
function openCreate() {
  Object.assign(form, { id: null, title: '', content: '', characterId: null })
  docDialog.value = true
}

/** 保存（新建/编辑） */
async function saveDoc() {
  await docFormRef.value.validate()
  saving.value = true
  try {
    if (form.id) {
      await updateKnowledgeDoc(form.id, { title: form.title, content: form.content, characterId: form.characterId })
      ElMessage.success('已更新（正文变化已重新向量化）')
    } else {
      await createKnowledgeDoc(projectId, { title: form.title, content: form.content, characterId: form.characterId })
      ElMessage.success('已保存并向量化')
    }
    docDialog.value = false
    await loadDocs()
    await loadVectorAvailable()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

/** 打开查看对话框 */
function openView(row) {
  viewDoc.value = row
  viewDialog.value = true
}

/** 重新向量化 */
async function doReindex(row) {
  try {
    await reindexKnowledgeDoc(row.id)
    ElMessage.success('已重新向量化')
    await loadDocs()
  } catch (e) {
    ElMessage.error(e.message || '重新向量化失败')
  }
}

/** 删除文档 */
async function doDelete(row) {
  try {
    await ElMessageBox.confirm(`确认删除知识文档「${row.title}」？`, '删除确认', { type: 'warning' })
  } catch (_) {
    return
  }
  try {
    await deleteKnowledgeDoc(row.id)
    ElMessage.success('已删除')
    await loadDocs()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

/** 打开上传对话框 */
function openUpload() {
  uploadFile.value = null
  uploadCharacterId.value = null
  uploadDialog.value = true
}

/** 选择文件回调 */
function onFileChange(file) {
  uploadFile.value = file.raw
}

/** 上传并入库 */
async function doUpload() {
  if (!uploadFile.value) return
  uploading.value = true
  try {
    const doc = await uploadKnowledgeDoc(projectId, uploadFile.value, uploadCharacterId.value || undefined)
    ElMessage.success(`已上传「${doc.title}」并向量化`)
    uploadDialog.value = false
    await loadDocs()
    await loadVectorAvailable()
  } catch (e) {
    ElMessage.error(e.message || '上传失败')
  } finally {
    uploading.value = false
  }
}

/** 执行检索 */
async function doSearch() {
  if (!searchQuery.value.trim()) {
    ElMessage.warning('请输入检索内容')
    return
  }
  searching.value = true
  try {
    const hits = await searchKnowledgeDoc(projectId, {
      query: searchQuery.value,
      characterId: searchCharacterId.value || undefined,
      topK: 5
    })
    searchHits.value = hits
    searched.value = true
    searchMode.value = vectorAvailable.value ? 'vector' : 'text'
  } catch (e) {
    ElMessage.error(e.message || '检索失败')
  } finally {
    searching.value = false
  }
}

/** 时间格式化 */
function formatTime(t) {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(0, 19)
}
</script>

<style scoped>
.knowledge-view { max-width: 1200px; margin: 0 auto; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; }
.page-title { font-size: 1.3rem; font-weight: 700; color: var(--text-primary); margin-bottom: 6px; }
.page-desc { font-size: 0.85rem; color: var(--text-secondary); }
.vec-alert { margin-bottom: 12px; }
.kb-tabs { background: var(--bg-layer-1); border-radius: var(--radius-lg); border: 1px solid var(--border-light); padding: 8px 20px 16px; }
.tab-ops { display: flex; gap: 8px; justify-content: flex-end; margin-bottom: 12px; }
.table-card { padding: 8px; }
.search-panel { padding: 12px 4px; }
.search-row { display: flex; gap: 8px; margin-bottom: 12px; }
.search-mode-tag { margin-bottom: 10px; }
.search-empty { color: var(--text-secondary); text-align: center; padding: 24px 0; }
.hit-card { padding: 12px 14px; margin-bottom: 10px; }
.hit-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
.hit-title { font-weight: 600; font-size: 0.9rem; }
.hit-text { font-size: 0.86rem; color: var(--text-secondary); line-height: 1.6; }
.doc-meta { display: flex; gap: 8px; margin-bottom: 12px; }
.doc-content { white-space: pre-wrap; word-break: break-word; font-family: inherit; font-size: 0.9rem; line-height: 1.7; max-height: 60vh; overflow-y: auto; background: var(--bg-layer-2); padding: 14px; border-radius: 8px; }
/* 项目大事记 */
.mem-hint { font-size: 0.8rem; color: var(--text-secondary); margin-bottom: 12px; }
.mem-list { max-height: 60vh; overflow-y: auto; display: flex; flex-direction: column; gap: 10px; }
.mem-empty { color: var(--text-secondary); text-align: center; padding: 24px 0; }
.mem-item { border: 1px solid var(--border-light); border-radius: 8px; padding: 10px 12px; background: var(--bg-layer-2); }
.mem-head { display: flex; align-items: center; gap: 8px; }
.mem-time { font-size: 0.72rem; color: var(--text-secondary); }
.mem-del { margin-left: auto; opacity: 0; transition: opacity .15s; }
.mem-item:hover .mem-del { opacity: 1; }
.mem-content { font-size: 0.86rem; color: var(--text-primary); margin-top: 8px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
</style>
