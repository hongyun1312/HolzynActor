/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * 角色档案（11 维）可视化编辑器支撑逻辑。
 * <p><b>编辑形态</b>：</p>
 * <ul>
 * <li><b>结构化表单</b>：每维按 {@link DIM_FORM_FIELDS} 渲染字段控件（文本框/文本域/数字/
 * 滑条 0-10·0-1/枚举下拉/标签列表/键值对），字段键为档案 JSON 内的 snake_case 路径
 * <li><b>JSON 兜底</b>：每维可切换到「原始 JSON」编辑（表单未覆盖的字段在这里改），
 * 该维一旦切过 JSON 并编辑，保存时以 JSON 为准（整维替换）；否则由表单值合并进
 * <b>原档案该维对象</b>（未在表单里的字段原样保留，不丢数据）；</li>
 * <li><b>保存</b>：拼回 {@code {schema_version, dims, _sources, ...}} 原档案顶层，走
 * {@code PUT /api/characters/{id}/card}——后端做 11 维结构化校验 + ③⑤⑥ 来源分派 +
 * 版本自增 + 版本历史快照（错误逐条返回，前端展示）。</li>
 * </ul>
 * <p>字段键说明：档案 JSON 是 snake_case（{@code PersonaJson} 序列化口径），本模块所有
 * {@code key} 均为此口径；值域与校验器 {@code PersonaSchemaValidator} 对齐（OCEAN/权重/抑制 0-1，
 * 体质/感官/技能 0-10，身高 0-300 等）。</p>
 */

/**
 * 表单字段定义：每维一组字段。
 * <p>字段类型：</p>
 * <ul>
 * <li>{@code text}——单行文本（el-input）；</li>
 * <li>{@code textarea}——多行文本（el-input type=textarea）；</li>
 * <li>{@code number}——数字（el-input-number，允许小数）；</li>
 * <li>{@code slider10}——0-10 整数滑条（体质/感官/技能）；</li>
 * <li>{@code slider1}——0-1 滑条（OCEAN/权重/抑制）；</li>
 * <li>{@code select}——枚举下拉（options 提供候选）；</li>
 * <li>{@code list}——字符串标签列表（el-select multiple filterable allow-create）；</li>
 * <li>{@code kv}——键值对动态行（键=圈层/情境码，值=文本或数值字符串）；</li>
 * <li>{@code kvNum}——键→数值 动态行（数值用 el-input-number）。</li>
 * </ul>
 */
export const DIM_FORM_FIELDS = {
  identity: [
    { key: 'aliases', label: '别名/绰号', type: 'list' },
    { key: 'identity_title', label: '身份头衔', type: 'text' },
    { key: 'social_class', label: '阶层', type: 'text' },
    { key: 'livelihood', label: '谋生方式', type: 'text' },
    { key: 'property.house', label: '房宅', type: 'text' },
    { key: 'property.land_mu', label: '水田（亩）', type: 'number' },
    { key: 'property.grain_shi', label: '存粮（石）', type: 'number' },
    { key: 'property.money_guan', label: '现钱（贯）', type: 'number' },
    { key: 'property.heirlooms', label: '传家物', type: 'list' },
    { key: 'property.notes', label: '财产备注', type: 'textarea' },
    { key: 'reputation', label: '声望（圈层→描述/0-1）', type: 'kv' },
    { key: 'status_tags', label: '身份标签', type: 'list' }
  ],
  appearance: [
    { key: 'height_cm', label: '身高（cm）', type: 'number' },
    { key: 'build', label: '体型', type: 'text' },
    { key: 'posture', label: '体态', type: 'text' },
    { key: 'face_features', label: '相貌特征', type: 'textarea' },
    { key: 'hair', label: '须发', type: 'text' },
    { key: 'attire_style', label: '衣着风格', type: 'text' },
    { key: 'voice_timbre', label: '嗓音', type: 'text' },
    { key: 'scent', label: '体味', type: 'text' },
    { key: 'strength', label: '力量', type: 'slider10' },
    { key: 'endurance', label: '耐力', type: 'slider10' },
    { key: 'agility', label: '敏捷', type: 'slider10' },
    { key: 'balance', label: '平衡', type: 'slider10' },
    { key: 'senses.vision', label: '视力', type: 'slider10' },
    { key: 'senses.hearing', label: '听力', type: 'slider10' },
    { key: 'senses.smell', label: '嗅觉', type: 'slider10' },
    { key: 'senses.taste', label: '味觉', type: 'slider10' },
    { key: 'senses.touch', label: '触觉', type: 'slider10' },
    { key: 'illnesses', label: '疾病', type: 'list' },
    { key: 'disabilities', label: '残障', type: 'list' },
    { key: 'constitution_weaknesses', label: '体质弱点', type: 'list' },
    { key: 'appearance_notes', label: '外观备注', type: 'textarea' }
  ],
  physio: [
    { key: 'chronotype', label: '作息型', type: 'select', options: [
      { value: 'early_bird', label: '早鸟（21-5 睡）' }, { value: 'intermediate', label: '中间（22-6 睡）' },
      { value: 'night_owl', label: '夜猫（24-8 睡）' }] },
    { key: 'sleep_need_hours', label: '睡眠需求（小时）', type: 'number' },
    { key: 'sleep_quality', label: '睡眠质量', type: 'text' },
    { key: 'meal_cycle_hours', label: '餐隔（小时）', type: 'number' },
    { key: 'meals_per_day', label: '餐数/日', type: 'number' },
    { key: 'metabolism', label: '代谢', type: 'select', options: [
      { value: 'slow', label: '慢' }, { value: 'normal', label: '正常' }, { value: 'fast', label: '快' }] },
    { key: 'pain_tolerance', label: '耐痛', type: 'slider10' },
    { key: 'thirst_tolerance', label: '耐渴', type: 'slider10' },
    { key: 'cold_tolerance', label: '耐寒', type: 'slider10' },
    { key: 'heat_tolerance', label: '耐热', type: 'slider10' },
    { key: 'fertility_state', label: '生育状态', type: 'text' },
    { key: 'libido', label: '性欲', type: 'text' },
    { key: 'alcohol_tolerance', label: '酒量', type: 'text' },
    { key: 'stimulants', label: '烟茶等嗜好', type: 'kv' },
    { key: 'excretion_regularity', label: '排泄规律', type: 'text' },
    { key: 'sweat_tendency', label: '出汗倾向', type: 'text' },
    { key: 'physio_notes', label: '生理备注', type: 'textarea' }
  ],
  personality: [
    { key: 'ocean.openness', label: '开放性', type: 'slider1' },
    { key: 'ocean.conscientiousness', label: '尽责性', type: 'slider1' },
    { key: 'ocean.extraversion', label: '外向性', type: 'slider1' },
    { key: 'ocean.agreeableness', label: '宜人性', type: 'slider1' },
    { key: 'ocean.neuroticism', label: '神经质', type: 'slider1' },
    { key: 'temperament_type', label: '气质型', type: 'text' },
    { key: 'attachment_style', label: '依恋类型', type: 'text' },
    { key: 'emotion_thresholds.anger', label: '怒阈值', type: 'slider1' },
    { key: 'emotion_thresholds.fear', label: '惧阈值', type: 'slider1' },
    { key: 'emotion_thresholds.joy', label: '喜阈值', type: 'slider1' },
    { key: 'emotion_thresholds.sadness', label: '悲阈值', type: 'slider1' },
    { key: 'emotion_recovery', label: '情绪恢复', type: 'text' },
    { key: 'impulse_control', label: '冲动控制', type: 'slider10' },
    { key: 'cognitive_style', label: '认知风格', type: 'text' },
    { key: 'risk_preference', label: '风险偏好', type: 'select', options: [
      { value: 'low', label: '低' }, { value: 'medium', label: '中' }, { value: 'high', label: '高' }] },
    { key: 'time_orientation', label: '时间取向', type: 'text' },
    { key: 'traits_summary', label: '特质摘要', type: 'textarea' }
  ],
  values: [
    { key: 'value_weights', label: '价值权重（键→0-1）', type: 'kvNum' },
    { key: 'core_fears', label: '核心恐惧', type: 'list' },
    { key: 'core_desires', label: '核心欲望', type: 'list' },
    { key: 'moral_bottom_lines', label: '道德底线（绝不做）', type: 'list' },
    { key: 'life_goals', label: '人生目标（长期）', type: 'list' },
    { key: 'current_goals', label: '当前目标', type: 'list' },
    { key: 'regrets', label: '人生遗憾', type: 'list' }
  ],
  ability: [
    { key: 'skills', label: '技能熟练度（键→0-10）', type: 'kvNum10' },
    { key: 'knowledge_domains', label: '知识领域', type: 'list' },
    { key: 'knowledge_blind_spots', label: '知识盲区', type: 'list' },
    { key: 'literacy', label: '识字与语言', type: 'text' },
    { key: 'talents', label: '天赋倾向', type: 'list' },
    { key: 'cognitive_ceiling', label: '认知上限（键→描述）', type: 'kv' },
    { key: 'secrets', label: '秘密', type: 'list' }
  ],
  voice: [
    { key: 'sentence_length', label: '句长', type: 'text' },
    { key: 'register', label: '用词雅俗', type: 'text' },
    { key: 'speech_rate', label: '语速', type: 'text' },
    { key: 'pitch', label: '音高', type: 'text' },
    { key: 'address_habits', label: '称呼习惯（对象→称呼）', type: 'kv' },
    { key: 'catchphrases', label: '口头禅', type: 'list' },
    { key: 'dialect', label: '方言腔调', type: 'text' },
    { key: 'taboo_topics', label: '忌讳话题', type: 'list' },
    { key: 'silence_tendency', label: '沉默倾向', type: 'text' },
    { key: 'rhetoric_preference', label: '修辞偏好', type: 'text' },
    { key: 'written_vs_spoken', label: '书面/口头差异', type: 'text' },
    { key: 'vocabulary_keys', label: '词汇特征', type: 'list' }
  ],
  habits: [
    { key: 'schedule', label: '日程（每时段安排）', type: 'list' },
    { key: 'sleep_habits', label: '睡眠习惯', type: 'list' },
    { key: 'quirks', label: '癖好', type: 'list' },
    { key: 'micro_movements', label: '小动作', type: 'list' },
    { key: 'food_preferences', label: '饮食偏好', type: 'text' },
    { key: 'addictions', label: '嗜好（烟茶酒…）', type: 'list' },
    { key: 'ritual_behaviors', label: '仪式性行为', type: 'list' }
  ],
  experience: [
    { key: 'origin', label: '出身', type: 'textarea' },
    { key: 'key_events', label: '关键经历（事件条目）', type: 'events' },
    { key: 'traumas', label: '创伤（事件+触发物）', type: 'traumas' },
    { key: 'honors', label: '荣誉', type: 'list' },
    { key: 'unresolved_knots', label: '未解心结', type: 'list' }
  ],
  development: [
    { key: 'stage', label: '当前阶段码', type: 'text' },
    { key: 'stage_entered_game_second', label: '阶段进入时刻（游戏秒）', type: 'number' },
    { key: 'genome', label: '基因位点（键→值）', type: 'kv' },
    { key: 'epigenetic_marks', label: '表观修饰', type: 'list' },
    { key: 'lineage', label: '谱系（键→描述）', type: 'kv' },
    { key: 'capacity_curve.peak_age', label: '机能峰值年龄', type: 'number' },
    { key: 'capacity_curve.current_ratio', label: '当前机能比 0-1', type: 'slider1' },
    { key: 'death_risk.baseline_annual', label: '基线年死亡率 0-1', type: 'slider1' }
  ],
  mask: [
    { key: 'context_suppression', label: '情境抑制强度（键→0-1）', type: 'kvNum' },
    { key: 'allowed_expressions', label: '允许表达（情境→列表）', type: 'kvList' },
    { key: 'forbidden_expressions', label: '禁止表达（情境→列表）', type: 'kvList' },
    { key: 'breaches', label: '面具破绽（触发物→抑制增量）', type: 'breaches' }
  ]
}

/** 枚举/特殊类型字段的候选值提示（select 用 options 内联，这里仅文档性汇总） */
export const PERSONA_ENUM_OPTIONS = {
  chronotype: ['early_bird', 'intermediate', 'night_owl'],
  metabolism: ['slow', 'normal', 'fast'],
  risk_preference: ['low', 'medium', 'high']
}

/** 面具情境码（键值对字段的键提示） */
export const MASK_CONTEXTS = [
  'before_authority', 'before_partner', 'before_stranger', 'before_peer',
  'before_child', 'before_rival', 'public_place', 'private_place'
]

/** 声望圈层建议键 */
export const REPUTATION_KEYS = ['village', 'neighboring', 'county', 'prefecture']

/**
 * 按点路径取值（"a.b.c" → obj.a.b.c；null 安全）。
 *
 * @param {object} obj 目标对象
 *
 * @param {string} path 点路径
 *
 * @returns {*} 值；路径缺失返回 undefined
 */
export function getByPath(obj, path) {
  if (!obj || !path) return undefined
  let cur = obj
  for (const seg of path.split('.')) {
    if (cur === null || cur === undefined) return undefined
    cur = cur[seg]
  }
  return cur
}

/**
 * 按点路径设值（"a.b.c" → obj.a.b.c = value；中间对象自动创建）。
 *
 * @param {object} obj 目标对象（原地修改）
 *
 * @param {string} path 点路径
 *
 * @param {*} value 新值（null/undefined 时删除该键）
 */
export function setByPath(obj, path, value) {
  if (!obj || !path) return
  const segs = path.split('.')
  let cur = obj
  for (let i = 0; i < segs.length - 1; i++) {
    if (cur[segs[i]] === null || cur[segs[i]] === undefined || typeof cur[segs[i]] !== 'object') {
      cur[segs[i]] = {}
    }
    cur = cur[segs[i]]
  }
  const last = segs[segs.length - 1]
  if (value === null || value === undefined || value === '') {
    delete cur[last]
  } else {
    cur[last] = value
  }
}

/**
 * 从档案 dims 提取某维的表单值映射（字段路径 → 值）。
 *
 * @param {object} dimObj 该维对象（档案 JSON 内）
 *
 * @param {Array} fields 该维字段定义
 *
 * @returns {object} 字段路径 → 值（缺省空串/空数组，保证表单控件受控）
 */
export function formValuesOf(dimObj, fields) {
  const out = {}
  for (const f of fields) {
    const v = getByPath(dimObj, f.key)
    if (f.type === 'list') out[f.key] = Array.isArray(v) ? [...v] : []
    else if (f.type === 'kv' || f.type === 'kvNum' || f.type === 'kvNum10' || f.type === 'kvList') {
      out[f.key] = v && typeof v === 'object' && !Array.isArray(v) ? { ...v } : {}
    } else if (f.type === 'events') {
      out[f.key] = Array.isArray(v) ? v.map(e => ({ ...e })) : []
    } else if (f.type === 'traumas') {
      out[f.key] = Array.isArray(v) ? v.map(e => ({ ...e })) : []
    } else if (f.type === 'breaches') {
      out[f.key] = Array.isArray(v) ? v.map(e => ({ ...e })) : []
    } else if (v === undefined || v === null) {
      out[f.key] = f.type === 'number' || f.type === 'slider10' || f.type === 'slider1' ? undefined : ''
    } else {
      out[f.key] = v
    }
  }
  return out
}

/**
 * 把表单值合并进该维对象（只动表单覆盖的路径，未知字段原样保留）。
 *
 * @param {object} dimObj 原维对象（可空）
 *
 * @param {object} formValues 字段路径 → 值
 *
 * @returns {object} 合并后的维对象（恒为对象）
 */
export function mergeFormIntoDim(dimObj, formValues) {
  const out = dimObj && typeof dimObj === 'object' && !Array.isArray(dimObj) ? { ...dimObj } : {}
  for (const [path, value] of Object.entries(formValues)) {
    if (value === undefined || value === null) {
      setByPath(out, path, null) // 删除该路径（原值由 setByPath 删除键）
    } else {
      setByPath(out, path, value)
    }
  }
  return out
}

/**
 * 序列化表单值（对象字段需要复制，避免与表单状态共享引用）。
 *
 * @param {object} dimObj 合并后的维对象
 *
 * @returns {object} 深拷贝（简单 JSON 结构）
 */
export function cloneDim(dimObj) {
  try {
    return JSON.parse(JSON.stringify(dimObj))
  } catch (_) {
    return { ...dimObj }
  }
}

/**
 * 该维"是否有实际内容"（空对象/空数组/空白字符串不算；与后端校验器口径一致）。
 *
 * @param {object} dimObj 维对象
 */
export function hasDimContent(dimObj) {
  if (dimObj === null || dimObj === undefined) return false
  if (typeof dimObj === 'string') return dimObj.trim() !== ''
  if (Array.isArray(dimObj)) return dimObj.length > 0
  if (typeof dimObj === 'object') {
    return Object.values(dimObj).some(x => {
      if (x === null || x === undefined) return false
      if (typeof x === 'string') return x.trim() !== ''
      if (Array.isArray(x)) return x.length > 0
      if (typeof x === 'object') return Object.keys(x).length > 0
      return true
    })
  }
  return true
}

/**
 * 把编辑器状态拼回完整档案 JSON（保存载荷）。
 * <p>规则：JSON 兜底脏的维（{@code dimJsonDirty[key]}）→ 整维用解析后的 JSON；
 * 否则用 {@code mergeFormIntoDim}（表单值合并进原维，未覆盖字段保留）。</p>
 * <p>键值对字段归一化：{@code kv}/{@code kvNum}/{@code kvNum10}/{@code kvList} 在表单里以
 * {@code [{k, v}]} 行数组编辑，保存时按字段类型还原为对象形态（{@code {k: v}} /
 * {@code {k: [v…]}}），空键行剔除；{@code events}/{@code traumas}/{@code breaches} 本就是
 * 对象数组，只剔除全空行。</p>
 *
 * @param {object} originalPersona 原档案对象（顶层含 dims/_sources 等）
 *
 * @param {object} formValues 维键 → 字段路径→值
 *
 * @param {object} dimJsonText 维键 → 原始 JSON 文本（仅脏维有效）
 *
 * @param {object} dimJsonDirty 维键 → 是否以 JSON 为准
 *
 * @param {object} fieldDefs 维键 → 字段定义数组（归一化用；可空=不归一化）
 *
 * @returns {{json: string, errors: string[]}} 序列化后的 JSON 文本 + 解析错误列表
 */
export function buildPersonaJson(originalPersona, formValues, dimJsonText, dimJsonDirty, fieldDefs) {
  const persona = originalPersona && typeof originalPersona === 'object' ? JSON.parse(JSON.stringify(originalPersona)) : {}
  if (!persona.dims || typeof persona.dims !== 'object') persona.dims = {}
  if (!persona.schema_version) persona.schema_version = 2
  const errors = []
  for (const key of Object.keys(formValues)) {
    if (dimJsonDirty[key]) {
      const text = String(dimJsonText[key] || '').trim()
      if (!text) {
        // 清空该维（显式置空）
        persona.dims[key] = undefined
        delete persona.dims[key]
        continue
      }
      try {
        const parsed = JSON.parse(text)
        if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
          errors.push(`${key}：JSON 必须是对象`)
          continue
        }
        persona.dims[key] = parsed
      } catch (e) {
        errors.push(`${key}：JSON 无法解析（${e.message}）`)
      }
    } else {
      const fields = (fieldDefs && fieldDefs[key]) || []
      persona.dims[key] = mergeFormIntoDim(persona.dims[key], normalizeFormValues(formValues[key], fields))
    }
  }
  return { json: JSON.stringify(persona, null, 2), errors }
}

/**
 * 表单值归一化（kv 行数组 → 对象形态；空行剔除）。
 *
 * @param {object} values 字段路径 → 值
 *
 * @param {Array} fields 该维字段定义
 *
 * @returns {object} 归一化后的值
 */
export function normalizeFormValues(values, fields) {
  const out = { ...values }
  for (const f of fields) {
    const v = out[f.key]
    if (!Array.isArray(v)) continue
    if (f.type === 'kv' || f.type === 'kvNum' || f.type === 'kvNum10' || f.type === 'kvList') {
      const obj = {}
      for (const row of v) {
        if (!row || typeof row !== 'object') continue
        const k = String(row.k ?? '').trim()
        if (!k) continue
        if (f.type === 'kvList') {
          if (Array.isArray(row.v) && row.v.length > 0) obj[k] = [...row.v]
        } else if (row.v !== undefined && row.v !== null && row.v !== '') {
          obj[k] = row.v
        }
      }
      out[f.key] = obj
    } else if (f.type === 'events') {
      out[f.key] = v.filter(r => r && String(r.what ?? '').trim())
    } else if (f.type === 'traumas') {
      out[f.key] = v.filter(r => r && String(r.event ?? '').trim())
    } else if (f.type === 'breaches') {
      out[f.key] = v.filter(r => r && String(r.trigger ?? '').trim())
    }
  }
  return out
}
