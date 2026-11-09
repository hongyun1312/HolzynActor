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
  <!--
    SideNav：侧栏「表现层」组件（不含任何路由/数据依赖，纯展示 + 事件）。
    设计意图：把「悬浮岛侧栏」的视觉与交互收敛成一份实现，
    供 DomainSidebar（项目六域导航）与 DevSidebar（开发者控制台）复用，
    彻底消除上一版「三套侧栏各写一遍、样式逐渐分叉」的问题。

    结构：nav（不滚动）→ side-scroll（导航行，纵向滚动）+ side-foot（底部状态，固定）。
    旧结构把底部插槽放在滚动区里，浮生 20 行导航一拉长，项目状态卡就被滚出视口。
  -->
  <nav class="side-nav" :class="{ 'is-icon-only': collapsed }">
    <!-- 滚动区：只装导航行 -->
    <div class="side-scroll">
      <!-- 置顶固定项（不属于任何域，永远在最前） -->
      <div v-if="pinned && pinned.length" class="side-pinned" data-tour="pinned-nav">
        <a
          v-for="item in pinned"
          :key="item.key"
          class="side-item"
          :class="{ active: item.key === activeKey }"
          :aria-current="item.key === activeKey ? 'page' : null"
          :title="collapsed ? `${item.name}${item.desc ? ' · ' + item.desc : ''}` : ''"
          href="#"
          @click.prevent="$emit('navigate', item)"
          @contextmenu.prevent="$emit('context', $event, item)"
        >
          <el-icon class="side-icon"><HIcon :name="item.icon" /></el-icon>
          <span v-if="!collapsed" class="side-text">
            <span class="side-name">{{ item.name }}</span>
            <!-- 副标签用于消歧同名字面（如「对话 → 世界运行中」/「对谈 → 世界静止」），
                 弱化小字，不改变主名，因此不破坏既有的位置肌肉记忆。 -->
            <em v-if="item.sub" class="side-sub">{{ item.sub }}</em>
          </span>
        </a>
      </div>

      <!-- 分组（域）导航：点击折叠/展开；当前页所在分组默认展开。
           单条目分组（如「设置」）扁平化为一个可直接点击的直达项——
           避免「分组头 + 组内同名子项」渲染出两个「设置」的观感（浮生/对谈两界面均适用）。 -->
      <div
        v-for="section in sections"
        :key="section.key"
        class="side-section"
        :class="{ open: isOpen(section.key) }"
      >
        <template v-if="section.items.length === 1">
          <a
            class="side-item"
            :class="{ active: section.items[0].key === activeKey }"
            :aria-current="section.items[0].key === activeKey ? 'page' : null"
            :title="collapsed
              ? `${section.items[0].name}${section.items[0].desc ? ' · ' + section.items[0].desc : ''}`
              : (section.items[0].desc || section.items[0].name)"
            href="#"
            @click.prevent="$emit('navigate', section.items[0])"
            @contextmenu.prevent="$emit('context', $event, section.items[0])"
          >
            <el-icon class="side-icon"><HIcon :name="section.items[0].icon || section.icon" /></el-icon>
            <span v-if="!collapsed" class="side-text">
              <span class="side-name">{{ section.items[0].name }}</span>
              <em v-if="section.items[0].sub" class="side-sub">{{ section.items[0].sub }}</em>
            </span>
          </a>
        </template>

        <template v-else>
          <button
            type="button"
            class="side-head"
            :title="collapsed ? section.name : ''"
            :aria-expanded="isOpen(section.key) ? 'true' : 'false'"
            @click="toggle(section.key)"
          >
            <el-icon class="side-icon"><HIcon :name="section.icon" /></el-icon>
            <span v-if="!collapsed" class="side-text">
              <span class="side-name">{{ section.name }}</span>
            </span>
            <!-- 折叠箭头用 CSS 绘制：ic_public_drawer 在鸿蒙图标集中是「抽屉」而非雪佛龙，
                 直接映射 ArrowDown 会渲染成三条横线（≡），语义与观感都不对 -->
            <span v-if="!collapsed" class="side-caret" aria-hidden="true"></span>
          </button>

          <!-- 子项：图标栏形态下也保留（悬浮/展开时可见），保证收起后仍可直达 -->
          <div v-show="isOpen(section.key) || collapsed" class="side-children">
            <a
              v-for="child in section.items"
              :key="child.key"
              class="side-item child"
              :class="{ active: child.key === activeKey }"
              :aria-current="child.key === activeKey ? 'page' : null"
              :title="collapsed ? `${child.name}${child.desc ? ' · ' + child.desc : ''}` : (child.desc || child.name)"
              href="#"
              @click.prevent="$emit('navigate', child)"
              @contextmenu.prevent="$emit('context', $event, child)"
            >
              <el-icon class="side-icon"><HIcon :name="child.icon" /></el-icon>
              <span v-if="!collapsed" class="side-text">
                <span class="side-name">{{ child.name }}</span>
                <em v-if="child.sub" class="side-sub">{{ child.sub }}</em>
              </span>
            </a>
          </div>
        </template>
      </div>
    </div>

    <!-- 底部插槽：项目状态小卡 / 版本号等由宿主注入（固定在侧栏底部，不随导航滚动）。
         没有传插槽时不渲染，避免只剩一条空的分隔线。 -->
    <div v-if="!collapsed && $slots.foot" class="side-foot">
      <slot name="foot"></slot>
    </div>
  </nav>
</template>

<script setup>
/**
 * 侧栏表现层组件。
 *
 * <p>职责：渲染「置顶项 + 若干可折叠分组」的导航列表，处理展开状态与当前项高亮，
 * 并通过 `navigate` 事件把点击意图交回宿主（宿主负责 router.push / 关闭覆盖式侧栏）。</p>
 *
 * <p>为什么做成表现层：项目空间侧栏（六域）与开发者控制台侧栏（九域）在功能上都是
 * 「分组导航」，此前各自写一遍导致样式分叉。统一成本组件后，任何视觉调整只改一处；
 * 所有尺度取 `--side-*` 令牌（见 variables.css），三档密度下自动跟随。</p>
 *
 * <p>折叠（icon-only）形态：由宿主通过 CSS 变量控制侧栏宽度；本组件据 `collapsed` 隐藏文字，
 * 并把名称降级为原生 title 提示（避免引入额外浮层依赖）。</p>
 */
import { computed, ref, watch } from 'vue'

const props = defineProps({
  /** 置顶固定项：[{ key, name, icon, desc }] */
  pinned: { type: Array, default: () => [] },
  /** 分组：[{ key, name, icon, items: [{ key, name, icon, desc }] }] */
  sections: { type: Array, default: () => [] },
  /** 当前激活项 key（由宿主按路由推导） */
  activeKey: { type: String, default: '' },
  /** 是否图标窄栏形态 */
  collapsed: { type: Boolean, default: false }
})
// context：右键导航项（$event + item），由宿主决定弹什么菜单（本组件不预设业务动作）
defineEmits(['navigate', 'context'])

/** 分组展开状态（默认全部展开；进入页面后自动展开当前项所在分组） */
const openGroups = ref({})

/** 全部展开为初始态，保证首次进入即可看到所有功能（旧版默认收起导致发现性差） */
watch(() => props.sections, (list) => {
  const next = { ...openGroups.value }
  for (const s of list || []) if (next[s.key] === undefined) next[s.key] = true
  openGroups.value = next
}, { immediate: true })

/** 当前激活项所在分组必须展开（例如从命令面板跳到某页后，侧栏要对得上） */
watch(() => props.activeKey, (key) => {
  if (!key) return
  for (const s of props.sections || []) {
    if ((s.items || []).some(i => i.key === key)) openGroups.value = { ...openGroups.value, [s.key]: true }
  }
}, { immediate: true })

const isOpen = (key) => !!openGroups.value[key]
function toggle(key) { openGroups.value = { ...openGroups.value, [key]: !openGroups.value[key] } }

/** 用于模板调试（不参与渲染逻辑） */
const sectionCount = computed(() => (props.sections || []).length)
</script>

<style scoped>
/* 侧栏本体：占满 aside 高度，「导航可滚动 + 底部状态固定」两段式。
   左右留白只在父级给一次（--side-inset），两侧必须相等；行内不能再叠第二套左右留白。 */
.side-nav {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 0 var(--side-inset, 8px) var(--side-inset, 8px);
  overflow: hidden;
}

/* 滚动区：只装导航行（含置顶项与各分组），底部插槽在它之外。
   mask 只淡出底部 12px、并在内容尾部留 14px 内边距 —— 滚到底时最后一行落在淡出区之外，
   所以「能看见的内容永远是完整的」，只在滚动中途把被切断的半行柔化掉。 */
.side-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  display: flex;
  flex-direction: column;
  padding: var(--space-1, 4px) 0 24px;
  -webkit-mask-image: linear-gradient(to bottom, #000 calc(100% - 20px), transparent 100%);
  mask-image: linear-gradient(to bottom, #000 calc(100% - 20px), transparent 100%);
  /* 隐藏原生滚动条（仍可用滚轮/触控板/键盘滚动）：
     ① 原生条会吃掉 6px 内容宽，使行的右留白变成 2px、与左侧 8px 不对称
        ——「两侧留白一致」在 720 高度（默认窗口、必然出现滚动条）下直接不成立；
     ② 内容长短切换（如浮生 20 行 ↔ 对谈 3 行）时，滚动条出现/消失会让整列横跳 6px。
     溢出提示由上面的底部渐隐承担，观感上也比一条灰条干净。 */
  scrollbar-width: none;
}
.side-scroll::-webkit-scrollbar { width: 0; height: 0; display: none; }

/* ===== 行（置顶项 / 子项 / 单条目分组共用同一份规格）=====
   行高、字号、图标、图文间距、圆角全部取 --side-* 令牌（随三档密度变化）：
   同层同规格，任何一行都不会比另一行更高或更挤。 */
.side-item {
  display: flex;
  align-items: center;
  gap: var(--side-gap, 10px);
  flex: none;
  height: var(--side-row-h, 40px);
  padding: 0 var(--side-pad-x, 10px);
  border-radius: var(--side-radius, 8px);
  color: var(--text-secondary);
  font-size: var(--side-font, 14px);
  line-height: 1.25;
  cursor: pointer;
  transition: background .15s var(--dsw-ease), color .15s var(--dsw-ease);
  white-space: nowrap;
  overflow: hidden;
  user-select: none;
}
.side-item:hover { background: var(--bg-hover); color: var(--text-primary); }
/* 键盘可达：焦点环走统一令牌（WCAG 2.4.7 / 1.4.11），不再裸 outline */
.side-item:focus-visible,
.side-head:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring-inset, inset 0 0 0 2px var(--accent));
}
/* 当前页：主色浅底 + 主色文字 + 左侧主色指示条 */
.side-item.active {
  background: var(--accent-soft);
  color: var(--accent-text);
  font-weight: 600;
  position: relative;
}
.side-item.active::before {
  content: '';
  position: absolute;
  left: 3px; top: 50%;
  transform: translateY(-50%);
  width: 3px;
  /* 指示条高度固定为行高的一半：换密度档、改行高时自动跟随，不再写死 16px */
  height: calc(var(--side-row-h, 40px) / 2);
  border-radius: 2px;
  background: var(--accent);
}
/* 图标略小于文字：--side-icon = --side-font − 1px（组头图标同理） */
.side-icon { font-size: var(--side-icon, 13px); flex: none; }
/* 名称 + 副标签：名称可省略，副标签不可省略（副标签本身就是消歧信息，截掉等于没写）。
   名称↔副标签的 8px 比图文间距（--side-gap）更紧：副标签是主名的从属信息。 */
.side-text { flex: 1; min-width: 0; display: flex; align-items: center; gap: 8px; overflow: hidden; }
.side-name { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.side-sub {
  flex: none;
  font-style: normal;
  font-size: var(--side-font-sub, 11px);
  font-weight: 400;
  color: var(--text-tertiary);
  white-space: nowrap;
}

/* 置顶项与分组之间加一道细分隔，形成「主工作区 / 功能域」的视觉分层 */
.side-pinned {
  display: flex;
  flex-direction: column;
  gap: var(--side-item-gap, 4px);
  padding-bottom: var(--space-3, 12px);
  margin-bottom: var(--space-3, 12px);
  border-bottom: 1px solid var(--border-l1);
}

/* ===== 分组头 =====
   text-align: left 不能删：<button> 默认居中，会把「世界 / 角色 / 心智」整段文字推到行中间，
   与左对齐的子项错位（旧版就是这个观感）。 */
.side-head {
  display: flex;
  align-items: center;
  gap: var(--side-gap, 10px);
  flex: none;
  width: 100%;
  height: var(--side-row-h, 40px);
  padding: 0 var(--side-pad-x, 10px);
  background: none;
  border: none;
  border-radius: var(--side-radius, 8px);
  color: var(--text-secondary);
  font-family: inherit;              /* <button> 不继承字体：漏写会掉回系统默认中文字体，与行内 HarmonyOS 混排 */
  font-size: var(--side-font-head, 12px);
  font-weight: 600;
  letter-spacing: .02em;
  text-align: left;
  cursor: pointer;
  transition: background .15s var(--dsw-ease), color .15s var(--dsw-ease);
}
.side-head:hover { background: var(--bg-hover); color: var(--text-primary); }
/* 组头图标跟组头字号走（不是跟行字号），否则 12px 的字配 13px 的图标会显得图标更大 */
.side-head .side-icon { font-size: calc(var(--side-font-head, 12px) - 1px); }
.side-caret {
  margin-left: auto; flex: none; width: 6px; height: 6px;
  border-right: 1.6px solid currentColor; border-bottom: 1.6px solid currentColor;
  transform: rotate(45deg); transform-origin: center;
  transition: transform .18s var(--dsw-ease); opacity: .5;
}
.side-head:hover .side-caret { opacity: .85; }
.side-section.open .side-caret { transform: rotate(-135deg); }

/* 子项：一级缩进（--side-indent），与组头形成「组 → 项」的层级，不做二级缩进 */
.side-children {
  display: flex;
  flex-direction: column;
  gap: var(--side-item-gap, 4px);
  margin-top: var(--side-item-gap, 4px);
}
.side-item.child { padding-left: calc(var(--side-pad-x, 10px) + var(--side-indent, 16px)); }
/* 组间比组内更松：组与组之间吃 --side-group-gap，组内只吃 --side-item-gap */
.side-section + .side-section { margin-top: var(--side-group-gap, 18px); }

/* 图标栏形态：图标居中、取消内边距与缩进；图标放大到「没有文字也能认」的尺寸 */
.is-icon-only .side-item,
.is-icon-only .side-head { justify-content: center; padding: 0; gap: 0; }
.is-icon-only .side-icon,
.is-icon-only .side-head .side-icon { font-size: var(--side-icon-rail, 18px); }
.is-icon-only .side-caret { display: none; }
.is-icon-only .side-item.child { padding-left: 0; }

/* 底部插槽：固定在侧栏底部，不随导航滚动 */
.side-foot {
  flex: none;
  padding: var(--space-3, 12px) var(--side-pad-x, 10px) 0;
  border-top: 1px solid var(--border-l1);
}

/* 滚动条样式由上面的隐藏规则统一接管（见 .side-scroll） */
</style>
