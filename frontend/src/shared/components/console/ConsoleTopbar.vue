<template>
  <!-- 顶部全局状态栏（52px，浅灰导航风格；与项目空间顶栏统一，控制台/用户中心/全局设置共用） -->
  <header class="console-topbar">
    <div class="topbar-left">
      <button v-if="showCollapse" class="collapse-btn" @click="$emit('toggle-collapse')">
        <el-icon><HIcon v-if="!collapsed" name="Fold" /><HIcon v-else name="Expand" /></el-icon>
      </button>
      <div class="topbar-brand" @click="goHome">
        <svg width="22" height="22" viewBox="0 0 32 32"><defs><radialGradient id="cl-g2" cx="40%" cy="35%" r="65%"><stop offset="0%" stop-color="#4080FF"/><stop offset="100%" stop-color="#0E42D2"/></radialGradient></defs><circle cx="16" cy="16" r="13" fill="url(#cl-g2)"/><ellipse cx="16" cy="16" rx="13" ry="5" fill="none" stroke="rgba(255,255,255,0.4)" stroke-width="0.8"/></svg>
        <span class="brand-name">Holzyn</span>
        <span class="brand-sep">|</span>
        <span class="brand-label">{{ brandLabel }}</span>
      </div>
    </div>
    <div class="topbar-center" v-if="showSearch">
      <div class="topbar-search">
        <el-icon><HIcon name="Search" /></el-icon>
        <input placeholder="搜索任务、项目、资产、文档、API" />
      </div>
    </div>
    <div class="topbar-right">
      <!-- 预留插槽：额外顶栏链接/按钮 -->
      <slot name="actions"></slot>
      <!-- 主题深浅快速切换（与项目空间顶栏一致） -->
      <button class="topbar-icon-btn" :title="isDark ? '切换到浅色主题' : '切换到深色主题'" @click="toggleMode">
        <el-icon :size="17"><HIcon :name="isDark ? 'Sunny' : 'Moon'" /></el-icon>
      </button>
      <a class="topbar-link">文档中心</a>
      <!-- 设置：触发 open-settings 事件，由宿主页面决定打开「首页设置弹窗」（零路由依赖，保证可用） -->
      <a class="topbar-link" @click="$emit('open-settings')">设置</a>
      <!-- 通知铃铛（P4） -->
      <NotificationBell class="topbar-bell" />
      <!-- 头像下拉快捷面板（P5，替换原硬编码头像） -->
      <AvatarMenu />
    </div>
  </header>
</template>

<script setup>
/**
 * 顶部全局状态栏组件（52px，浅灰导航风格 —— 与项目空间顶栏（--bg-nav）统一）。
 * 职责：控制台与用户中心共用的顶部栏——品牌 Logo、折叠按钮（可选）、搜索框（可选）、
 * 主题深浅切换、通知铃铛与头像下拉快捷面板。通过 props 区分场景，减少多套布局的重复。
 * 主题切换与项目空间顶栏一致（useTheme.toggleMode），保证首页/画廊与项目内观感统一。
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useTheme } from '@/shared/theme'
import NotificationBell from './NotificationBell.vue'
import AvatarMenu from './AvatarMenu.vue'

defineProps({
  /** 品牌标签文案：控制台 / 用户中心 */
  brandLabel: { type: String, default: '控制台' },
  /** 是否显示侧边栏折叠按钮 */
  showCollapse: { type: Boolean, default: true },
  /** 侧边栏折叠状态（配合 toggle-collapse 事件） */
  collapsed: { type: Boolean, default: false },
  /** 是否显示中部搜索框 */
  showSearch: { type: Boolean, default: true }
})
defineEmits(['toggle-collapse', 'open-settings'])

const router = useRouter()
const themeApi = useTheme()
const isDark = computed(() => document.documentElement.hasAttribute('data-theme'))
/** 切换深浅主题（与项目空间顶栏一致） */
function toggleMode() { themeApi.toggleMode() }
/** 点击品牌返回主页（项目总览） */
function goHome() { router.push('/') }
</script>

<style scoped>
/* 顶部全局状态栏（52px，浅灰导航风格 —— 与项目空间顶栏同高/同底色/同控件，随个性化主色极淡晕染） */
.console-topbar {
  height: 52px; background: var(--bg-nav); display: flex; align-items: center; justify-content: space-between;
  padding: 0 20px; flex-shrink: 0; color: var(--text-regular);
  border-bottom: 1px solid var(--border-l1); box-shadow: var(--shadow-lv1);
}
.topbar-left { display: flex; align-items: center; gap: 12px; }
.collapse-btn { background: none; border: none; color: var(--text-secondary); cursor: pointer; padding: 6px; border-radius: 4px; transition: all 0.2s; }
.collapse-btn:hover { background: var(--bg-hover); color: var(--text-primary); }
.topbar-brand { display: flex; align-items: center; gap: 6px; cursor: pointer; }
.brand-name { font-weight: 700; font-size: 1rem; color: var(--text-primary); }
.brand-sep { color: var(--border-l3); }
.brand-label { font-size: 0.85rem; color: var(--text-secondary); }
.topbar-center { flex: 1; max-width: 480px; margin: 0 24px; }
.topbar-search {
  display: flex; align-items: center; gap: 8px; background: var(--bg-layer-2);
  border: 1px solid var(--border-l1); border-radius: 20px; padding: 6px 16px; color: var(--text-placeholder);
}
.topbar-search input { flex: 1; background: none; border: none; outline: none; color: var(--text-primary); font-size: 0.85rem; }
.topbar-search input::placeholder { color: var(--text-placeholder); }
.topbar-right { display: flex; align-items: center; gap: 16px; }
.topbar-link { font-size: 0.82rem; color: var(--text-secondary); cursor: pointer; text-decoration: none; transition: color .15s; }
.topbar-link:hover { color: var(--accent-text); }
/* 深浅切换按钮（与项目空间顶栏一致） */
.topbar-icon-btn {
  width: 30px; height: 30px; display: inline-flex; align-items: center; justify-content: center;
  color: var(--text-secondary); background: transparent; border: 1px solid transparent;
  border-radius: 999px; cursor: pointer; transition: all .15s var(--dsw-ease);
}
.topbar-icon-btn:hover { background: var(--bg-hover); color: var(--text-primary); }
.topbar-bell { cursor: pointer; color: var(--text-regular); }
</style>
