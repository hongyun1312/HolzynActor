<template>
  <!-- 渲染 HarmonyOS 图标：fill=currentColor 随文字色/主色；name 传 Element Plus 图标名或 HarmonyOS 文件名 -->
  <svg
    v-if="innerSvg"
    class="h-icon"
    :width="size"
    :height="size"
    :viewBox="viewBox"
    fill="currentColor"
    aria-hidden="true"
    v-html="innerSvg"
  />
</template>

<script>
// 模块级唯一 id 计数器（必须放在普通 <script> 块 = 模块作用域，只执行一次）。
// ⚠ 关键：若放在 <script setup> 里，该代码会在【每个组件实例】都执行一次，
// 每个实例的计数器都从 0 开始 → 所有 HIcon 实例都生成后缀 h1 →
// 文档中所有 <use href="#path-1-h1"> 都解析到第一个图标的 path-1 →
// 全页图标全部串扰成「文档中第一个图标的形状」（画廊=搜索、项目空间=抽屉箭头）。
// 2026-08-18 修复：计数器提升到模块级，跨实例递增（h1/h2/h3…）保证唯一。
let uidSeed = 0
</script>

<script setup>
/**
 * HIcon 全局图标组件（2026-08-18 接入华为鸿蒙图标集）。
 * <p>职责：替代 Element Plus 图标——根据传入 name（Element Plus 图标名，如 Plus/Delete/Setting，
 * 或 HarmonyOS 文件名 ic_public_xxx）从 harmonyIcons 注册表取 SVG 文本，渲染为 24×24 的
 * currentColor 图标（随文字颜色/主色自动换色，深浅主题自适应）。</p>
 * <p>设计：HarmonyOS SVG 内部使用 `<defs><path/><mask><use/></mask><use fill="currentColor"/></defs>` 结构；
 * 为避免多个图标实例的同名 id（path-1/mask-2）互相串扰，渲染时按实例分配唯一后缀。</p>
 * <p>用法：保持外层 `<el-icon>` 包裹可复用其尺寸/居中/loading 旋转；也可独立使用（size 默认 1em）。</p>
 * <p>所属模块：shared/components（通用组件）</p>
 */
import { computed, ref } from 'vue'
import { ELEMENT_TO_HARMONY, HARMONY_ICONS } from './harmonyIcons'

const props = defineProps({
  /** 图标名：Element Plus 图标名（如 Plus）或 HarmonyOS 文件名（如 ic_public_add） */
  name: { type: String, default: '' },
  /** 尺寸：默认 1em（跟随字体大小） */
  size: { type: [String, Number], default: '1em' }
})

// 实例唯一后缀计数器在顶部普通 <script> 块（模块级，跨实例共享）；
// ⚠ 此处【不得】再声明 uidSeed——setup 内声明会遮蔽模块级变量，
// 导致每个实例都从 0 计数（全部后缀 h1）→ 图标互相串扰成同一形状。
// 视口：默认 24×24；从原始 SVG 提取（个别图标非标准，如 spinner 为 12×24，避免拉伸变形）
const viewBox = ref('0 0 24 24')

const innerSvg = computed(() => {
  const file = ELEMENT_TO_HARMONY[props.name] || props.name
  const raw = HARMONY_ICONS[file] || ''
  if (!raw) return ''
  // 提取 <svg ...> 与 </svg> 之间的内容（defs/mask/use 等），同时捕获开始标签属性以便解析 viewBox
  const m = raw.match(/<svg([^>]*)>([\s\S]*)<\/svg>/)
  if (!m) return ''
  // 解析原始 viewBox：不同图标视口可能不一致（spinner 12×24），跟随原始值保证纵横比正确
  const vb = m[1].match(/viewBox="([^"]+)"/)
  viewBox.value = vb ? vb[1] : '0 0 24 24'
  const uid = `h${++uidSeed}`
  return m[2]
    // 给所有 id / 引用加实例后缀，避免多图标共存时引用错乱。
    // ⚠ 关键：xlink:href 与 href 必须在同一条正则里处理（(xlink:href|href)）。
    // 若分两条写，第二条 /href="#..."/ 会命中已改写的 xlink:href="#...-h1" 中的 href 子串，
    // 导致「双重后缀」（#path-1-h1-h1）→ 引用指向不存在的 id → 图标整体空白（2026-08-18 修复）。
    .replace(/id="([^"]+)"/g, (_, id) => `id="${id}-${uid}"`)
    .replace(/(xlink:href|href)="#([^"]+)"/g, (_, attr, id) => `${attr}="#${id}-${uid}"`)
})
</script>

<style scoped>
.h-icon {
  display: inline-block;
  flex-shrink: 0;
  fill: currentColor;
  vertical-align: -0.15em;
}
</style>
