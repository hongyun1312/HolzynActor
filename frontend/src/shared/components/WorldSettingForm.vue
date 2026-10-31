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
  <div class="world-setting-form">
    <!-- 表头：一句话说明 + 填写完整度（后端「世界观就绪」判据 = 名称 + 至少 3 个分节有内容） -->
    <div class="wsf-head">
      <div class="wsf-head-text">
        <span class="wsf-title">{{ title }}</span>
        <span class="wsf-desc">{{ desc }}</span>
      </div>
      <div class="wsf-head-tags">
        <el-tag size="small" :type="filledSections >= 3 ? 'success' : 'info'" effect="plain">
          分节已填 {{ filledSections }}/6
        </el-tag>
        <el-tag v-if="!named" size="small" type="warning" effect="plain">缺世界观名称</el-tag>
      </div>
    </div>

    <el-tabs v-model="activeTab" tab-position="left" class="wsf-tabs">
      <el-tab-pane label="基本信息" name="basic" lazy>
        <div class="wsf-pane">
          <div class="wsf-field">
            <div class="wsf-label">世界观名称 <span class="wsf-req">*</span></div>
            <el-input v-model="model.name" placeholder="如：星陨之都" maxlength="100" show-word-limit clearable />
            <div class="wsf-hint">世界观标题，也是世界观详情页的标题（世界画廊卡片上的名字以「世界名」为准，保存时两者同步）</div>
          </div>
          <div class="wsf-row">
            <div class="wsf-field">
              <div class="wsf-label">世界类别 <span class="wsf-req">*</span></div>
              <el-select v-model="model.genre" placeholder="从固定八类中选择" clearable class="wsf-select">
                <el-option v-for="c in categories" :key="c.code" :label="c.label" :value="c.label" />
              </el-select>
              <div class="wsf-hint">
                类别是固定枚举（八类），决定世界观生成、角色卡与判定的口径；旧世界的自由文本题材需重新选一次
              </div>
            </div>
            <div class="wsf-field">
              <div class="wsf-label">时代背景</div>
              <el-input v-model="model.era" placeholder="如：上古诸神时代 / 近未来" maxlength="50" clearable />
            </div>
          </div>
          <div class="wsf-field">
            <div class="wsf-label">世界程度</div>
            <el-select v-model="model.tone" placeholder="选择这个世界的严肃程度" class="wsf-select">
              <el-option v-for="t in tones" :key="t.code" :label="t.label" :value="t.code">
                <div class="wsf-opt">
                  <span class="wsf-opt-label">{{ t.label }}</span>
                  <span class="wsf-opt-desc">{{ t.summary }}</span>
                </div>
              </el-option>
            </el-select>
            <div class="wsf-hint">{{ toneHint }}</div>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="地理设定" name="geography" lazy>
        <div class="wsf-pane">
          <div class="wsf-field">
            <div class="wsf-label">地理设定</div>
            <el-input
              v-model="model.geography" type="textarea" :rows="12" maxlength="5000" show-word-limit
              placeholder="地理/地图/地貌/生态/版图…（初始化第 2 步会从这里提取「世界观地点」）"
            />
            <div class="wsf-hint">越具体，初始化时提取出的地点越可信（地点是后续地图、角色驻留、行动的底座）</div>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="势力格局" name="factions" lazy>
        <div class="wsf-pane">
          <div class="wsf-field">
            <div class="wsf-label">势力格局</div>
            <el-input
              v-model="model.factions" type="textarea" :rows="12" maxlength="5000" show-word-limit
              placeholder="势力/阵营/组织/政治格局…"
            />
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="规则体系" name="magic" lazy>
        <div class="wsf-pane">
          <div class="wsf-field">
            <div class="wsf-label">规则体系</div>
            <el-input
              v-model="model.magicSystem" type="textarea" :rows="12" maxlength="5000" show-word-limit
              placeholder="规则/体系/能力/法则/魔法/科技…"
            />
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="社会文化" name="culture" lazy>
        <div class="wsf-pane">
          <div class="wsf-field">
            <div class="wsf-label">社会文化</div>
            <el-input
              v-model="model.culture" type="textarea" :rows="12" maxlength="5000" show-word-limit
              placeholder="文化/风俗/民俗/社会/传统/信仰…"
            />
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="历史脉络" name="history" lazy>
        <div class="wsf-pane">
          <div class="wsf-field">
            <div class="wsf-label">历史脉络</div>
            <el-input
              v-model="model.history" type="textarea" :rows="12" maxlength="5000" show-word-limit
              placeholder="历史/脉络/纪元/大事/时间线…"
            />
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="补充设定" name="supplement" lazy>
        <div class="wsf-pane">
          <div class="wsf-field">
            <div class="wsf-label">补充设定 / 完整世界观</div>
            <el-input
              v-model="model.freeText" type="textarea" :rows="12" maxlength="5000" show-word-limit
              placeholder="上述分类之外的其他设定；也是角色卡生成的知识库注入源，越详细角色卡越贴合"
            />
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane v-if="projectId" label="地点详情" name="locations" lazy>
        <div class="wsf-pane">
          <div class="wsf-loc-head">
            <span class="wsf-label">世界观地点表</span>
            <el-tag size="small" type="info" effect="plain">AI 提取 · 增删改查</el-tag>
          </div>
          <WorldLocationTable :project-id="projectId" editable extractable />
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
/**
 * 世界设定表单（共享组件：新建世界向导 / 创意发散回填 / 世界修改页共用同一份）。
 *
 * <p><b>为什么要抽这一个组件</b>：改前「手动添加」面板与「世界修改」页各写了一套九字段表单，
 * 布局、文案、字数上限各不相同；创意发散生成完世界观后只能看只读预览，用户想改一个字都没入口。
 * 抽成一份后：生成完成 → 同一个表单就地回填可编辑；保存口径也只剩一处。</p>
 *
 * <p><b>字段约束对齐后端</b>：name ≤100、genre/era ≤50、五个分节文本 ≤5000（与 WorldSettingDTO 一致）。
 * 后端「世界观就绪」的判据是「名称 + 至少 3 个分节有内容」，所以表头常驻显示分节填写完整度。</p>
 *
 * <p>所属模块：shared/components（跨 features 复用）</p>
 */
import { computed, onMounted, ref } from 'vue'
import WorldLocationTable from '@/shared/components/WorldLocationTable.vue'
import { fetchWorldOptions } from '@/shared/api/worldInit'

const props = defineProps({
  /** 表单数据（父级持有的 reactive 对象，九字段：name/genre/era/geography/factions/magicSystem/culture/history/freeText） */
  model: { type: Object, required: true },
  /** 标题 */
  title: { type: String, default: '世界观设定' },
  /** 说明 */
  desc: { type: String, default: '九个字段都可留空稍后补；初始化前至少要填「世界观名称 + 3 个分节」。' },
  /** 已存在项目 ID（传了才显示「地点详情」表；新建流程中还没建项目时不显示） */
  projectId: { type: [Number, String], default: null }
})

const activeTab = ref('basic')

/**
 * 固定八类（后端枚举的兜底副本）：接口挂了也要能选，不能因为一个下拉的数据源失败就
 * 让用户连世界观都填不了。真实的 code/label 以接口返回为准。
 */
const CATEGORY_FALLBACK = [
  { code: 'qihuan', label: '奇幻' }, { code: 'kehuan', label: '科幻' },
  { code: 'dushi', label: '都市' }, { code: 'lishi', label: '历史' },
  { code: 'moshi', label: '末世' }, { code: 'xianxia', label: '仙侠' },
  { code: 'richang', label: '日常' }, { code: 'xuanyi', label: '悬疑' }
]

/** 世界程度四档（兜底副本） */
const TONE_FALLBACK = [
  { code: 'casual', label: '休闲', summary: '生活向：不出现势力斗争、战争与宏观推演' },
  { code: 'light', label: '轻度', summary: '允许小摩擦：不出现战争与政权更替' },
  { code: 'standard', label: '标准', summary: '按题材本身的世界尺度正常推进' },
  { code: 'hard', label: '硬核', summary: '势力博弈、战争与灾变全开' }
]

const categories = ref(CATEGORY_FALLBACK)
const tones = ref(TONE_FALLBACK)

/** 当前档位的说明（下拉被收起时也要能读到边界，避免用户只看到两个字就选） */
const toneHint = computed(() => {
  const code = props.model && props.model.tone ? props.model.tone : 'standard'
  const hit = tones.value.find((t) => t.code === code)
  const base = hit ? hit.summary : '按题材本身的世界尺度正常推进'
  return `${base}。程度决定世界运行时的边界：休闲/轻度档不会调度宏观社会演化，也不会生成宏观模板。`
})

onMounted(async () => {
  try {
    const opt = await fetchWorldOptions()
    if (opt && Array.isArray(opt.categories) && opt.categories.length) {
      categories.value = opt.categories
    }
    if (opt && Array.isArray(opt.tones) && opt.tones.length) {
      tones.value = opt.tones
    }
  } catch (_) {
    // 接口不可用时保留兜底清单（八个类别是硬约束，前端不能因此空白）
  }
})

/** 六个分节里有内容的数量（与后端 settingReady 同一口径） */
const filledSections = computed(() => {
  const m = props.model || {}
  return [m.geography, m.factions, m.magicSystem, m.culture, m.history, m.freeText]
    .filter((v) => v && String(v).trim()).length
})

/** 是否有世界观名称 */
const named = computed(() => !!(props.model && props.model.name && String(props.model.name).trim()))
</script>

<style scoped>
.world-setting-form { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.wsf-head { display: flex; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
.wsf-head-text { display: flex; flex-direction: column; gap: 2px; min-width: 0; flex: 1 1 320px; }
.wsf-title { font-weight: 700; color: var(--text-primary); font-size: 0.94rem; }
.wsf-desc { font-size: 0.82rem; color: var(--text-regular); line-height: 1.5; }
.wsf-head-tags { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
/* 左置标签固定 132px 宽，避免标签文字换行把内容区挤歪 */
.wsf-tabs { min-height: 0; }
.wsf-tabs :deep(.el-tabs__header.is-left) { margin-right: 16px; }
.wsf-tabs :deep(.el-tabs__item) { height: 40px; font-size: 0.86rem; padding: 0 16px; }
.wsf-pane { display: flex; flex-direction: column; gap: 14px; padding: 2px 2px 6px; min-width: 0; }
.wsf-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 14px; }
.wsf-field { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.wsf-field :deep(.el-input__inner), .wsf-field :deep(.el-textarea__inner) { font-size: 0.90rem; line-height: 1.6; }
.wsf-label { font-size: 0.86rem; font-weight: 600; color: var(--text-regular, var(--text-primary)); }
.wsf-req { color: var(--el-color-danger, #f56c6c); }
.wsf-hint { font-size: 0.78rem; color: var(--text-regular); line-height: 1.55; }
.wsf-select { width: 100%; }
.wsf-opt { display: flex; align-items: baseline; gap: 8px; }
.wsf-opt-label { font-weight: 600; }
.wsf-opt-desc { font-size: 0.78rem; color: var(--text-secondary); }
.wsf-loc-head { display: flex; align-items: center; gap: 10px; }
</style>
