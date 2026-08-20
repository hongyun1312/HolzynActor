<template>
  <div class="character-view">
    <!-- 左栏：角色列表 -->
    <div class="char-side">
      <div class="side-header">
        <span>NPC 角色（{{ characters.length }}）</span>
        <el-button size="small" type="primary" text @click="openCharDialog()"><el-icon><HIcon name="Plus" /></el-icon>新增</el-button>
      </div>
      <div class="side-filter">
        <el-select v-model="typeFilter" size="small" clearable placeholder="类型筛选" style="width: 100%">
          <el-option label="特殊型 NPC" value="special" />
          <el-option label="普通型" value="common" />
        </el-select>
      </div>
      <div v-if="filteredChars.length === 0" class="side-empty">暂无角色，点击「新增」添加</div>
      <div v-else class="char-list">
        <div
          v-for="ch in filteredChars"
          :key="ch.id"
          class="char-item"
          :class="{ active: currentChar?.id === ch.id }"
          @click="selectChar(ch)"
        >
          <el-avatar :size="34" class="char-avatar">{{ ch.name?.charAt(0) }}</el-avatar>
          <div class="char-meta">
            <div class="char-name">
              {{ ch.name }}
              <el-tag v-if="ch.isProtagonist === 1" size="small" type="danger">主角</el-tag>
            </div>
            <div class="char-title">
              {{ ch.title || '—' }}
              <el-tag v-if="ch.type === 'common'" size="small" type="info">普通型</el-tag>
            </div>
          </div>
          <div class="char-ops" @click.stop>
            <el-button size="small" text type="primary" @click="openCharDialog(ch)">编辑</el-button>
            <el-button size="small" text type="danger" @click="doRemoveChar(ch)">删除</el-button>
          </div>
        </div>
      </div>
      <div class="side-foot">
        <el-button type="primary" :loading="generatingAll" :disabled="characters.length === 0" size="small" style="width: 100%" @click="generateAll">
          <el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;一键生成全部角色卡
        </el-button>
      </div>
    </div>

    <!-- 右栏：角色详情，分 Tab（「关系拓扑」无需选中角色即可查看全角色网络图，其余 Tab 需选中角色） -->
    <div class="char-main">
      <!-- 顶部操作条：仅选中角色时显示 -->
      <div v-if="currentChar" class="detail-header">
          <div class="detail-name">
            {{ currentChar.name }}
            <el-tag v-if="currentChar.isProtagonist === 1" size="small" type="danger">主角</el-tag>
            <el-tag v-if="currentChar.type === 'common'" size="small" type="info">普通型</el-tag>
          </div>
          <div class="detail-ops">
            <el-tag v-if="roleCard" size="small" effect="plain" class="card-version">v{{ roleCard.version }}</el-tag>
            <el-button size="small" :loading="generatingMap[currentChar.id]" @click="generateOne(currentChar)">
              <el-icon><HIcon name="MagicStick" /></el-icon>&nbsp;{{ roleCard ? '重新生成' : '生成角色卡' }}
            </el-button>
            <el-button size="small" plain @click="openVersions">
              <el-icon><HIcon name="Clock" /></el-icon>&nbsp;版本历史
            </el-button>
          </div>
        </div>

        <el-tabs v-model="infoTab" class="info-tabs" @tab-change="onInfoTabChange">
          <!-- Tab 1 · 概览：基础档案只读（需选中角色） -->
          <el-tab-pane label="概览" name="overview">
            <div v-if="!currentChar" class="main-empty">
              <el-empty description="从左侧选择一个角色查看详情" />
            </div>
            <template v-else>
            <div class="ov-grid">
              <div class="ov-item"><span class="k">姓名</span><span class="v">{{ currentChar.name }}</span></div>
              <div class="ov-item"><span class="k">头衔</span><span class="v">{{ currentChar.title || '—' }}</span></div>
              <div class="ov-item"><span class="k">类型</span><span class="v">{{ currentChar.type === 'common' ? '普通型' : '特殊型 NPC' }}</span></div>
              <div class="ov-item"><span class="k">是否主角</span><span class="v">{{ currentChar.isProtagonist === 1 ? '是' : '否' }}</span></div>
              <div class="ov-item"><span class="k">重要度</span><span class="v"><el-rate :model-value="currentChar.importance" disabled size="small" /></span></div>
              <div class="ov-item"><span class="k">角色卡</span><span class="v">
                <el-tag v-if="currentChar.hasCard" size="small" type="success">已生成</el-tag>
                <el-tag v-else size="small" type="info">未生成</el-tag>
              </span></div>
            </div>
            <div class="ov-detail">
              <div class="ov-detail-label">详细信息</div>
              <div class="ov-detail-text">{{ currentChar.detail || '—' }}</div>
            </div>
            <el-button size="small" text type="primary" @click="openCharDialog(currentChar)">编辑档案</el-button>
            </template>
          </el-tab-pane>

          <!-- Tab 2 · 结构化角色卡（JSON 前端解析渲染；需选中角色） -->
          <el-tab-pane label="结构化角色卡" name="card">
            <div v-if="!currentChar" class="main-empty">
              <el-empty description="从左侧选择一个角色查看详情" />
            </div>
            <template v-else>
            <div v-if="!roleCard" class="main-empty">
              <el-empty description="尚未生成角色卡">
                <el-button type="primary" :loading="generatingMap[currentChar.id]" @click="generateOne(currentChar)">AI 生成角色卡</el-button>
              </el-empty>
            </div>
            <template v-else>
              <div class="persona-sections">
                <div class="p-section">
                  <div class="p-title">身份</div>
                  <div class="p-grid">
                    <div class="p-cell"><span class="k">姓名</span><span class="v">{{ persona.identity?.name || '—' }}</span></div>
                    <div class="p-cell"><span class="k">头衔</span><span class="v">{{ persona.identity?.title || '—' }}</span></div>
                    <div class="p-cell"><span class="k">种族</span><span class="v">{{ persona.identity?.species || '—' }}</span></div>
                    <div class="p-cell"><span class="k">职业</span><span class="v">{{ persona.identity?.occupation || '—' }}</span></div>
                    <div class="p-cell"><span class="k">所属势力</span><span class="v">{{ persona.identity?.affiliation || '—' }}</span></div>
                    <div class="p-cell"><span class="k">年龄</span><span class="v">{{ persona.identity?.age ?? '—' }}</span></div>
                  </div>
                </div>
                <div class="p-section">
                  <div class="p-title">性格特质</div>
                  <div class="p-list">
                    <div v-if="list(persona.personality?.traits).length" class="p-tags">
                      <el-tag v-for="(t, i) in list(persona.personality.traits)" :key="i" size="small" effect="plain">{{ t }}</el-tag>
                    </div>
                    <div v-else class="p-none">—</div>
                    <div class="p-label">价值观</div>
                    <div class="p-tags"><el-tag v-for="(v, i) in list(persona.personality?.values)" :key="i" size="small" type="success" effect="plain">{{ v }}</el-tag></div>
                    <div class="p-label">怪癖/习惯</div>
                    <div class="p-tags"><el-tag v-for="(q, i) in list(persona.personality?.quirks)" :key="i" size="small" type="warning" effect="plain">{{ q }}</el-tag></div>
                  </div>
                </div>
                <div class="p-section">
                  <div class="p-title">背景与目标</div>
                  <div class="p-text">{{ persona.background?.history || '—' }}</div>
                  <div class="p-label">关键事件</div>
                  <div class="p-tags"><el-tag v-for="(e, i) in list(persona.background?.keyEvents)" :key="i" size="small" effect="plain">{{ e }}</el-tag></div>
                  <div class="p-label">心结/创伤</div>
                  <div class="p-tags"><el-tag v-for="(w, i) in list(persona.background?.wounds)" :key="i" size="small" type="danger" effect="plain">{{ w }}</el-tag></div>
                  <div class="p-label">目标</div>
                  <div class="p-tags"><el-tag v-for="(g, i) in list(persona.background?.goals)" :key="i" size="small" type="primary" effect="plain">{{ g }}</el-tag></div>
                </div>
                <div class="p-section">
                  <div class="p-title">说话风格</div>
                  <div class="p-text">
                    {{ persona.speechStyle?.tone || '—' }}
                    <template v-if="persona.speechStyle?.vocabulary">（{{ persona.speechStyle.vocabulary }}）</template>
                  </div>
                  <div class="p-label">口头禅</div>
                  <div class="p-tags"><el-tag v-for="(c, i) in list(persona.speechStyle?.catchphrases)" :key="i" size="small" type="warning" effect="plain">{{ c }}</el-tag></div>
                  <div class="p-label">禁忌</div>
                  <div class="p-tags"><el-tag v-for="(t, i) in list(persona.speechStyle?.taboos)" :key="i" size="small" type="danger" effect="plain">{{ t }}</el-tag></div>
                </div>
                <div class="p-section">
                  <div class="p-title">知识边界</div>
                  <div class="p-label">知晓</div>
                  <div class="p-tags"><el-tag v-for="(k, i) in list(persona.knowledge?.knows)" :key="i" size="small" type="success" effect="plain">{{ k }}</el-tag></div>
                  <div class="p-label">不知道/不假装知道</div>
                  <div class="p-tags"><el-tag v-for="(n, i) in list(persona.knowledge?.notKnows)" :key="i" size="small" type="info" effect="plain">{{ n }}</el-tag></div>
                </div>
                <div class="p-section">
                  <div class="p-title">行为模式（行动驱动）</div>
                  <div class="p-list">
                    <div v-if="list(persona.behaviorPatterns).length" class="p-text">
                      <div v-for="(b, i) in list(persona.behaviorPatterns)" :key="i" class="p-bitem">· {{ b }}</div>
                    </div>
                    <div v-else class="p-none">—</div>
                  </div>
                </div>
              </div>
              <div class="persona-ops">
                <el-button size="small" text type="primary" @click="openCardEdit">编辑角色卡（JSON）</el-button>
                <el-button size="small" text @click="openVersions">版本历史</el-button>
              </div>
            </template>
            </template>
          </el-tab-pane>

          <!-- Tab 3 · Prompt：系统提示词只读（需选中角色） -->
          <el-tab-pane label="Prompt" name="prompt">
            <div v-if="!currentChar" class="main-empty">
              <el-empty description="从左侧选择一个角色查看详情" />
            </div>
            <template v-else>
            <div v-if="!roleCard?.systemPrompt" class="main-empty">
              <el-empty description="该角色尚未生成角色卡（Prompt 由角色卡渲染生成）" />
            </div>
            <template v-else>
              <div class="prompt-toolbar">
                <span class="prompt-hint">对话系统提示词（由角色卡渲染生成，只读）</span>
                <el-button size="small" text type="primary" @click="copyPrompt"><el-icon><HIcon name="CopyDocument" /></el-icon>&nbsp;复制</el-button>
              </div>
              <pre class="prompt-pre">{{ roleCard.systemPrompt }}</pre>
            </template>
            </template>
          </el-tab-pane>

          <!-- Tab 4 · 记忆：角色长期记忆（需选中角色） -->
          <el-tab-pane label="记忆" name="memory">
            <div v-if="!currentChar" class="main-empty">
              <el-empty description="从左侧选择一个角色查看详情" />
            </div>
            <template v-else>
            <div class="memory-ops">
              <span class="memory-hint">对话后 AI 自动抽取关键事实/摘要</span>
              <el-button size="small" text type="primary" :loading="extracting" @click="doExtractMemories">手动抽取</el-button>
            </div>
            <div v-loading="memoryLoading" class="memory-list">
              <div v-if="memories.length === 0" class="side-empty">暂无记忆，对话几轮后自动生成</div>
              <div v-for="m in memories" :key="m.id" class="memory-item" :class="m.kind">
                <div class="memory-head">
                  <el-tag size="small" :type="m.kind === 'summary' ? 'primary' : 'info'" effect="plain">
                    {{ m.kind === 'summary' ? '摘要' : '事实' }}
                  </el-tag>
                  <el-tag size="small" :type="impTag(m.importance)" effect="light">重要度 {{ m.importance }}</el-tag>
                  <el-button class="memory-del" size="small" text type="danger" @click="doDeleteMemory(m)" title="删除该记忆">
                    <el-icon><HIcon name="Delete" /></el-icon>
                  </el-button>
                </div>
                <div class="memory-content">{{ m.content }}</div>
              </div>
            </div>
            </template>
          </el-tab-pane>

          <!-- Tab 5 · 关系拓扑：全角色网络图（AntV G6 v5；无需选中角色，选中角色时图内高亮其关联 + 生成该角色关系） -->
          <el-tab-pane label="关系拓扑" name="topology" lazy>
            <RelationTopology
              :project-id="projectId"
              :selected-character-id="currentChar?.id ?? null"
              :active="infoTab === 'topology'"
              :refresh-key="charRefreshKey"
              variant="tab"
              @select-character="selectCharFromTopology"
              @clear-selection="clearSelectionFromTopology"
            />
          </el-tab-pane>
        </el-tabs>
    </div>

    <!-- 角色新增/编辑对话框：只需输入 名字 + 详细信息 + 重要度 + 是否主角，保存后可直接生成角色卡 -->
    <el-dialog v-model="charDialog.visible" :title="charDialog.editId ? '编辑角色' : '新增角色'" width="640px" top="6vh">
      <el-form :model="charDialog.form" label-width="80px">
        <el-form-item label="姓名" required>
          <el-input v-model="charDialog.form.name" maxlength="50" placeholder="角色名字（必填）" />
        </el-form-item>
        <el-form-item label="详细信息" required>
          <el-input v-model="charDialog.form.detail" type="textarea" :rows="12" maxlength="20000" show-word-limit
            placeholder="填写角色的完整详细信息：背景经历、性格特质、能力体系、社会关系、语言风格、语录、目标、心结等。AI 将【严格遵循、只可多不可少】地据此生成结构化角色卡与角色 Prompt（杜绝 OOC）。" />
        </el-form-item>
        <el-form-item label="重要度">
          <el-rate v-model="charDialog.form.importance" :max="5" />
        </el-form-item>
        <el-form-item label="主角">
          <el-switch v-model="charDialog.form.isProtagonist" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-collapse class="char-adv-collapse">
          <el-collapse-item title="高级选项（头衔 / 类型，可留空）" name="adv">
            <el-form-item label="头衔"><el-input v-model="charDialog.form.title" maxlength="50" placeholder="如：帝国首席法师" /></el-form-item>
            <el-form-item label="类型">
              <el-radio-group v-model="charDialog.form.type">
                <el-radio value="special">特殊型</el-radio>
                <el-radio value="common">普通型</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-collapse-item>
        </el-collapse>
        <el-form-item label="生成角色卡">
          <el-switch v-model="charDialog.autoGen" />
          <span class="char-auto-hint">保存后立即按世界观 + 详细信息生成结构化角色卡与 Prompt</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="charDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="savingChar" @click="saveChar">
          保存{{ charDialog.autoGen ? '并生成角色卡' : '' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 角色卡编辑对话框（JSON 手动编辑） -->
    <el-dialog v-model="cardDialog.visible" :title="`编辑角色卡 · ${currentChar?.name}`" width="760px" top="6vh">
      <div class="card-edit-tabs">
        <el-tabs v-model="cardDialog.tab">
          <el-tab-pane label="结构化角色卡 (JSON)" name="persona">
            <el-input v-model="cardDialog.persona" type="textarea" :rows="18" class="code-input" spellcheck="false" />
          </el-tab-pane>
          <el-tab-pane label="系统 Prompt" name="prompt">
            <el-input v-model="cardDialog.systemPrompt" type="textarea" :rows="18" class="code-input" spellcheck="false" />
          </el-tab-pane>
        </el-tabs>
      </div>
      <template #footer>
        <el-button @click="cardDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="cardDialog.saving" @click="saveCard">保存为新版本</el-button>
      </template>
    </el-dialog>

    <!-- 角色卡版本历史对话框 -->
    <el-dialog v-model="versionDialog" :title="`角色卡版本历史 · ${currentChar?.name}`" width="720px">
      <div v-loading="versionLoading" class="version-list">
        <div v-if="versions.length === 0" class="side-empty">暂无版本记录</div>
        <div v-for="v in [...versions].reverse()" :key="v.version" class="version-item" :class="{ current: v.version === roleCard?.version }">
          <div class="version-head">
            <el-tag size="small" :type="v.version === roleCard?.version ? 'success' : 'info'" effect="plain">
              v{{ v.version }} {{ v.version === roleCard?.version ? '· 当前' : '' }}
            </el-tag>
            <span class="version-source">{{ sourceLabel(v.source) }}</span>
            <span class="version-time">{{ (v.updatedAt || '').slice(0, 16).replace('T', ' ') }}</span>
            <el-button size="small" text type="primary" @click="viewVersion(v)">查看</el-button>
          </div>
          <div v-if="versionDetail?.version === v.version" class="version-detail">
            <div class="vd-label">角色卡 JSON</div>
            <pre class="vd-pre">{{ prettyJson(versionDetail.personaJson) }}</pre>
            <div class="vd-label">系统 Prompt</div>
            <pre class="vd-pre">{{ versionDetail.systemPrompt }}</pre>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="versionDialog = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * NPC 角色页（前端布局重构 V1.0，设计文档 §3.5.1）。
 * <p>职责：特殊型角色（NPC）的管理 + 生成 + 展示全能力——
 * 左栏角色列表（头像/姓名/头衔/重要度/类型/主角标记，类型筛选，新增/编辑/删除）；
 * 右栏角色详情分 Tab：概览（基础档案只读）/ 结构化角色卡（JSON 前端解析渲染为卡片分节，
 * 非原始文本，支持重新生成/编辑/版本历史）/ Prompt（系统提示词只读+复制）/ 记忆（长期记忆列表+手动抽取）。</p>
 * <p>数据来源：/api/projects/{id}/characters、/api/characters/{id}/card(versions)、memories 系列接口。</p>
 */
import { ref, reactive, computed, onMounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchCharacters, createCharacter, updateCharacter, deleteCharacter,
  generateCharacterCard, fetchCharacterCard, fetchCharacterCardVersions, editCharacterCard,
  fetchMemories, deleteMemory, extractCharacterMemories
} from '@/shared/api'
import RelationTopology from '../components/RelationTopology.vue'

const route = useRoute()
const router = useRouter()
const projectId = Number(route.params.id)

// 全局拓扑页「补充」跳转参数：?from=topology&name=角色名 → 自动打开新增弹窗并预填角色名，保存后回跳拓扑页
const fromTopology = route.query.from === 'topology'
const supplementName = route.query.name ? String(route.query.name) : ''

// ===== 左栏：角色列表 =====
const characters = ref([])
const currentChar = ref(null)
const typeFilter = ref(null)
const generatingMap = reactive({})
const generatingAll = ref(false)

// 类型筛选后的角色列表
const filteredChars = computed(() => {
  if (!typeFilter.value) return characters.value
  return characters.value.filter(c => c.type === typeFilter.value)
})

// 「关系拓扑」Tab 刷新键：角色集合变化（新增/删除）时改变该值，触发拓扑图重新拉取
const charRefreshKey = computed(() => characters.value.map(c => c.id).join(','))

// ===== 右栏：详情 Tab =====
const infoTab = ref('overview')
const roleCard = ref(null)
const memories = ref([])
const memoryLoading = ref(false)
const extracting = ref(false)

// 解析后的 persona 对象（供结构化渲染）
// 防御：后端旧 jar/旧数据可能返回「双重编码」JSON（H2 JSON 列读回多包一层字符串字面量），
// JSON.parse 会得到字符串而非对象；若首层解析结果是字符串则再解析一次，保证得到对象。
const persona = computed(() => {
  try {
    let p = roleCard.value?.personaJson ? JSON.parse(roleCard.value.personaJson) : {}
    if (typeof p === 'string') {
      try { p = JSON.parse(p) } catch (_) { p = {} }
    }
    return p && typeof p === 'object' ? p : {}
  } catch (_) { return {} }
})

// ===== 对话框 =====
const charDialog = reactive({ visible: false, editId: null, saving: false, autoGen: true, form: { name: '', title: '', detail: '', type: 'special', importance: 3, isProtagonist: 0 } })
const cardDialog = reactive({ visible: false, persona: '', systemPrompt: '', tab: 'persona', saving: false })
const versionDialog = ref(false)
const versionLoading = ref(false)
const versions = ref([])
const versionDetail = ref(null)

/** 加载角色列表 */
async function loadChars() {
  try {
    characters.value = await fetchCharacters(projectId)
    if (currentChar.value) {
      currentChar.value = characters.value.find(c => c.id === currentChar.value.id) || null
      if (currentChar.value) await loadRoleCard()
    } else if (characters.value.length > 0) {
      await selectChar(characters.value[0])
    }
  } catch (e) { ElMessage.error(e.message || '加载角色失败') }
}

/** 选中角色：加载角色卡 + 记忆（keepTab=true 时保持当前 Tab，用于关系拓扑图内点选角色） */
async function selectChar(ch, keepTab = false) {
  currentChar.value = ch
  if (!keepTab) infoTab.value = 'overview'
  await loadRoleCard()
  await loadMemories()
}

/** 关系拓扑图内点击节点 → 在左侧选中该角色（保持当前「关系拓扑」Tab，图内立即高亮其关联） */
async function selectCharFromTopology(id) {
  const ch = characters.value.find(c => c.id === id)
  if (!ch) return
  // 图内点选也同步左侧列表选中态；keepTab 保持停留在「关系拓扑」Tab
  await selectChar(ch, true)
}

/** 关系拓扑图内点击空白画布 → 取消左侧选中角色（selectedCharacterId→null，拓扑图全部节点恢复清晰显示；保持停留在「关系拓扑」Tab） */
function clearSelectionFromTopology() {
  currentChar.value = null
  roleCard.value = null
  memories.value = []
}

/** 加载当前角色最新角色卡 */
async function loadRoleCard() {
  roleCard.value = null
  if (!currentChar.value) return
  try { roleCard.value = await fetchCharacterCard(currentChar.value.id) } catch (_) { roleCard.value = null }
}

/** 加载当前角色记忆列表 */
async function loadMemories() {
  if (!currentChar.value) { memories.value = []; return }
  memoryLoading.value = true
  try {
    const page = await fetchMemories(projectId, { characterId: currentChar.value.id, page: 1, size: 50 })
    memories.value = page.list || []
  } catch (_) { memories.value = [] }
  finally { memoryLoading.value = false }
}

/**
 * 右栏 Tab 切换回调：切到「记忆」Tab 时重新拉取记忆列表。
 * <p>背景：记忆为「对话回复完成后异步抽取」（后端约数秒落库），而记忆列表仅在
 * 选中角色时加载一次——停留页面期间对话产生的记忆不会自动出现。切 Tab 时重新拉取保证所见即最新。</p>
 *
 * @param name 切换到的 Tab 名称（overview/prompt/memory）
 */
function onInfoTabChange(name) {
  if (name === 'memory') loadMemories()
}

/** 删除单条记忆（软删） */
async function doDeleteMemory(m) {
  try {
    await ElMessageBox.confirm(`确认删除该条${m.kind === 'summary' ? '摘要' : '记忆'}？删除后不再注入后续对话。`, '删除确认', { type: 'warning' })
    await deleteMemory(m.id)
    ElMessage.success('已删除')
    await loadMemories()
  } catch (_) { /* 用户取消 */ }
}

/** 手动触发当前角色记忆抽取 */
async function doExtractMemories() {
  if (!currentChar.value) return
  extracting.value = true
  try {
    const res = await extractCharacterMemories(currentChar.value.id)
    ElMessage.success(`抽取完成，新增 ${res.added || 0} 条事实`)
    await loadMemories()
  } catch (e) { ElMessage.error(e.message || '抽取失败') }
  finally { extracting.value = false }
}

/** 打开角色新增/编辑对话框（新增默认开启「保存后自动生成角色卡」） */
function openCharDialog(row) {
  if (row) {
    charDialog.editId = row.id
    Object.assign(charDialog.form, { name: row.name, title: row.title, detail: row.detail, type: row.type, importance: row.importance, isProtagonist: row.isProtagonist })
    charDialog.autoGen = true
  } else {
    charDialog.editId = null
    Object.assign(charDialog.form, { name: '', title: '', detail: '', type: 'special', importance: 3, isProtagonist: 0 })
    charDialog.autoGen = true
  }
  charDialog.visible = true
}

/** 全局拓扑页「补充」入口：自动打开新增弹窗并预填角色名（保存后回跳拓扑页） */
function openSupplement(name) {
  charDialog.editId = null
  Object.assign(charDialog.form, { name: name || '', title: '', detail: '', type: 'special', importance: 3, isProtagonist: 0 })
  charDialog.autoGen = true
  charDialog.visible = true
}

/**
 * 保存角色（重构后的新增逻辑）：只需输入 姓名 + 详细信息 + 重要度 + 是否主角。
 * 保存后若勾选「自动生成角色卡」，立即严格按 世界观 + 详细信息 生成结构化角色卡与 Prompt，
 * 并自动选中该角色展示到「结构化角色卡」Tab。
 */
async function saveChar() {
  const f = charDialog.form
  if (!f.name.trim()) return ElMessage.warning('请填写角色姓名')
  if (!f.detail.trim()) return ElMessage.warning('请填写角色详细信息（AI 将严格依据它生成角色卡，只可多不可少）')
  charDialog.saving = true
  try {
    const payload = { name: f.name.trim(), title: f.title || null, detail: f.detail || null, type: f.type, importance: f.importance, isProtagonist: f.isProtagonist }
    let id = charDialog.editId
    if (id) {
      await updateCharacter(id, payload)
    } else {
      const created = await createCharacter(projectId, payload)
      id = created.id
    }
    charDialog.visible = false
    await loadChars()
    // 自动生成角色卡：严格按世界观 + 详细信息生成结构化卡 + Prompt（只可多不可少）
    if (charDialog.autoGen && f.detail.trim()) {
      await generateOneById(id, `「${f.name.trim()}」`)
      if (currentChar.value?.id === id) {
        await loadRoleCard()
        infoTab.value = 'card'
      }
    }
    ElMessage.success('角色已保存')
    // 全局拓扑页「补充」流程：保存成功后回到拓扑页（自动按名称全表扫描关联由后端完成）
    if (fromTopology) {
      router.replace(`/project/${projectId}/topology`)
      return
    }
  } catch (e) { ElMessage.error(e.message || '保存失败') } finally { charDialog.saving = false }
}

/** 删除角色 */
async function doRemoveChar(row) {
  try {
    await ElMessageBox.confirm(`确认删除角色「${row.name}」？`, '删除确认', { type: 'warning' })
    await deleteCharacter(row.id)
    ElMessage.success('角色已删除')
    if (currentChar.value?.id === row.id) { currentChar.value = null; roleCard.value = null }
    await loadChars()
  } catch (_) { /* 取消或失败 */ }
}

/** 按角色 ID 生成角色卡（生成后刷新列表与当前选中角色卡） */
async function generateOneById(id, label) {
  generatingMap[id] = true
  try {
    await generateCharacterCard(id)
    if (label) ElMessage.success(`${label}角色卡已生成`)
    await loadChars()
    if (currentChar.value?.id === id) await loadRoleCard()
  } catch (e) { ElMessage.error(e.message || '生成失败') } finally { generatingMap[id] = false }
}

/** 单角色生成角色卡 */
async function generateOne(row) {
  await generateOneById(row.id, `「${row.name}」`)
}

/**
 * 一键生成全部角色卡（串行）。
 * <p>行为：只生成「尚未生成角色卡」的 NPC 角色（hasCard=false），
 * 已生成角色卡的角色自动跳过（不重复调用 AI，避免浪费 token）；
 * 若所有角色均已生成角色卡，则给出友善提醒并直接返回。</p>
 */
async function generateAll() {
  // 只挑选尚未生成角色卡的角色；hasCard 由后端 list 接口批量返回
  const pending = characters.value.filter(c => !c.hasCard)
  const skipped = characters.value.length - pending.length
  // 全部都已生成角色卡：友善提醒，不做任何 AI 调用
  if (pending.length === 0) {
    ElMessage.info('所有角色均已生成角色卡，无需重复生成 🎉')
    return
  }
  generatingAll.value = true
  let ok = 0
  for (const c of pending) {
    generatingMap[c.id] = true
    try { await generateCharacterCard(c.id); ok++ } catch (e) { ElMessage.warning(`「${c.name}」生成失败：${e.message}`) }
    finally { generatingMap[c.id] = false }
  }
  generatingAll.value = false
  // 汇总：成功数 / 本次实际生成数；有跳过时额外说明已生成数量
  ElMessage.success(
    skipped > 0
      ? `一键生成完成：成功 ${ok}/${pending.length}，已跳过 ${skipped} 个已生成角色卡的角色`
      : `一键生成完成：成功 ${ok}/${pending.length}`
  )
  await loadChars()
}

/** 打开角色卡 JSON 编辑对话框（预填当前内容） */
function openCardEdit() {
  if (!roleCard.value) return
  cardDialog.persona = prettyJson(roleCard.value.personaJson)
  cardDialog.systemPrompt = roleCard.value.systemPrompt || ''
  cardDialog.tab = 'persona'
  cardDialog.visible = true
}

/** 保存角色卡（新版本，source=edited） */
async function saveCard() {
  cardDialog.saving = true
  try {
    const card = await editCharacterCard(currentChar.value.id, {
      personaJson: cardDialog.persona,
      systemPrompt: cardDialog.systemPrompt
    })
    roleCard.value = card
    cardDialog.visible = false
    ElMessage.success(`角色卡已保存为新版本 v${card.version}`)
    await loadChars()
  } catch (e) { ElMessage.error(e.message || '保存失败') } finally { cardDialog.saving = false }
}

/** 打开版本历史对话框 */
async function openVersions() {
  versionDialog.value = true
  versionLoading.value = true
  versionDetail.value = null
  try {
    versions.value = await fetchCharacterCardVersions(currentChar.value.id)
  } catch (e) { ElMessage.error(e.message || '加载版本失败'); versions.value = [] }
  finally { versionLoading.value = false }
}

/** 查看某个历史版本详情 */
function viewVersion(v) {
  versionDetail.value = versionDetail.value?.version === v.version ? null : v
}

/** 版本来源文案 */
function sourceLabel(s) {
  return ({ ai: 'AI 生成', edited: '手动编辑', imported: '导入' })[s] || s || '—'
}

/** 复制系统 Prompt */
function copyPrompt() {
  try {
    navigator.clipboard.writeText(roleCard.value?.systemPrompt || '')
    ElMessage.success('已复制系统提示词')
  } catch (_) { ElMessage.info('复制失败，请手动选择复制') }
}

/** 重要度颜色映射 */
function impTag(i) { return i >= 4 ? 'danger' : (i >= 3 ? 'warning' : 'info') }

/** 数组安全取值（对象字段可能为字符串/缺失） */
function list(v) {
  if (Array.isArray(v)) return v
  if (typeof v === 'string' && v.trim()) return v.split(/[、,，;；]/).filter(Boolean)
  return []
}

/** 格式化 JSON 文本 */
function prettyJson(text) {
  if (!text) return ''
  try { return JSON.stringify(JSON.parse(text), null, 2) } catch (_) { return text || '' }
}

onMounted(async () => {
  await loadChars()
  // 全局拓扑页「补充」跳转（?from=topology&name=角色名）：加载完列表后自动打开新增弹窗并预填角色名
  if (fromTopology && supplementName) {
    await nextTick()
    openSupplement(supplementName)
  }
})
</script>

<style scoped>
.character-view { display: flex; gap: 16px; height: calc(100vh - 140px); }

/* 左栏 */
.char-side { width: 300px; background: var(--bg-layer-1); border-radius: var(--radius-lg); border: 1px solid var(--border-light); padding: 12px; display: flex; flex-direction: column; overflow-y: auto; flex-shrink: 0; }
.side-header { display: flex; align-items: center; justify-content: space-between; font-weight: 600; color: var(--text-primary); padding: 4px 2px 8px; }
.side-filter { margin-bottom: 8px; }
.side-empty { color: var(--text-secondary); font-size: 0.82rem; padding: 12px 4px; }
.char-list { flex: 1; display: flex; flex-direction: column; gap: 4px; overflow-y: auto; }
.char-item { padding: 8px 10px; border-radius: var(--radius-sm); cursor: pointer; display: flex; align-items: center; gap: 10px; transition: background 0.15s; }
.char-item:hover { background: var(--bg-hover, var(--bg-layer-2)); }
.char-item.active { background: var(--primary-bg, #ecf5ff); }
.char-avatar { flex-shrink: 0; background: var(--brand-gradient); color: #fff; }
.char-meta { flex: 1; min-width: 0; }
.char-name { font-size: 0.88rem; font-weight: 600; display: flex; align-items: center; gap: 4px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.char-title { font-size: 0.75rem; color: var(--text-secondary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.char-ops { display: flex; gap: 2px; opacity: 0; transition: opacity .15s; flex-shrink: 0; }
.char-item:hover .char-ops { opacity: 1; }
.side-foot { border-top: 1px solid var(--border-light); padding-top: 10px; }

/* 右栏 */
.char-main { flex: 1; background: var(--bg-layer-1); border-radius: var(--radius-lg); border: 1px solid var(--border-light); padding: 14px 18px; display: flex; flex-direction: column; overflow: hidden; }
.main-empty { border: none; box-shadow: none; display: flex; align-items: center; justify-content: center; height: 100%; }
.detail-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.detail-name { font-size: 1.1rem; font-weight: 700; color: var(--text-primary); display: flex; align-items: center; gap: 8px; }
.detail-ops { display: flex; align-items: center; gap: 8px; }
.card-version { margin-right: 4px; }
.info-tabs { flex: 1; min-height: 0; overflow-y: auto; }

/* 概览 Tab */
.ov-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 10px 20px; margin-bottom: 16px; }
.ov-item { display: flex; gap: 8px; align-items: center; }
.ov-item .k { color: var(--text-secondary); font-size: 0.8rem; width: 64px; flex-shrink: 0; }
.ov-item .v { color: var(--text-primary); font-size: 0.85rem; }
.ov-detail { margin-bottom: 8px; }
.ov-detail-label { font-size: 0.8rem; color: var(--text-secondary); margin-bottom: 6px; }
.ov-detail-text { font-size: 0.88rem; color: var(--text-regular); line-height: 1.8; white-space: pre-wrap; word-break: break-word; background: var(--bg-layer-2); border-radius: 8px; padding: 12px; }

/* 结构化角色卡 */
.persona-sections { display: flex; flex-direction: column; gap: 18px; }
.p-section { }
.p-title { font-weight: 700; color: var(--text-primary); font-size: 0.95rem; margin-bottom: 8px; padding-bottom: 6px; border-bottom: 1px solid var(--border-light); }
.p-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 8px 16px; }
.p-cell { display: flex; gap: 8px; }
.p-cell .k { color: var(--text-secondary); font-size: 0.78rem; flex-shrink: 0; }
.p-cell .v { color: var(--text-primary); font-size: 0.85rem; }
.p-list { }
.p-label { font-size: 0.75rem; color: var(--text-secondary); margin: 8px 0 4px; }
.p-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.p-text { font-size: 0.88rem; color: var(--text-regular); line-height: 1.7; white-space: pre-wrap; word-break: break-word; }
.p-none { color: var(--text-placeholder); font-size: 0.82rem; }
.p-bitem { margin-bottom: 2px; }
.persona-ops { margin-top: 16px; display: flex; gap: 8px; }

/* Prompt Tab */
.prompt-toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.prompt-hint { font-size: 0.78rem; color: var(--text-secondary); }
.prompt-pre { margin: 0; padding: 14px; background: var(--bg-layer-2); border: 1px solid var(--border-light); border-radius: 8px; font-family: var(--font-mono, monospace); font-size: 0.78rem; line-height: 1.7; white-space: pre-wrap; word-break: break-word; max-height: calc(100vh - 320px); overflow-y: auto; }

/* 记忆 Tab */
.memory-ops { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.memory-hint { font-size: 0.72rem; color: var(--text-secondary); }
.memory-list { display: flex; flex-direction: column; gap: 8px; max-height: calc(100vh - 340px); overflow-y: auto; }
.memory-item { border: 1px solid var(--border-light); border-radius: 8px; padding: 8px 10px; }
.memory-item.summary { background: var(--state-success-bg); }
.memory-item.fact { background: var(--bg-layer-2); }
.memory-head { display: flex; align-items: center; gap: 6px; }
.memory-del { margin-left: auto; opacity: 0; transition: opacity .15s; }
.memory-item:hover .memory-del { opacity: 1; }
.memory-content { font-size: 0.8rem; color: var(--text-primary); margin-top: 6px; line-height: 1.5; white-space: pre-wrap; word-break: break-word; }

/* 版本历史 */
.version-list { max-height: 55vh; overflow-y: auto; display: flex; flex-direction: column; gap: 8px; }
.version-item { border: 1px solid var(--border-light); border-radius: 8px; padding: 10px 12px; }
.version-item.current { border-color: #67c23a; }
.version-head { display: flex; align-items: center; gap: 10px; }
.version-source { font-size: 0.78rem; color: var(--text-secondary); }
.version-time { font-size: 0.75rem; color: var(--text-placeholder); margin-left: auto; }
.version-detail { margin-top: 10px; }
.vd-label { font-size: 0.75rem; color: var(--text-secondary); margin-bottom: 4px; }
.vd-pre { margin: 0 0 10px; padding: 10px; background: var(--bg-layer-2); border-radius: 6px; font-size: 0.75rem; white-space: pre-wrap; word-break: break-word; max-height: 200px; overflow-y: auto; }

.code-input { font-family: var(--font-mono, monospace); font-size: 0.82rem; }

/* 角色新增/编辑对话框 */
.char-adv-collapse { margin-bottom: 8px; border: none; }
.char-auto-hint { font-size: 0.78rem; color: var(--text-secondary); margin-left: 8px; }
</style>
