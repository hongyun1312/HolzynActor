/**
 * 角色档案（11 维）只读展示支撑逻辑（O6 轨道 G1a「角色档案统一重构」）。
 *
 * <p><b>职责</b>：把后端 {@code CharacterCardVO} 的两个字段转成"可直接渲染"的 11 维分区结构：
 * <ul>
 *   <li>{@code personaRenderText}：服务端 {@code PersonaService.renderFull} 渲染好的全量 11 维中文文本，
 *       形如 {@code 【身份与社会】\n姓名：…\n…}——本模块只做<b>按维切分</b>（不做任何字段翻译/排版），
 *       渲染规则始终只有后端一份实现（契约 §6：避免前端重复实现渲染逻辑而与契约漂移）；</li>
 *   <li>{@code personaJson}：11 维档案 JSON 原文（{@code schema_version:2} + {@code dims{...}} + {@code _sources}），
 *       仅用于"原始 JSON"折叠面板与降级展示。</li>
 * </ul>
 *
 * <p><b>空维口径</b>：后端 {@code renderDims} 会整段跳过空维，因此切分结果里"该维无行"即
 * "该维暂无内容"——前端显示占位文案而不是空白（契约 §1：渲染时无空维，展示时区分空维）。</p>
 *
 * <p><b>双前端复用</b>：本文件是主前端 {@code frontend/src/features/character/personaArchive.js} 的
 * <b>同源镜像</b>（两个 Vite 工程各自独立构建；组件镜像见 {@code src/components/PersonaArchivePanel.vue}）。
 * 修改时请两侧同步。</p>
 *
 * <p>所属模块：utils（调试前端纯逻辑工具，无 Vue 依赖，可被脚本直接引用）</p>
 */

/**
 * 11 维定义（顺序＝①→⑪；键名与后端 {@code PersonaSchema.Dims} 一致，
 * 标题与后端 {@code PersonaSchema.DIM_TITLES} 逐字一致——切分依赖标题精确匹配）。
 *
 * <p>{@code shards} / {@code kind} 取自《角色档案 Schema V2 契约与分维来源》§2 的「维 × 分片 × 来源」总表，
 * 仅作展示用途（让人看出"这一维进哪些分片、权威在谁那里"）。</p>
 */
export const PERSONA_DIMS = [
  { key: 'identity', no: '①', title: '身份与社会', shards: 'S1 S11', kind: 'MIXED' },
  { key: 'appearance', no: '②', title: '外貌与体质', shards: 'S10', kind: 'PERSONA' },
  { key: 'physio', no: '③', title: '生理基线', shards: 'S4', kind: 'MODULE' },
  { key: 'personality', no: '④', title: '气质与人格', shards: 'S2', kind: 'PERSONA' },
  { key: 'values', no: '⑤', title: '价值观与动机', shards: 'S2', kind: 'MIXED' },
  { key: 'ability', no: '⑥', title: '能力与知识', shards: 'S5', kind: 'MIXED' },
  { key: 'voice', no: '⑦', title: '语言与表达', shards: 'S3', kind: 'PERSONA' },
  { key: 'habits', no: '⑧', title: '习惯与日常', shards: 'S6', kind: 'MIXED' },
  { key: 'experience', no: '⑨', title: '经历与创伤', shards: 'S7', kind: 'MIXED' },
  { key: 'development', no: '⑩', title: '发育·遗传·谱系', shards: 'S9', kind: 'MIXED' },
  { key: 'mask', no: '⑪', title: '社会面具', shards: 'S8', kind: 'PERSONA' }
]

/** 维标题 → 维定义（切分索引） */
const DIM_BY_TITLE = new Map(PERSONA_DIMS.map(d => [d.title, d]))

/** 空维占位文案（不用空白表达"没有内容"） */
export const DIM_EMPTY_TEXT = '（该维暂无内容）'

/** 归档来源（{@code persona_json.generated_by} → 中文；兼容历史 source 取值） */
const SOURCE_LABELS = {
  ai_bootstrap: 'AI 建档',
  ai: 'AI 生成',
  manual: '人工编辑',
  edited: '人工编辑',
  imported: '导入',
  dev: '开发基准样例',
  migrated: '迁移导入'
}

/** 生成场景（{@code persona_json.generated_scene} → 中文） */
const SCENE_LABELS = {
  character_card_gen: '角色档案生成',
  persona_bootstrap_fill_a: 'AI 建档·第一段',
  persona_bootstrap_fill_b: 'AI 建档·第二段',
  manual: '人工编辑',
  baseline_fixture: '基准夹具'
}

/**
 * 档案来源中文名。
 *
 * @param {string} source 来源标记（VO.source / persona_json.generated_by）
 * @returns {string} 中文名；未知取值原样返回，空返回 '—'
 */
export function sourceLabel(source) {
  if (!source) return '—'
  return SOURCE_LABELS[source] || source
}

/**
 * 生成场景中文名（VO 未直接给出，从 personaJson 内读取）。
 *
 * @param {string} scene 场景码
 * @returns {string} 中文名；未知/空返回 ''
 */
export function sceneLabel(scene) {
  if (!scene) return ''
  return SCENE_LABELS[scene] || scene
}

/**
 * 宽松解析档案 JSON 文本。
 *
 * <p><b>双重编码防御</b>：历史后端/旧数据可能出现 JSON 列读回多包一层字符串字面量的情况，
 * 首次 {@code JSON.parse} 得到字符串而非对象时再解析一次（沿用既有页面口径）。</p>
 *
 * @param {string|object} raw 档案 JSON 文本或对象
 * @returns {object} 档案对象；不可解析返回 {}
 */
export function parsePersonaJson(raw) {
  let v = raw
  if (typeof v === 'string') {
    if (!v.trim()) return {}
    try { v = JSON.parse(v) } catch (_) { return {} }
  }
  if (typeof v === 'string') {
    try { v = JSON.parse(v) } catch (_) { return {} }
  }
  return v && typeof v === 'object' ? v : {}
}

/**
 * 判断某维在档案 JSON 里是否有内容（降级展示用；空对象/空数组/空白字符串都不算内容）。
 *
 * @param {object} dims 档案 dims 对象
 * @param {string} key  维键
 * @returns {boolean} true=有内容
 */
export function hasDimContent(dims, key) {
  const v = dims ? dims[key] : null
  if (v === null || v === undefined) return false
  if (typeof v === 'string') return v.trim() !== ''
  if (Array.isArray(v)) return v.length > 0
  if (typeof v === 'object') {
    return Object.values(v).some(x => {
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
 * 按维切分服务端渲染文本（{@code personaRenderText}）。
 *
 * <p>只认<b>整行且标题精确等于 11 维标题之一</b>的 {@code 【维名】} 行作为分区标记——
 * 与后端 {@code PersonaRenderer} 的段标题写法一致（{@code TITLE_L + DIM_TITLES + TITLE_R}），
 * 且不会误把分片标题（如【身份锚定】【语言指纹】【人格与价值观】）当作维标题。</p>
 *
 * <p><b>只接受 {@code renderFull} 的输出</b>：11 个分片标题里有 5 个与维标题同名
 * （外貌与体质／能力与知识／习惯与日常／经历与创伤／社会面具），传 {@code renderShards} 的
 * 分片文本会在这几维上发生"分片内容并进同名维"——{@code CharacterCardVO.personaRenderText}
 * 恒为全量渲染文本，故该边界不会在展示链路上出现。</p>
 *
 * @param {string} renderText 服务端渲染的全量 11 维文本（可空）
 * @returns {Record<string, string[]>} 维键 → 该维文本行（11 个键恒存在，无内容为空数组）
 */
export function splitPersonaRenderText(renderText) {
  const out = {}
  for (const d of PERSONA_DIMS) out[d.key] = []
  if (typeof renderText !== 'string' || !renderText.trim()) return out
  let current = null
  for (const rawLine of renderText.split(/\r?\n/)) {
    const line = rawLine.trim()
    if (!line) continue
    const m = /^【(.+?)】$/.exec(line)
    if (m && DIM_BY_TITLE.has(m[1])) {
      current = DIM_BY_TITLE.get(m[1]).key
      continue
    }
    // 标记行之前的内容（正常渲染文本不会出现）与未知段标题一律忽略，不污染任何一维
    if (current) out[current].push(line)
  }
  return out
}

/**
 * 把一行渲染文本拆成「标签 + 值」（首个全角冒号处切分，供展示层做对齐排版）。
 *
 * @param {string} line 行文本（如 {@code 姓名：沈砚秋｜别名：沈老族长}）
 * @returns {{label: string, value: string}} 无标签时 label 为空串
 */
export function splitDimLine(line) {
  const text = String(line ?? '')
  const i = text.indexOf('：')
  if (i <= 0) return { label: '', value: text }
  return { label: text.slice(0, i), value: text.slice(i + 1) }
}

/**
 * 组装 11 维展示结构（组件唯一入口）。
 *
 * <p>每维返回：维定义 + 渲染文本行（优先） + 是否有内容 + 原始 JSON 兜底是否存在。</p>
 *
 * @param {object} card CharacterCardVO（可空）
 * @returns {{dims: Array<object>, rawJson: string, hasRenderText: boolean, meta: object}}
 *          dims 恒为 11 项（顺序 ①→⑪）
 */
export function buildArchive(card) {
  const persona = parsePersonaJson(card?.personaJson)
  const dimsJson = persona?.dims && typeof persona.dims === 'object' ? persona.dims : {}
  const renderText = typeof card?.personaRenderText === 'string' ? card.personaRenderText : ''
  const sections = splitPersonaRenderText(renderText)
  const dims = PERSONA_DIMS.map(def => {
    const lines = sections[def.key] || []
    return {
      ...def,
      lines,
      hasContent: lines.length > 0,
      // 降级展示：服务端渲染文本缺失时，该维若在档案 JSON 里有内容，则给出该维原始 JSON 文本
      rawDimJson: hasDimContent(dimsJson, def.key) ? safeStringify(dimsJson[def.key]) : ''
    }
  })
  return {
    dims,
    rawJson: card?.personaJson ? prettyJson(card.personaJson) : '',
    hasRenderText: sections != null && renderText.trim() !== '',
    meta: {
      // 角色姓名：VO.characterName 为主（G1a 新增），列表页已有姓名时由调用方兜底
      characterName: card?.characterName || '',
      version: card?.version ?? persona?.persona_version ?? null,
      // VO.source 即 persona_json.generated_by；JSON 内直接读一次作为兜底
      source: card?.source || persona?.generated_by || '',
      scene: persona?.generated_scene || '',
      schemaVersion: persona?.schema_version ?? null,
      updatedAt: card?.updatedAt || '',
      createdAt: card?.createdAt || ''
    }
  }
}

/**
 * JSON 文本美化（不可解析时原样返回，绝不抛异常阻断展示）。
 *
 * @param {string|object} raw JSON 文本或对象
 * @returns {string} 美化后的文本
 */
export function prettyJson(raw) {
  if (raw === null || raw === undefined || raw === '') return ''
  if (typeof raw === 'object') return safeStringify(raw)
  try { return JSON.stringify(JSON.parse(raw), null, 2) } catch (_) { return String(raw) }
}

/**
 * 对象安全序列化（循环引用/序列化异常返回占位文案）。
 *
 * @param {*} v 任意值
 * @returns {string} JSON 文本
 */
export function safeStringify(v) {
  try { return JSON.stringify(v, null, 2) } catch (_) { return String(v) }
}

/**
 * 时间文本格式化（后端 LocalDateTime → 'YYYY-MM-DD HH:mm'）。
 *
 * @param {string} t 时间文本（ISO 形态）
 * @returns {string} 格式化文本；空返回 '—'
 */
export function fmtDateTime(t) {
  if (!t) return '—'
  return String(t).slice(0, 16).replace('T', ' ')
}