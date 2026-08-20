<template>
  <div class="crowd-view">
    <!-- 页头 -->
    <div class="page-header">
      <div>
        <div class="page-title">普通型人群</div>
        <div class="page-desc">AI 依据世界观生成普通型 NPC（每人独立档案）：标准字段数据（字段字典）→ 居民生成 → 关系生成 → 两级 AI 调度。</div>
      </div>
      <div class="header-ops">
        <el-button :loading="relationAllLoading" @click="openAllRelations">
          <el-icon><HIcon name="Connection" /></el-icon>&nbsp;为全部普通 NPC 生成关系
        </el-button>
        <el-button @click="openFieldDict">
          <el-icon><HIcon name="FolderOpened" /></el-icon>&nbsp;字段字典
        </el-button>
        <el-button type="primary" @click="openGen">
          <el-icon><HIcon name="Highlight" /></el-icon>&nbsp;AI 生成居民
        </el-button>
      </div>
    </div>

    <!-- 环境摘要 -->
    <el-alert v-if="envSummary" class="env-alert" type="success" :closable="false" show-icon>
      <template #title>
        <div class="env-title">🌆 当前居民环境</div>
        <div class="env-body">{{ envSummary }}</div>
      </template>
    </el-alert>

    <!-- 统计条（按主/次分类字段分布 + 归属分布） -->
    <div class="stats-row">
      <el-statistic title="居民总数" :value="stats.total || 0" />
      <div class="stats-block">
        <span class="muted">{{ primaryFieldLabel }}分布：</span>
        <el-tag v-for="(cnt, v) in stats.byPrimary || {}" :key="'p-' + v" size="small" effect="plain" type="primary" class="aff-tag">
          {{ v }} {{ cnt }}
        </el-tag>
        <span v-if="!Object.keys(stats.byPrimary || {}).length" class="muted">（未配置主分类字段）</span>
      </div>
      <div class="stats-block">
        <span class="muted">{{ secondaryFieldLabel }}分布：</span>
        <el-tag v-for="(cnt, v) in stats.bySecondary || {}" :key="'s-' + v" size="small" effect="plain" class="aff-tag">
          {{ v }} {{ cnt }}
        </el-tag>
        <span v-if="!Object.keys(stats.bySecondary || {}).length" class="muted">（未配置次分类字段）</span>
      </div>
      <div class="stats-block">
        <span class="muted">归属分布：</span>
        <el-tag v-for="(cnt, v) in topAffiliations" :key="'a-' + v[0]" size="small" effect="plain" class="aff-tag">
          {{ v[0] }} {{ v[1] }}
        </el-tag>
        <span v-if="!topAffiliations.length" class="muted">（暂无）</span>
      </div>
    </div>

    <!-- 调度操作条 -->
    <div class="schedule-bar">
      <el-switch v-model="scheduleEnabled" :loading="scheduleSaving" @change="toggleSchedule" />
      <span class="muted">定时调度（每 5 分钟程序化推进全部居民状态）</span>
      <el-divider direction="vertical" />
      <el-button size="small" :loading="scheduling" @click="doSchedule(false)">
        <el-icon><HIcon name="VideoPlay" /></el-icon>&nbsp;程序化调度
      </el-button>
      <el-button size="small" type="warning" :loading="schedulingAi" @click="doSchedule(true)">
        <el-icon><HIcon name="Highlight" /></el-icon>&nbsp;AI 两级调度
      </el-button>
      <span v-if="runtime.lastScheduleAt" class="muted">最近调度：{{ formatTime(runtime.lastScheduleAt) }}</span>
    </div>

    <!-- 筛选条（全部字段可筛：性别/种族/次级种族/归属/职业/所在地/年龄区间/关键词） -->
    <div class="filter-bar">
      <el-select v-model="filters.gender" placeholder="性别" clearable filterable allow-create default-first-option style="width: 110px" @change="search">
        <el-option v-for="g in genderOptions" :key="g" :label="g" :value="g" />
      </el-select>
      <el-select v-model="filters.race" placeholder="种族" clearable filterable allow-create default-first-option style="width: 120px" @change="onRaceFilterChange">
        <el-option v-for="r in raceOptions" :key="r" :label="r" :value="r" />
      </el-select>
      <el-select v-model="filters.subRace" placeholder="次级种族" clearable filterable allow-create default-first-option style="width: 130px" :disabled="!filters.race" @change="search">
        <el-option v-for="s in subRaceOptions" :key="s" :label="s" :value="s" />
      </el-select>
      <el-select v-model="filters.affiliation" placeholder="归属" clearable filterable allow-create default-first-option style="width: 150px" @change="search">
        <el-option v-for="a in affiliationOptions" :key="a" :label="a" :value="a" />
      </el-select>
      <el-select v-model="filters.occupation" placeholder="职业" clearable filterable allow-create default-first-option style="width: 130px" @change="search">
        <el-option v-for="o in occupationOptions" :key="o" :label="o" :value="o" />
      </el-select>
      <el-select v-model="filters.location" placeholder="所在地" clearable filterable allow-create default-first-option style="width: 130px" @change="search">
        <el-option v-for="l in locationOptions" :key="l" :label="l" :value="l" />
      </el-select>
      <el-input-number v-model="filters.ageMin" :min="0" :max="10000" controls-position="right" placeholder="年龄≥" style="width: 110px" />
      <span class="muted">~</span>
      <el-input-number v-model="filters.ageMax" :min="0" :max="10000" controls-position="right" placeholder="年龄≤" style="width: 110px" />
      <el-input v-model="filters.keyword" placeholder="搜索名称/详情/…" clearable style="width: 180px" @keyup.enter="search" @clear="search">
        <template #prefix><el-icon><HIcon name="Search" /></el-icon></template>
      </el-input>
      <el-button type="primary" @click="search">
        <el-icon><HIcon name="Search" /></el-icon>&nbsp;查询
      </el-button>
      <el-button @click="resetFilter">
        <el-icon><HIcon name="Refresh" /></el-icon>&nbsp;重置
      </el-button>
      <el-button type="danger" plain :disabled="!selection.length" @click="doBatchDelete">
        <el-icon><HIcon name="Delete" /></el-icon>&nbsp;批量删除（{{ selection.length }}）
      </el-button>
    </div>

    <!-- 居民表格（新字段列，全部字段可排序：后端排序白名单） -->
    <el-table v-loading="loading" :data="list" size="default" border stripe
      @selection-change="sel => (selection = sel)" @sort-change="onSortChange"
      empty-text="暂无普通型 NPC，点击右上角「AI 生成居民」开始">
      <el-table-column type="selection" width="46" />
      <el-table-column prop="name" label="名称" width="120" sortable="custom" show-overflow-tooltip />
      <el-table-column prop="gender" label="性别" width="70" sortable="custom" />
      <el-table-column prop="race" label="种族" width="90" sortable="custom" show-overflow-tooltip />
      <el-table-column prop="subRace" label="次级种族" width="100" sortable="custom" show-overflow-tooltip />
      <el-table-column prop="age" label="年龄" width="70" sortable="custom" />
      <el-table-column prop="affiliation" label="归属" width="130" sortable="custom" show-overflow-tooltip />
      <el-table-column prop="occupation" label="职业" width="110" sortable="custom" show-overflow-tooltip />
      <el-table-column prop="location" label="所在地" width="130" sortable="custom" show-overflow-tooltip />
      <el-table-column prop="detail" label="角色详情" min-width="200" show-overflow-tooltip />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="stateType(row.state)" effect="plain">{{ stateText(row.state) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="关系" width="150" fixed="right">
        <template #default="{ row }">
          <el-button size="small" text type="primary" @click="openRelation(row, false)">
            <el-icon><HIcon name="Document" /></el-icon>&nbsp;查看
          </el-button>
          <el-button size="small" text type="warning" :loading="row._relLoading" @click="openRelation(row, true)">
            <el-icon><HIcon name="Connection" /></el-icon>&nbsp;生成
          </el-button>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button size="small" text type="primary" @click="openEdit(row)">
            <el-icon><HIcon name="Edit" /></el-icon>
          </el-button>
          <el-button size="small" text type="danger" @click="doDelete(row)">
            <el-icon><HIcon name="Delete" /></el-icon>
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <div class="pager">
      <el-pagination layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :current-page="page"
        :page-sizes="[10, 20, 50, 100]" background @size-change="onSizeChange" @current-change="onPageChange" />
    </div>

    <!-- 新增/编辑居民对话框（含次级种族，字段取自字段字典可手动补充） -->
    <el-dialog v-model="editDialog.visible" :title="editDialog.mode === 'create' ? '新增居民' : `编辑居民 · ${editDialog.form.name}`"
      width="680px" :close-on-click-modal="false">
      <el-form ref="editFormRef" :model="editDialog.form" :rules="editRules" label-width="92px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="名称" prop="name">
              <el-input v-model="editDialog.form.name" maxlength="50" placeholder="严格符合世界观命名习惯" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="性别">
              <el-select v-model="editDialog.form.gender" clearable filterable allow-create default-first-option style="width: 100%">
                <el-option v-for="g in genderOptions" :key="g" :label="g" :value="g" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="年龄">
              <el-input-number v-model="editDialog.form.age" :min="0" :max="10000" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="种族">
              <el-select v-model="editDialog.form.race" clearable filterable allow-create default-first-option style="width: 100%"
                @change="onFormRaceChange">
                <el-option v-for="r in raceOptions" :key="r" :label="r" :value="r" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="次级种族">
              <el-select v-model="editDialog.form.subRace" clearable filterable allow-create default-first-option style="width: 100%"
                :disabled="!editDialog.form.race">
                <el-option v-for="s in formSubRaceOptions" :key="s" :label="s" :value="s" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="归属">
              <el-select v-model="editDialog.form.affiliation" clearable filterable allow-create default-first-option style="width: 100%">
                <el-option v-for="a in affiliationOptions" :key="a" :label="a" :value="a" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="职业">
              <el-select v-model="editDialog.form.occupation" clearable filterable allow-create default-first-option style="width: 100%">
                <el-option v-for="o in occupationOptions" :key="o" :label="o" :value="o" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="当前所在地">
          <el-select v-model="editDialog.form.location" clearable filterable allow-create default-first-option style="width: 100%">
            <el-option v-for="l in locationOptions" :key="l" :label="l" :value="l" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色详情">
          <el-input v-model="editDialog.form.detail" type="textarea" :rows="4" maxlength="4000" show-word-limit
            placeholder="背景/性格/谋生方式/家庭/与世界观的联系" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 字段字典管理对话框（AI 一次性拟定 → 预览确认 → 整体保存；手动增删） -->
    <el-dialog v-model="fieldDictDialog.visible" :title="fieldDictDialog.mode === 'preview' ? 'AI 拟定字段字典 · 预览确认' : '标准字段数据（字段字典）管理'"
      width="820px" :close-on-click-modal="false">
      <el-alert type="info" :closable="false" show-icon class="cat-tip">
        标准字段数据由 AI 依据世界观一次性拟定（种族含次级种族 / 归属 / 职业，每条含出处、禁止编造），
        供居民生成时选取；同时选出主/次分类字段（一主一次，供人群分组与调度）。
      </el-alert>
      <el-alert v-if="fieldDictDialog.mode === 'preview'" type="warning" :closable="false" show-icon class="cat-tip">
        以下为 AI 拟定预览，请核对出处与主/次分类字段；「确认并保存」将<strong>整体替换</strong>当前字段字典。
      </el-alert>
      <div class="cat-ops">
        <el-button type="primary" :loading="fieldDictDialog.generating" @click="generateFieldDictAI">
          <el-icon><HIcon name="Highlight" /></el-icon>&nbsp;{{ fieldDictDialog.mode === 'preview' ? '重新生成' : 'AI 拟定 / 重新生成' }}
        </el-button>
        <el-button :loading="fieldDictDialog.saving" @click="saveFieldDictAll">
          <el-icon><HIcon name="FolderOpened" /></el-icon>&nbsp;{{ fieldDictDialog.mode === 'preview' ? '确认并保存' : '保存当前' }}
        </el-button>
        <span class="muted">当前 {{ fieldDictTotal }} 条</span>
      </div>
      <div class="classify-row">
        <span class="classify-label">主分类字段</span>
        <el-select v-model="fieldDictDialog.primaryField" style="width: 150px">
          <el-option v-for="o in classifyFieldOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <span class="classify-label">次分类字段</span>
        <el-select v-model="fieldDictDialog.secondaryField" style="width: 150px">
          <el-option v-for="o in classifyFieldOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </div>
      <!-- 字段树：种族（含次级种族）/ 归属 / 职业 -->
      <div class="dict-group" v-for="grp in dictGroups" :key="grp.field">
        <div class="dict-group-title">
          {{ grp.title }}
          <span class="muted dict-count">{{ fieldDictDialog.rows[grp.field].length }} 条</span>
          <el-button size="small" text type="primary" @click="addDictRow(grp.field)">
            <el-icon><HIcon name="Plus" /></el-icon>&nbsp;新增
          </el-button>
        </div>
        <div class="dict-table">
          <div v-for="(row, i) in fieldDictDialog.rows[grp.field]" :key="row._key" class="dict-row">
            <el-input v-model="row.level1" size="small" maxlength="50" :placeholder="grp.field === 'race' ? '种族大类（如 人族）' : (grp.field === 'affiliation' ? '归属名（如 会馆）' : '职业名（如 渔夫）')" style="width: 180px" />
            <el-input v-if="grp.field === 'race'" v-model="row.level2" size="small" maxlength="50" placeholder="次级种族（如 汉族/猫妖）" style="width: 170px" />
            <el-input v-model="row.source" size="small" maxlength="1000" placeholder="出处（引用世界观具体字段/段落）" style="flex: 1" />
            <el-button size="small" text type="danger" @click="removeDictRow(grp.field, row)">
              <el-icon><HIcon name="Delete" /></el-icon>
            </el-button>
          </div>
          <el-empty v-if="!fieldDictDialog.rows[grp.field].length" description="暂无，可「新增」或「AI 拟定」" :image-size="40" />
        </div>
      </div>
      <template #footer>
        <el-button @click="fieldDictDialog.visible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- AI 生成居民对话框（SSE 进度） -->
    <el-dialog v-model="genDialog.visible" title="AI 生成居民" width="560px" :close-on-click-modal="false"
      :close-on-press-escape="!genDialog.running" :show-close="!genDialog.running">
      <template v-if="!genDialog.running">
        <el-alert v-if="!hasFieldDict" type="warning" :closable="false" show-icon class="gen-tip">
          当前项目还没有保存的标准字段数据（字段字典），请先到「字段字典」中「AI 拟定」并确认保存，再生成居民。
        </el-alert>
        <el-form label-width="90px">
          <el-form-item label="生成数量">
            <el-input-number v-model="genDialog.count" :min="1" :max="500" style="width: 160px" />
            <span class="muted form-hint">1~500 人，AI 分批生成（每批 30）</span>
          </el-form-item>
        </el-form>
        <div class="gen-note muted">生成结果仅作预览，确认后才会入库；AI 严格从字段字典与地点表中选取种族/次级种族/归属/职业/所在地，符合世界观、避免 OOC。</div>
      </template>
      <template v-else>
        <div class="gen-progress">
          <el-progress :percentage="genPercent" :stroke-width="10" />
          <div class="gen-status">已生成 <b>{{ genDialog.generated }}</b> / {{ genDialog.total }} 名居民{{ genDialog.failedBatches ? `（失败批次 ${genDialog.failedBatches}）` : '' }}</div>
        </div>
        <div class="gen-list">
          <div v-for="(item, i) in genDialog.preview" :key="i" class="gen-item">
            <span class="gen-name">{{ item.name }}</span>
            <span class="muted">{{ item.race }}<template v-if="item.subRace">·{{ item.subRace }}</template> · {{ item.occupation }} · {{ item.affiliation }}</span>
          </div>
          <div v-if="!genDialog.preview.length" class="muted">AI 生成中…</div>
        </div>
      </template>
      <template #footer>
        <template v-if="!genDialog.running">
          <el-button @click="genDialog.visible = false">取消</el-button>
          <el-button type="primary" :disabled="!hasFieldDict" :loading="genDialog.generating" @click="startGen">开始生成</el-button>
        </template>
        <template v-else>
          <el-button disabled>生成中，请稍候…</el-button>
        </template>
      </template>
    </el-dialog>

    <!-- 生成结果预览确认对话框 -->
    <el-dialog v-model="genPreview.visible" title="生成结果预览 · 确认入库" width="780px" :close-on-click-modal="false">
      <el-alert type="success" :closable="false" show-icon class="gen-tip">
        已生成 {{ genPreview.items.length }} 名居民{{ genPreview.failedBatches ? `（${genPreview.failedBatches} 批失败，可再次生成补齐）` : '' }}，勾选后确认入库。
      </el-alert>
      <div class="preview-ops">
        <span class="muted">已选 {{ genPreview.selected.length }}/{{ genPreview.items.length }}（表头复选框可全选）</span>
      </div>
      <el-table :data="genPreview.items" size="small" max-height="400" @selection-change="sel => (genPreview.selected = sel)">
        <el-table-column type="selection" width="40" />
        <el-table-column prop="name" label="名称" width="120" show-overflow-tooltip />
        <el-table-column prop="race" label="种族" width="90" show-overflow-tooltip />
        <el-table-column prop="subRace" label="次级种族" width="100" show-overflow-tooltip />
        <el-table-column prop="occupation" label="职业" width="110" show-overflow-tooltip />
        <el-table-column prop="affiliation" label="归属" width="140" show-overflow-tooltip />
        <el-table-column prop="location" label="所在地" width="140" show-overflow-tooltip />
        <el-table-column prop="detail" label="角色详情" min-width="180" show-overflow-tooltip />
      </el-table>
      <template #footer>
        <el-button @click="genPreview.visible = false">取消</el-button>
        <el-button type="primary" :loading="genSaving" :disabled="!genPreview.selected.length" @click="confirmGen">确认入库（{{ genPreview.selected.length }}）</el-button>
      </template>
    </el-dialog>

    <!-- 单个普通 NPC 关系管理对话框（查看现有关系 + AI 生成 + 手动增删 + 批量入库） -->
    <el-dialog v-model="relationDialog.visible" :title="`关系管理 · ${relationDialog.crowdName}`" width="760px" :close-on-click-modal="false">
      <el-alert type="info" :closable="false" show-icon class="cat-tip">
        关系写入 actor_character_relation（与角色拓扑共用）：可关联特殊 NPC / 普通 NPC / 未出现 NPC（按名幽灵）。
        入库方式可选「追加」或「重建」（重建将清空该普通 NPC 的相关关系后写入）。
      </el-alert>
      <div class="rel-ops">
        <el-button size="small" type="primary" :loading="relationDialog.generating" @click="runRelationAi">
          <el-icon><HIcon name="Highlight" /></el-icon>&nbsp;AI 生成该 NPC 的关系
        </el-button>
        <el-button size="small" @click="addRelDraft">
          <el-icon><HIcon name="Plus" /></el-icon>&nbsp;手动新增关系
        </el-button>
        <el-radio-group v-model="relationDialog.mode" size="small">
          <el-radio-button value="supplement">追加</el-radio-button>
          <el-radio-button value="rebuild">重建</el-radio-button>
        </el-radio-group>
      </div>

      <div class="rel-sec-title">现有关系（{{ relationDialog.existing.length }}）</div>
      <div v-if="relationDialog.existing.length" class="rel-existing">
        <div v-for="(r, i) in relationDialog.existing" :key="i" class="rel-existing-row">
          <el-tag size="small" effect="plain" type="primary">{{ r.type }}</el-tag>
          <span class="rel-text">{{ r.from }} ↔ {{ r.to }}</span>
          <span class="muted rel-desc">{{ r.description || '' }}</span>
        </div>
      </div>
      <div v-else class="muted rel-empty">该普通 NPC 暂无关系（可「AI 生成」或「手动新增」）。</div>

      <div class="rel-sec-title">待保存草稿（{{ relationDialog.drafts.length }}）</div>
      <div v-if="relationDialog.drafts.length" class="rel-drafts">
        <div v-for="d in relationDialog.drafts" :key="d._key" class="rel-draft-row">
          <el-checkbox v-model="d.checked" />
          <el-input v-model="d.from" size="small" placeholder="角色 A（可空=本 NPC）" style="width: 130px" />
          <el-input v-model="d.to" size="small" placeholder="角色 B" style="width: 130px" />
          <el-input v-model="d.relationType" size="small" placeholder="关系类型" style="width: 110px" />
          <el-input v-model="d.description" size="small" placeholder="描述" style="flex: 1" />
          <el-button size="small" text type="danger" @click="removeRelDraft(d)">
            <el-icon><HIcon name="Delete" /></el-icon>
          </el-button>
        </div>
      </div>
      <div v-else class="muted rel-empty">暂无草稿，点击「AI 生成该 NPC 的关系」或「手动新增关系」。</div>

      <template #footer>
        <el-button @click="relationDialog.visible = false">关闭</el-button>
        <el-button type="primary" :loading="relationDialog.saving" :disabled="!relationDialog.drafts.length"
          @click="saveCrowdRelations">保存关系（{{ checkedDraftCount }}）</el-button>
      </template>
    </el-dialog>

    <!-- 为全部普通 NPC 生成关系 · 预览确认对话框（scope=project） -->
    <el-dialog v-model="allRelPreview.visible" title="AI 生成全部普通 NPC 关系 · 预览确认" width="760px" :close-on-click-modal="false">
      <el-alert type="warning" :closable="false" show-icon class="cat-tip">
        以下关系由 AI 依据世界观 + 全项目名单拟定（覆盖特殊 NPC / 普通 NPC / 幽灵），勾选后确认入库；
        保存方式：{{ allRelPreview.mode === 'rebuild' ? '重建（清空项目关系后写入）' : '追加（保留已有关系）' }}。
      </el-alert>
      <div class="preview-ops">
        <span class="muted">已选 {{ allRelPreview.selected.length }}/{{ allRelPreview.items.length }}（表头复选框可全选）</span>
      </div>
      <el-table :data="allRelPreview.items" size="small" max-height="400" @selection-change="sel => (allRelPreview.selected = sel)">
        <el-table-column type="selection" width="40" />
        <el-table-column prop="from" label="角色 A" width="140" show-overflow-tooltip />
        <el-table-column prop="to" label="角色 B" width="140" show-overflow-tooltip />
        <el-table-column prop="relationType" label="关系类型" width="120" show-overflow-tooltip />
        <el-table-column prop="description" label="描述" min-width="180" show-overflow-tooltip />
      </el-table>
      <template #footer>
        <el-button @click="allRelPreview.visible = false">取消</el-button>
        <el-button type="primary" :loading="allRelPreview.saving" :disabled="!allRelPreview.selected.length"
          @click="confirmAllRelations">确认入库（{{ allRelPreview.selected.length }}）</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 普通型人群页（项目级，2026-08-19 分类体系重构后）。
 * <p>职责：普通型 NPC 的表格化管理——新字段列（名称/性别/种族/次级种族/年龄/归属/职业/所在地/详情/关系/操作），
 * 全部字段可筛选可排序（走后端 NpcQuery + sortBy/sortDir 白名单）+ 关键词 + 分页；
 * 标准字段数据（字段字典）管理（AI 一次性拟定 → 预览确认【字段树含出处 + 主次分类字段选择】→ 整体保存；
 * 手动增删行）；统计按主/次分类字段分布 + 归属分布；AI 分批生成（SSE 逐条 + 前置校验字段字典）；
 * 普通 NPC 关系单独生成（复用角色关系生成流程，写 actor_character_relation，可查看/手动增删）；
 * 两级 AI 调度（项目级按主次分类字段分组 + 归属下发指令 → 归属级合并执行）/ 程序化调度 / 定时开关 / 环境摘要。</p>
 * <p>数据来源：/api/projects/{id}/ordinary-npcs 系列接口 + /api/projects/{id}/character-relations。</p>
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchOrdinaryNpcs, createOrdinaryNpc, updateOrdinaryNpc, deleteOrdinaryNpc,
  batchDeleteOrdinaryNpcs, fetchOrdinaryNpcStats,
  fetchFieldDict, generateFieldDict, saveFieldDict,
  generateOrdinaryNpcsStream, batchSaveOrdinaryNpcs,
  generateCrowdRelations, generateAllCrowdRelations, batchSaveCrowdRelations,
  scheduleOrdinaryNpcs, scheduleOrdinaryNpcsAi, fetchOrdinaryNpcEnvSummary, fetchOrdinaryNpcRuntime,
  setOrdinaryNpcScheduleEnabled,
  fetchCharacterRelations, batchSaveCharacterRelations
} from '@/shared/api'

const props = defineProps({ projectId: { type: [String, Number], default: null } })
const route = useRoute()
const projectId = props.projectId || route.params.id

// ===== 列表 / 筛选 / 排序 / 分页 =====
const list = ref([])
const loading = ref(false)
const total = ref(0)
const page = ref(1)
const size = ref(20)
const selection = ref([])
const filters = reactive({ gender: '', race: '', subRace: '', affiliation: '', occupation: '', location: '', ageMin: null, ageMax: null, keyword: '' })
const sortState = reactive({ sortBy: '', sortDir: 'asc' })

// ===== 统计 / 字段字典 / 环境 / 调度 =====
const stats = ref({})
const fieldDict = ref({ race: [], affiliation: [], occupation: [] })
const envSummary = ref('')
const scheduleEnabled = ref(false)
const scheduleSaving = ref(false)
const runtime = ref({})

// ===== 编辑对话框 =====
const editDialog = reactive({ visible: false, mode: 'create', form: {} })
const editFormRef = ref(null)
const editSaving = ref(false)
const editRules = { name: [{ required: true, message: '请输入名称', trigger: 'blur' }] }

// ===== 字段字典管理 =====
const classifyFieldOptions = [
  { value: 'race', label: '种族' },
  { value: 'affiliation', label: '归属' },
  { value: 'occupation', label: '职业' }
]
const fieldDictDialog = reactive({
  visible: false,
  mode: 'manage', // manage=当前已保存；preview=AI 拟定预览待确认
  primaryField: '',
  secondaryField: '',
  generating: false,
  saving: false,
  rows: { race: [], affiliation: [], occupation: [] }
})
const dictGroups = [
  { field: 'race', title: '种族（含次级种族）' },
  { field: 'affiliation', title: '归属' },
  { field: 'occupation', title: '职业' }
]

// ===== AI 生成（SSE 进度 + 预览确认） =====
const genDialog = reactive({ visible: false, running: false, generating: false, count: 30, total: 0, generated: 0, failedBatches: 0, preview: [] })
const genPreview = reactive({ visible: false, items: [], selected: [], failedBatches: 0 })
const genSaving = ref(false)

// ===== 关系 =====
const relationDialog = reactive({
  visible: false,
  crowdId: null,
  crowdName: '',
  existing: [], // 现有关系（来自拓扑图，按名过滤）
  drafts: [], // 待保存草稿（AI 生成 + 手动新增）
  mode: 'supplement',
  generating: false,
  saving: false
})
const allRelPreview = reactive({ visible: false, items: [], selected: [], mode: 'supplement', saving: false })
const relationAllLoading = ref(false)

// ===== 调度 =====
const scheduling = ref(false)
const schedulingAi = ref(false)

onMounted(() => {
  loadPage()
  loadStats()
  loadFieldDict()
  loadEnv()
  loadRuntime()
})

// ==================== 列表 ====================

/** 加载分页列表（带筛选 + 排序）。 */
async function loadPage() {
  loading.value = true
  try {
    const params = { page: page.value, size: size.value }
    for (const [k, v] of Object.entries(filters)) {
      if (v !== '' && v !== null && v !== undefined) params[k] = v
    }
    if (sortState.sortBy) { params.sortBy = sortState.sortBy; params.sortDir = sortState.sortDir }
    const res = await fetchOrdinaryNpcs(projectId, params)
    list.value = res.list || []
    total.value = res.total || 0
    page.value = res.page || 1
    selection.value = []
  } catch (e) {
    ElMessage.error(e.message || '加载居民失败')
  } finally {
    loading.value = false
  }
}

/** 加载统计（总数/主次分类字段分布/归属分布）。 */
async function loadStats() {
  try {
    stats.value = await fetchOrdinaryNpcStats(projectId) || {}
  } catch (_) {
    stats.value = {}
  }
}

/** 加载字段字典（筛选下拉/生成前置校验共用）。 */
async function loadFieldDict() {
  try {
    fieldDict.value = await fetchFieldDict(projectId) || { race: [], affiliation: [], occupation: [] }
  } catch (_) {
    fieldDict.value = { race: [], affiliation: [], occupation: [] }
  }
}

/** 加载环境摘要。 */
async function loadEnv() {
  try {
    const res = await fetchOrdinaryNpcEnvSummary(projectId)
    envSummary.value = res?.summary || ''
  } catch (_) {
    envSummary.value = ''
  }
}

/** 加载项目级调度运行时信息（开关/主次分类字段/上次调度）。 */
async function loadRuntime() {
  try {
    const r = await fetchOrdinaryNpcRuntime(projectId) || {}
    runtime.value = r
    scheduleEnabled.value = !!r.enabled
  } catch (_) {
    runtime.value = {}
    scheduleEnabled.value = false
  }
}

// ==================== 筛选 / 排序 ====================

/** 切换种族筛选：清空次级种族后查询。 */
function onRaceFilterChange() {
  if (!filters.race) filters.subRace = ''
  search()
}

/** 查询（回到第一页）。 */
function search() {
  page.value = 1
  loadPage()
}

/** 重置筛选与排序。 */
function resetFilter() {
  Object.assign(filters, { gender: '', race: '', subRace: '', affiliation: '', occupation: '', location: '', ageMin: null, ageMax: null, keyword: '' })
  sortState.sortBy = ''
  sortState.sortDir = 'asc'
  page.value = 1
  loadPage()
}

function onSizeChange(s) {
  size.value = s
  page.value = 1
  loadPage()
}

function onPageChange(p) {
  page.value = p
  loadPage()
}

/** 列排序（el-table sortable="custom"）：映射到后端 sortBy/sortDir 白名单。 */
function onSortChange({ prop, order }) {
  if (!prop || !order) {
    sortState.sortBy = ''
    sortState.sortDir = 'asc'
  } else {
    sortState.sortBy = prop
    sortState.sortDir = order === 'descending' ? 'desc' : 'asc'
  }
  search()
}

// ==================== CRUD ====================

/** 打开新增居民对话框。 */
function openCreate() {
  editDialog.mode = 'create'
  editDialog.form = { name: '', gender: '', race: '', subRace: '', age: null, affiliation: '', location: '', occupation: '', detail: '' }
  editDialog.visible = true
}

/** 打开编辑居民对话框（回显含次级种族）。 */
function openEdit(row) {
  editDialog.mode = 'edit'
  editDialog.form = {
    name: row.name || '', gender: row.gender || '', race: row.race || '', subRace: row.subRace || '', age: row.age,
    affiliation: row.affiliation || '', location: row.location || '', occupation: row.occupation || '', detail: row.detail || ''
  }
  editDialog._id = row.id
  editDialog.visible = true
}

/** 新增/编辑保存。 */
async function saveEdit() {
  await editFormRef.value.validate()
  editSaving.value = true
  try {
    const form = editDialog.form
    const data = {
      name: form.name, gender: form.gender || undefined, race: form.race || undefined,
      subRace: form.subRace || undefined, age: form.age, affiliation: form.affiliation || undefined,
      location: form.location || undefined, occupation: form.occupation || undefined, detail: form.detail || undefined
    }
    if (editDialog.mode === 'create') {
      await createOrdinaryNpc(projectId, data)
      ElMessage.success('居民已新增')
    } else {
      await updateOrdinaryNpc(editDialog._id, data)
      ElMessage.success('居民已更新')
    }
    editDialog.visible = false
    await loadPage()
    await loadStats()
    await loadEnv()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    editSaving.value = false
  }
}

/** 删除单个居民。 */
async function doDelete(row) {
  try {
    await ElMessageBox.confirm(`确认删除居民「${row.name}」？`, '删除确认', { type: 'warning' })
  } catch (_) {
    return
  }
  try {
    await deleteOrdinaryNpc(row.id)
    ElMessage.success('已删除')
    await loadPage()
    await loadStats()
    await loadEnv()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

/** 批量删除。 */
async function doBatchDelete() {
  const names = selection.value.map(r => r.name).join('、')
  try {
    await ElMessageBox.confirm(`确认删除选中的 ${selection.value.length} 名居民（${names}）？`, '批量删除确认', { type: 'warning' })
  } catch (_) {
    return
  }
  try {
    const res = await batchDeleteOrdinaryNpcs(projectId, selection.value.map(r => r.id))
    ElMessage.success(`已删除 ${res.deleted || 0} 名居民`)
    await loadPage()
    await loadStats()
    await loadEnv()
  } catch (e) {
    ElMessage.error(e.message || '批量删除失败')
  }
}

// ==================== 字段字典管理 ====================

/** 打开字段字典管理对话框（同步已保存字典 + 主次分类字段）。 */
async function openFieldDict() {
  await loadFieldDict()
  fieldDictDialog.rows = {
    race: (fieldDict.value.race || []).map(dictToRow),
    affiliation: (fieldDict.value.affiliation || []).map(dictToRow),
    occupation: (fieldDict.value.occupation || []).map(dictToRow)
  }
  fieldDictDialog.primaryField = runtime.value.primaryField || ''
  fieldDictDialog.secondaryField = runtime.value.secondaryField || ''
  if (!fieldDictDialog.primaryField || !fieldDictDialog.secondaryField) {
    fieldDictDialog.primaryField = fieldDictDialog.primaryField || 'race'
    fieldDictDialog.secondaryField = fieldDictDialog.secondaryField || 'affiliation'
  }
  fieldDictDialog.mode = 'manage'
  fieldDictDialog.visible = true
}

/** 字典条目 → 可编辑行。 */
function dictToRow(d) {
  return { _key: `${Date.now()}-${Math.random().toString(36).slice(2, 6)}`, field: d.field, level1: d.level1, level2: d.level2, source: d.source }
}

function addDictRow(field) {
  fieldDictDialog.rows[field].push({ _key: `new-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`, field, level1: '', level2: '', source: '' })
}

function removeDictRow(field, row) {
  fieldDictDialog.rows[field] = fieldDictDialog.rows[field].filter(r => r._key !== row._key)
}

/** AI 一次性拟定全部字段字典 + 主/次分类字段（不落库，切到预览模式）。 */
async function generateFieldDictAI() {
  fieldDictDialog.generating = true
  try {
    const preview = await generateFieldDict(projectId)
    fieldDictDialog.rows = {
      race: (preview.fields?.race || []).map(dictToRow),
      affiliation: (preview.fields?.affiliation || []).map(dictToRow),
      occupation: (preview.fields?.occupation || []).map(dictToRow)
    }
    fieldDictDialog.primaryField = preview.primaryField || fieldDictDialog.primaryField || 'race'
    fieldDictDialog.secondaryField = preview.secondaryField || fieldDictDialog.secondaryField || 'affiliation'
    if (fieldDictDialog.primaryField === fieldDictDialog.secondaryField) {
      fieldDictDialog.secondaryField = classifyFieldOptions.find(o => o.value !== fieldDictDialog.primaryField)?.value || 'affiliation'
    }
    fieldDictDialog.mode = 'preview'
    ElMessage.success(`AI 已拟定：种族 ${fieldDictDialog.rows.race.length} 条、归属 ${fieldDictDialog.rows.affiliation.length} 条、职业 ${fieldDictDialog.rows.occupation.length} 条，请核对后确认保存`)
  } catch (e) {
    ElMessage.error(e.message || 'AI 拟定字段字典失败')
  } finally {
    fieldDictDialog.generating = false
  }
}

/** 校验并保存字段字典（整体替换 + 主次分类字段）。 */
async function saveFieldDictAll() {
  const primary = fieldDictDialog.primaryField
  const secondary = fieldDictDialog.secondaryField
  if (!primary || !secondary) {
    ElMessage.warning('请选择主/次分类字段（从 种族/归属/职业 中各选一个）')
    return
  }
  if (primary === secondary) {
    ElMessage.warning('主分类字段与次分类字段不能相同')
    return
  }
  const fields = { race: [], affiliation: [], occupation: [] }
  let valid = 0
  for (const grp of dictGroups) {
    for (const row of fieldDictDialog.rows[grp.field]) {
      const level1 = String(row.level1 || '').trim()
      if (!level1) continue
      fields[grp.field].push({
        field: grp.field,
        level1,
        level2: grp.field === 'race' ? String(row.level2 || '').trim() || null : null,
        source: String(row.source || '').trim() || undefined
      })
      valid++
    }
  }
  if (!fields.race.length || !fields.affiliation.length) {
    ElMessage.warning('至少需要种族（含次级种族）与归属各一条标准值')
    return
  }
  fieldDictDialog.saving = true
  try {
    await saveFieldDict(projectId, { primaryField: primary, secondaryField: secondary, fields })
    ElMessage.success(`字段字典已保存（${valid} 条，主=${fieldLabel(primary)} 次=${fieldLabel(secondary)}）`)
    fieldDictDialog.mode = 'manage'
    fieldDictDialog.visible = false
    await loadFieldDict()
    await loadRuntime()
    await loadStats()
  } catch (e) {
    ElMessage.error(e.message || '保存字段字典失败')
  } finally {
    fieldDictDialog.saving = false
  }
}

// ==================== AI 生成 ====================

/** 打开生成对话框（前置校验：已保存字段字典）。 */
function openGen() {
  if (!hasFieldDict.value) {
    ElMessage.warning('请先在「字段字典」中 AI 拟定并确认保存标准字段数据，再生成居民')
    openFieldDict()
    return
  }
  genDialog.count = 30
  genDialog.preview = []
  genDialog.generated = 0
  genDialog.total = 0
  genDialog.failedBatches = 0
  genDialog.running = false
  genDialog.visible = true
}

/** 开始 AI 生成（SSE 流式进度）。 */
async function startGen() {
  genDialog.generating = true
  genDialog.running = true
  genDialog.generated = 0
  genDialog.failedBatches = 0
  genDialog.preview = []
  genDialog.total = genDialog.count
  try {
    await generateOrdinaryNpcsStream(projectId, genDialog.count, {
      onStart: (d) => {
        genDialog.total = d?.count || genDialog.count
      },
      onNpc: (d) => {
        genDialog.generated++
        genDialog.preview.push({ ...d })
      },
      onDone: (d) => {
        genDialog.failedBatches = d?.failedBatches || 0
        if (genDialog.preview.length) {
          genPreview.items = genDialog.preview.map(x => ({ ...x }))
          genPreview.selected = []
          genPreview.failedBatches = genDialog.failedBatches
          genPreview.visible = true
        } else {
          ElMessage.warning('AI 未生成有效居民，请检查世界观/字段字典后重试')
        }
      },
      onError: (msg) => {
        ElMessage.error(msg || 'AI 生成失败')
      }
    })
  } catch (e) {
    ElMessage.error(e.message || 'AI 生成失败')
  } finally {
    genDialog.running = false
    genDialog.generating = false
  }
}

/** 生成结果预览确认入库。 */
async function confirmGen() {
  const items = genPreview.selected.map(r => ({
    name: r.name, gender: r.gender || undefined, race: r.race || undefined, subRace: r.subRace || undefined,
    age: r.age, affiliation: r.affiliation || undefined, location: r.location || undefined,
    occupation: r.occupation || undefined, detail: r.detail || undefined
  }))
  if (!items.length) {
    ElMessage.warning('请至少勾选一名居民')
    return
  }
  genSaving.value = true
  try {
    const res = await batchSaveOrdinaryNpcs(projectId, items)
    ElMessage.success(`已入库 ${res.saved || 0} 名居民`)
    genPreview.visible = false
    genDialog.visible = false
    await loadPage()
    await loadStats()
    await loadEnv()
  } catch (e) {
    ElMessage.error(e.message || '入库失败')
  } finally {
    genSaving.value = false
  }
}

// ==================== 关系（普通 NPC） ====================

/**
 * 打开单普通 NPC 关系管理对话框。
 * @param row          表格行（普通 NPC）
 * @param autoGenerate 是否自动发起 AI 生成（「生成」按钮）
 */
async function openRelation(row, autoGenerate) {
  relationDialog.crowdId = row.id
  relationDialog.crowdName = row.name
  relationDialog.mode = 'supplement'
  relationDialog.drafts = []
  relationDialog.existing = []
  relationDialog.visible = true
  await loadRelationExisting(row.name)
  if (autoGenerate) await runRelationAi()
}

/** 加载该普通 NPC 的现有关系（从项目拓扑图数据按名称过滤；普通 NPC 关系以名称兜底存储）。 */
async function loadRelationExisting(name) {
  try {
    const graph = await fetchCharacterRelations(projectId)
    relationDialog.existing = (graph.relations || [])
      .filter(r => r.fromName === name || r.toName === name)
      .map(r => ({
        type: r.relationType || '未知关系',
        from: r.fromName,
        to: r.toName,
        description: r.description || ''
      }))
  } catch (_) {
    relationDialog.existing = []
  }
}

/** 手动新增关系草稿行（from 预填本 NPC，可改）。 */
function addRelDraft() {
  relationDialog.drafts.push({
    _key: `d-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
    from: relationDialog.crowdName,
    to: '',
    relationType: '',
    description: '',
    checked: true
  })
}

function removeRelDraft(d) {
  relationDialog.drafts = relationDialog.drafts.filter(x => x._key !== d._key)
}

/** AI 为该普通 NPC 生成关系（scope=crowd，合并进草稿）。 */
async function runRelationAi() {
  relationDialog.generating = true
  try {
    // 已有关系时先让用户选「重建/追加」
    let mode = relationDialog.mode
    if (relationDialog.existing.length) {
      try {
        await ElMessageBox.confirm(
          `「${relationDialog.crowdName}」已存在 ${relationDialog.existing.length} 条关系，请选择生成方式：\n「重建」将清空该 NPC 的相关关系后重新生成；\n「补充」保留已有关系，仅追加新识别到的。`,
          '生成方式', { confirmButtonText: '重建并生成', cancelButtonText: '补充生成', type: 'warning', distinguishCancelAndClose: true }
        )
        mode = 'rebuild'
      } catch (action) {
        if (action === 'cancel') mode = 'supplement'
        else return // 点右上角 X 关闭：取消整个操作
      }
    }
    const drafts = await generateCrowdRelations(projectId, relationDialog.crowdId, mode)
    relationDialog.mode = mode
    if (!drafts.length) {
      ElMessage.info('AI 未识别到该普通 NPC 的关系，可完善世界观与角色信息后重试')
      return
    }
    // 合并进草稿（去重：from|to|type）
    const seen = new Set(relationDialog.drafts.map(d => `${d.from}|${d.to}|${d.relationType}`))
    for (const d of drafts) {
      const key = `${d.from}|${d.to}|${d.relationType}`
      if (seen.has(key)) continue
      seen.add(key)
      relationDialog.drafts.push({ _key: `ai-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`, ...d, checked: true })
    }
    ElMessage.success(`AI 生成 ${drafts.length} 条关系，已加入待保存草稿，请核对后保存`)
  } catch (e) {
    ElMessage.error(e.message || '关系生成失败')
  } finally {
    relationDialog.generating = false
  }
}

/** 保存该普通 NPC 的关系草稿（batchSave，按 crowdId 重建/追加）。 */
async function saveCrowdRelations() {
  const items = relationDialog.drafts
    .filter(d => d.checked && d.from && String(d.from).trim() && d.to && String(d.to).trim())
    .map(d => ({ from: String(d.from).trim(), to: String(d.to).trim(), relationType: d.relationType, description: d.description }))
  if (!items.length) {
    ElMessage.warning('请至少勾选并填写一条完整的关系（A 与 B 均非空）')
    return
  }
  relationDialog.saving = true
  try {
    const res = await batchSaveCrowdRelations(projectId, relationDialog.crowdId, relationDialog.mode, items)
    ElMessage.success(`已保存 ${res?.added ?? items.length} 条关系`)
    relationDialog.visible = false
  } catch (e) {
    ElMessage.error(e.message || '关系保存失败')
  } finally {
    relationDialog.saving = false
  }
}

/** 为全部普通 NPC（全项目范围）生成关系：先选重建/追加 → AI 预览 → 确认入库。 */
async function openAllRelations() {
  relationAllLoading.value = true
  try {
    let mode = 'supplement'
    const graph = await fetchCharacterRelations(projectId)
    if ((graph.relations || []).length) {
      try {
        await ElMessageBox.confirm(
          '项目已存在关系数据，请选择生成方式：\n「重建」将清空整个项目的关系表后重新生成；\n「补充」保留已有关系，仅追加新识别到的。',
          '生成方式', { confirmButtonText: '重建并生成', cancelButtonText: '补充生成', type: 'warning', distinguishCancelAndClose: true }
        )
        mode = 'rebuild'
      } catch (action) {
        if (action === 'cancel') mode = 'supplement'
        else return // 点右上角 X 关闭：取消整个操作
      }
    }
    const drafts = await generateAllCrowdRelations(projectId, mode)
    allRelPreview.items = (drafts || []).map(d => ({ ...d }))
    allRelPreview.selected = []
    allRelPreview.mode = mode
    if (!allRelPreview.items.length) {
      ElMessage.info('AI 未识别到任何关系，可先完善世界观与角色信息后重试')
    } else {
      allRelPreview.visible = true
    }
  } catch (e) {
    ElMessage.error(e.message || '关系生成失败')
  } finally {
    relationAllLoading.value = false
  }
}

/** 全部普通 NPC 关系预览确认入库（scope=project，mode 重建/追加）。 */
async function confirmAllRelations() {
  const items = allRelPreview.selected.map(({ from, to, relationType, description }) => ({ from, to, relationType, description }))
  if (!items.length) {
    ElMessage.warning('请至少勾选一条关系')
    return
  }
  allRelPreview.saving = true
  try {
    const res = await batchSaveCharacterRelations(projectId, { mode: allRelPreview.mode, items })
    ElMessage.success(`已入库 ${res?.added ?? items.length} 条关系（可在「角色关系拓扑」中查看）`)
    allRelPreview.visible = false
  } catch (e) {
    ElMessage.error(e.message || '入库失败')
  } finally {
    allRelPreview.saving = false
  }
}

// ==================== 调度 / 环境 ====================

/** 调度：false=程序化，true=两级 AI。 */
async function doSchedule(useAi) {
  if (useAi) {
    try {
      await ElMessageBox.confirm('AI 两级调度将调用多次 AI（项目级 1 次 + 每个归属各 1 次），会产生 token 消耗。继续？', 'AI 调度确认', { type: 'warning' })
    } catch (_) {
      return
    }
  }
  if (useAi) schedulingAi.value = true
  else scheduling.value = true
  try {
    const res = useAi ? await scheduleOrdinaryNpcsAi(projectId) : await scheduleOrdinaryNpcs(projectId)
    ElMessage.success(`调度完成（${useAi ? '两级 AI' : '程序化'}）：${res.summary || '已推进居民状态'}`)
    await loadPage()
    await loadEnv()
    await loadRuntime()
  } catch (e) {
    ElMessage.error(e.message || '调度失败')
  } finally {
    scheduling.value = false
    schedulingAi.value = false
  }
}

/** 项目级定时调度开关。 */
async function toggleSchedule(v) {
  scheduleSaving.value = true
  try {
    const r = await setOrdinaryNpcScheduleEnabled(projectId, v)
    scheduleEnabled.value = !!r?.enabled
    runtime.value = r || {}
    ElMessage.success(v ? '已开启定时调度（每 5 分钟程序化推进）' : '已关闭定时调度')
  } catch (e) {
    scheduleEnabled.value = !v
    ElMessage.error(e.message || '设置失败')
  } finally {
    scheduleSaving.value = false
  }
}

// ==================== 计算属性 ====================

/** 已保存字段字典是否存在（生成居民前置校验）。 */
const hasFieldDict = computed(() => !!fieldDict.value.race?.length && !!fieldDict.value.affiliation?.length)

/** 主/次分类字段中文标签。 */
const primaryFieldLabel = computed(() => fieldLabel(stats.value.primaryField))
const secondaryFieldLabel = computed(() => fieldLabel(stats.value.secondaryField))

/** 归属分布 Top 5。 */
const topAffiliations = computed(() => {
  const by = stats.value.byAffiliation || {}
  return Object.entries(by).sort((a, b) => b[1] - a[1]).slice(0, 5)
})

/** 筛选/编辑下拉选项（取自字段字典，去重保序）。 */
const raceOptions = computed(() => unique((fieldDict.value.race || []).map(r => r.level1)))
const subRaceOptions = computed(() => unique((fieldDict.value.race || [])
  .filter(r => !filters.race || r.level1 === filters.race).map(r => r.level2).filter(Boolean)))
const affiliationOptions = computed(() => unique((fieldDict.value.affiliation || []).map(r => r.level1)))
const occupationOptions = computed(() => unique((fieldDict.value.occupation || []).map(r => r.level1)))
const genderOptions = ['男', '女', '无性', '自定义']
const locationOptions = computed(() => unique(list.value.map(r => r.location).filter(Boolean)))

/** 编辑表单里当前种族对应的次级种族选项。 */
const formSubRaceOptions = computed(() => {
  const race = editDialog.form.race
  if (!race) return []
  return unique((fieldDict.value.race || []).filter(r => r.level1 === race).map(r => r.level2).filter(Boolean))
})

/** 字段字典总条数。 */
const fieldDictTotal = computed(() => dictGroups.reduce((n, g) => n + fieldDictDialog.rows[g.field].length, 0))

/** 草稿勾选数（关系保存按钮计数）。 */
const checkedDraftCount = computed(() => relationDialog.drafts.filter(d => d.checked).length)

/** 生成进度百分比。 */
const genPercent = computed(() => {
  if (!genDialog.total) return 0
  return Math.min(100, Math.round((genDialog.generated / genDialog.total) * 100))
})

// ==================== 表单联动 ====================

/** 编辑表单切换种族：清空次级种族。 */
function onFormRaceChange() {
  editDialog.form.subRace = ''
}

// ==================== 展示工具 ====================

function formatTime(t) {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(0, 19)
}

function stateText(s) {
  return ({ idle: '空闲', walk: '行走', stop: '驻足', talk: '交谈', rest: '休息' })[s] || s || '空闲'
}

function stateType(s) {
  return ({ idle: 'info', walk: 'primary', stop: 'success', talk: 'warning', rest: 'info' })[s] || 'info'
}

function fieldLabel(f) {
  return ({ race: '种族', affiliation: '归属', occupation: '职业' })[f] || f || ''
}

/** 去重保序。 */
function unique(arr) {
  return [...new Set(arr.filter(v => v !== null && v !== undefined && String(v).trim() !== ''))]
}
</script>

<style scoped>
.crowd-view { max-width: 1360px; margin: 0 auto; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; }
.page-title { font-size: 1.3rem; font-weight: 700; color: var(--text-primary); margin-bottom: 6px; }
.page-desc { font-size: 0.85rem; color: var(--text-secondary); }
.header-ops { display: flex; gap: 8px; flex-wrap: wrap; }
.env-alert { margin-bottom: 14px; }
.env-title { font-weight: 600; margin-bottom: 4px; }
.env-body { font-size: 0.88rem; color: var(--text-secondary); line-height: 1.6; white-space: pre-wrap; }
.stats-row { display: flex; align-items: center; gap: 28px; padding: 12px 16px; margin-bottom: 12px;
  border: 1px solid var(--border-l1); border-radius: 10px; background: var(--bg-layer-1); flex-wrap: wrap; }
.stats-block { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.aff-tag { margin: 0; }
.schedule-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.filter-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; flex-wrap: wrap; }
.pager { display: flex; justify-content: flex-end; margin-top: 14px; }
.muted { color: var(--text-secondary); font-size: 0.8rem; }
.form-hint { margin-left: 8px; font-size: 0.78rem; }
.cat-tip { margin-bottom: 12px; }
.cat-ops { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.classify-row { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.classify-label { color: var(--text-secondary); font-size: 0.82rem; }
.dict-group { margin-bottom: 14px; }
.dict-group-title { display: flex; align-items: center; gap: 10px; font-weight: 600; margin-bottom: 6px; }
.dict-count { font-weight: 400; }
.dict-table { border: 1px solid var(--border-l1); border-radius: 8px; padding: 8px; }
.dict-row { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.dict-row:last-child { margin-bottom: 0; }
.gen-tip { margin-bottom: 12px; }
.gen-progress { margin-bottom: 10px; }
.gen-status { margin-top: 6px; font-size: 0.85rem; }
.gen-list { max-height: 280px; overflow: auto; border: 1px solid var(--border-l1); border-radius: 8px; padding: 8px 12px; }
.gen-item { display: flex; justify-content: space-between; gap: 8px; padding: 4px 0; border-bottom: 1px dashed var(--border-l1); }
.gen-item:last-child { border-bottom: none; }
.gen-name { font-weight: 600; }
.gen-note { margin-top: 6px; }
.preview-ops { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.rel-ops { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.rel-sec-title { font-weight: 600; margin: 10px 0 6px; }
.rel-existing { max-height: 200px; overflow: auto; border: 1px solid var(--border-l1); border-radius: 8px; padding: 8px 12px; }
.rel-existing-row { display: flex; align-items: center; gap: 8px; padding: 4px 0; border-bottom: 1px dashed var(--border-l1); }
.rel-existing-row:last-child { border-bottom: none; }
.rel-text { font-size: 0.85rem; }
.rel-desc { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rel-empty { padding: 8px 0; }
.rel-drafts { max-height: 260px; overflow: auto; border: 1px solid var(--border-l1); border-radius: 8px; padding: 8px 12px; }
.rel-draft-row { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.rel-draft-row:last-child { margin-bottom: 0; }
</style>
