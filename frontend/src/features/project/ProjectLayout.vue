<template>
  <div class="project-layout">
    <!-- 顶部栏：返回画廊 + 项目名/面包屑 + 搜索 + 通知 + 本地账户菜单 -->
    <header class="proj-topbar">
      <div class="topbar-left">
        <el-button class="back-btn" text @click="goGallery">
          <el-icon><HIcon name="ArrowLeft" /></el-icon>&nbsp;返回画廊
        </el-button>
        <span class="topbar-divider"></span>
        <div class="topbar-title">
          <span class="proj-name">{{ project?.name || '项目' }}</span>
          <span v-if="breadcrumb" class="breadcrumb"> / {{ breadcrumb }}</span>
        </div>
      </div>
      <div class="topbar-right">
        <!-- 项目内全局搜索（占位：跨项目/角色/记忆/知识搜索为待开发项） -->
        <div class="topbar-search">
          <el-icon><HIcon name="Search" /></el-icon>
          <input placeholder="站内搜索（待开发）" disabled />
        </div>
        <!-- 主题深浅快速切换 -->
        <button class="topbar-icon-btn" :title="isDark ? '切换到浅色主题' : '切换到深色主题'" @click="toggleMode">
          <el-icon :size="17"><HIcon :name="isDark ? 'Sunny' : 'Moon'" /></el-icon>
        </button>
        <!-- 通知铃铛（占位） -->
        <NotificationBell class="topbar-bell" />
        <!-- 本地账户菜单 -->
        <AvatarMenu />
      </div>
    </header>

    <div class="proj-body">
      <!-- ===== 悬浮岛式左侧侧边栏（圆角卡片、离边留白、柔和阴影、多级导航） ===== -->
      <aside class="proj-sidebar">
        <nav class="sidebar-nav">
          <!-- 一级菜单：平铺（总览 / 互动） -->
          <template v-for="item in primaryItems" :key="item.key">
            <router-link :to="item.path" class="nav-item" :class="{ active: isActive(item.path) }" :title="item.name">
              <el-icon class="nav-icon"><HIcon :name="item.icon" /></el-icon>
              <span class="nav-text">{{ item.name }}</span>
            </router-link>
          </template>

          <!-- 可展开分组：世界观 / 角色 / 知识（子级缩进、当前页高亮、默认收起） -->
          <div v-for="group in navGroups" :key="group.key" class="nav-group" :class="{ open: openGroups[group.key] }">
            <div
              class="nav-item group-head"
              :class="{ active: isGroupActive(group.children) }"
              @click="toggleGroup(group.key)"
            >
              <el-icon class="nav-icon"><HIcon :name="group.icon" /></el-icon>
              <span class="nav-text">{{ group.name }}</span>
              <el-icon class="nav-arrow" :class="{ rotated: openGroups[group.key] }"><HIcon name="ArrowDown" /></el-icon>
            </div>
            <!-- 子级：缩进显示，高亮当前页 -->
            <transition name="expand">
              <div v-if="openGroups[group.key]" class="nav-children">
                <router-link
                  v-for="child in group.children"
                  :key="child.path"
                  :to="child.path"
                  class="nav-item nav-child"
                  :class="{ active: isActive(child.path) }"
                >
                  <span class="child-dot"></span>
                  <span class="nav-text">{{ child.name }}</span>
                </router-link>
              </div>
            </transition>
          </div>

          <!-- 设置：直接点击进入通用设置页（不再展开四个子页；子页经设置页左侧导航进入） -->
          <router-link :to="settingsPath" class="nav-item nav-settings" :class="{ active: isSettingsActive }" title="设置 · 通用设置">
            <el-icon class="nav-icon"><HIcon name="Setting" /></el-icon>
            <span class="nav-text">设置</span>
          </router-link>
        </nav>

        <!-- 底部：项目状态小卡 -->
        <div class="sidebar-foot">
          <el-tag size="small" :type="statusType(project?.status)" effect="plain">
            {{ statusText(project?.status) }}
          </el-tag>
          <span v-if="project?.characterCount != null" class="foot-count">{{ project.characterCount }} 角色</span>
        </div>
      </aside>

      <!-- 主内容区：渲染当前导航页 -->
      <main class="proj-content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
/**
 * 项目空间骨架（模块化重构 V1.0，悬浮岛式侧边栏）。
 * <p>职责：项目空间所有页面共享的外壳——左侧悬浮岛侧边栏（圆角卡片、离边留白、柔和阴影；
 * 一级平铺项：总览/互动；可展开分组：世界观/角色/知识，子级缩进、当前页高亮，默认收起；
 * 「设置」为直入项（点击进入通用设置页，四个子页经设置页左侧导航进入））+
 * 顶部栏（返回画廊/项目名/面包屑/搜索/通知/本地账户菜单 + 深浅主题快速切换）+ 主内容区渲染当前导航页。</p>
 * <p>页面范围：项目仪表盘（一级）；世界观组=世界详情/世界演化/时间线；角色组=NPC 角色/普通人群/关系拓扑；
 * 互动=对话（单聊/群聊）；知识组=知识库；设置=直入通用设置页（/settings/general）。
 * 历史隐藏页（普通人群/世界演化/时间线/知识库）已于 2026-08-18 恢复侧边栏入口（路由早已保留）。</p>
 * <p>数据来源：/api/projects/{id}（项目详情）。</p>
 */
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchProject, fetchWorldSetting } from '@/shared/api'
import { useTheme } from '@/shared/theme'
import NotificationBell from '@/shared/components/console/NotificationBell.vue'
import AvatarMenu from '@/shared/components/console/AvatarMenu.vue'

const route = useRoute()
const router = useRouter()
const themeApi = useTheme()
const isDark = computed(() => document.documentElement.hasAttribute('data-theme'))
const projectId = computed(() => Number(route.params.id))
const project = ref(null)

/** 各可展开分组展开状态（默认全部收起：2026-08-19 调整，侧边栏更紧凑，点分组标题展开） */
const openGroups = reactive({ world: false, role: false, knowledge: false })

/** 设置直入项路径：点击直接进入通用设置页（四个子页经设置页左侧导航进入） */
const settingsPath = computed(() => `/project/${projectId.value}/settings/general`)
/** 设置项激活判断：/settings 前缀下的任意子页都高亮设置入口 */
const isSettingsActive = computed(() => route.path.startsWith(`/project/${projectId.value}/settings`))

/** 一级平铺导航（总览 / 互动） */
const primaryItems = computed(() => [
  { key: 'dashboard', name: '总览 · 项目仪表盘', icon: 'Odometer', path: `/project/${projectId.value}/dashboard` },
  { key: 'chat', name: '互动 · 对话', icon: 'ChatDotRound', path: `/project/${projectId.value}/chat` }
])

/** 可展开分组导航（世界观 / 角色 / 知识），子级缩进显示、当前页高亮；「设置」为直入项（见 settingsPath） */
const navGroups = computed(() => [
  {
    key: 'world', name: '世界观', icon: 'Earth',
    children: [
      { name: '世界详情', path: `/project/${projectId.value}/world` },
      { name: '世界演化', path: `/project/${projectId.value}/evolve` },
      { name: '时间线', path: `/project/${projectId.value}/timeline` }
    ]
  },
  {
    key: 'role', name: '角色', icon: 'User',
    children: [
      { name: 'NPC 角色', path: `/project/${projectId.value}/characters` },
      { name: '普通人群', path: `/project/${projectId.value}/crowds` },
      { name: '关系拓扑', path: `/project/${projectId.value}/topology` }
    ]
  },
  {
    key: 'knowledge', name: '知识', icon: 'Reading',
    children: [
      { name: '知识库', path: `/project/${projectId.value}/knowledge` }
    ]
  }
])

/** 切换深浅主题（顶栏按钮；此前缺失该函数导致点击无效，与 ConsoleTopbar 保持一致） */
function toggleMode() { themeApi.toggleMode() }

/** 切换分组展开/收起 */
function toggleGroup(key) {
  openGroups[key] = !openGroups[key]
}

/** 当前面包屑（由路由 meta 标题去掉 · HolzynActor 后缀） */
const breadcrumb = computed(() => {
  const t = route.meta?.title || ''
  return t.replace(' · HolzynActor', '')
})

/** 项目状态文案（0草稿/1已生成角色卡/2进行中） */
function statusText(s) {
  return s === 1 ? '已生成角色卡' : (s === 2 ? '进行中' : '草稿')
}

/** 项目状态标签颜色 */
function statusType(s) {
  return s === 1 ? 'success' : (s === 2 ? 'primary' : 'info')
}

/** 分组是否激活（任一子路径命中） */
function isGroupActive(items) {
  return items.some(c => isActive(c.path))
}

/** 路径激活判断（含子路径前缀匹配，兼容 settings 嵌套路由） */
function isActive(path) {
  return route.path === path || route.path.startsWith(path + '/')
}

/** 返回项目画廊 */
function goGallery() { router.push('/') }

/** 加载项目详情 + 世界题材（用于主题联动换肤） */
async function loadProject() {
  try { project.value = await fetchProject(projectId.value) } catch (_) { project.value = null }
  let genre = null
  try {
    const world = await fetchWorldSetting(projectId.value)
    genre = world?.genre || null
  } catch (_) { /* 无世界观时用默认主色 */ }
  themeApi.setProjectContext(projectId.value, genre)
}

// 项目切换时：重新加载项目详情（导航路径由 computed 自动跟随 projectId）
watch(projectId, () => { loadProject() })

onMounted(loadProject)
onBeforeUnmount(() => themeApi.clearProjectContext())
</script>

<style scoped>
.project-layout { height: 100vh; display: flex; flex-direction: column; overflow: hidden; background: var(--bg-page, #eef1f6); }

/* ===== 顶部栏（52px，导航灰：与内容区明确分层，随个性化主色极淡晕染） ===== */
.proj-topbar {
  height: 52px; background: var(--bg-nav); display: flex; align-items: center; justify-content: space-between;
  padding: 0 20px; flex-shrink: 0; box-shadow: var(--shadow-lv1); z-index: 5;
  border-bottom: 1px solid var(--border-l1);
}
.topbar-left { display: flex; align-items: center; gap: 12px; min-width: 0; }
.back-btn { color: var(--text-regular); }
.back-btn:hover { color: var(--accent-text); background: var(--accent-soft); }
.topbar-divider { width: 1px; height: 20px; background: var(--border-l2); }
.topbar-title { display: flex; align-items: center; gap: 6px; min-width: 0; white-space: nowrap; }
.proj-name { font-weight: 700; font-size: 0.95rem; color: var(--text-primary); overflow: hidden; text-overflow: ellipsis; max-width: 320px; }
.breadcrumb { font-size: 0.82rem; color: var(--text-secondary); }
.topbar-right { display: flex; align-items: center; gap: 14px; }
.topbar-search { display: flex; align-items: center; gap: 8px; background: var(--bg-layer-2); border-radius: 20px; padding: 6px 16px; color: var(--text-placeholder); width: 240px; border: 1px solid var(--border-l1); }
.topbar-search input { flex: 1; background: none; border: none; outline: none; color: var(--text-primary); font-size: 0.85rem; }
.topbar-search input::placeholder { color: var(--text-placeholder); }
.topbar-search input:disabled { cursor: not-allowed; }
.topbar-icon-btn {
  width: 30px; height: 30px; display: inline-flex; align-items: center; justify-content: center;
  color: var(--text-secondary); background: transparent; border: 1px solid transparent;
  border-radius: 999px; cursor: pointer; transition: all .15s var(--dsw-ease);
}
.topbar-icon-btn:hover { background: var(--bg-hover); color: var(--text-primary); }
.topbar-bell { cursor: pointer; color: var(--text-regular); }

/* ===== 主体 ===== */
.proj-body { flex: 1; display: flex; overflow: hidden; }

/* ===== 悬浮岛式侧边栏（导航灰 / 圆角卡片 / 离边留白 / 柔和阴影 / 多级导航） ===== */
.proj-sidebar {
  /* 离边留白：左右上下留出安全间距 */
  width: 248px; margin: 16px 0 16px 16px; padding: 14px 10px;
  /* 圆角卡片 + 柔和阴影（导航灰背景与内容区明确分层，随深浅主题/个性化主色） */
  background: var(--bg-nav); border-radius: 18px;
  box-shadow: var(--shadow-lv2);
  border: 1px solid var(--border-l1);
  display: flex; flex-direction: column; justify-content: space-between;
  flex-shrink: 0; overflow-y: auto;
  /* 悬浮层叠：卡片高于背景 */
  position: relative; z-index: 3;
}
.sidebar-nav { flex: 1; display: flex; flex-direction: column; gap: 2px; }

/* 一级导航项 */
.nav-item {
  display: flex; align-items: center; gap: 10px; padding: 11px 14px; border-radius: 12px;
  color: var(--text-regular); cursor: pointer; transition: all 0.18s;
  font-size: 0.9rem; white-space: nowrap; text-decoration: none; position: relative;
}
.nav-item:hover { background: var(--bg-hover); color: var(--text-primary); }
.nav-item.active {
  background: linear-gradient(135deg, var(--accent-soft), color-mix(in srgb, var(--accent) 8%, transparent));
  color: var(--accent-text); font-weight: 600;
  box-shadow: inset 0 0 0 1px var(--accent-border);
}
/* 当前页高亮：左侧竖条指示 */
.nav-item.active::before {
  content: ''; position: absolute; left: 0; top: 20%; bottom: 20%; width: 3px;
  border-radius: 2px; background: var(--brand-primary, #1d5cff);
}
.nav-icon { font-size: 1.1rem; }

/* 设置父级 */
.nav-group { margin-top: 4px; }
.group-head { border-top: 1px solid var(--border-light, var(--border-l1)); border-radius: 0 0 0 0; margin-top: 6px; padding-top: 12px; }
.nav-arrow { transition: transform 0.2s; opacity: 0.45; margin-left: auto; }
.nav-arrow.rotated { transform: rotate(180deg); }

/* 设置直入项（顶部分隔线与分组对齐） */
.nav-settings { border-top: 1px solid var(--border-light, var(--border-l1)); margin-top: 6px; padding-top: 12px; }

/* 子级：缩进显示 */
.nav-children { padding-left: 14px; display: flex; flex-direction: column; gap: 2px; }
.nav-child { padding: 9px 12px 9px 18px; font-size: 0.85rem; }
.child-dot { width: 5px; height: 5px; border-radius: 50%; background: var(--border-strong, #cbd5e1); flex-shrink: 0; }
.nav-child.active .child-dot { background: var(--brand-primary, #1d5cff); }
.nav-child.active::before { left: 6px; top: 22%; bottom: 22%; }

/* 展开动画 */
.expand-enter-active, .expand-leave-active { transition: opacity 0.18s, transform 0.18s; }
.expand-enter-from, .expand-leave-to { opacity: 0; transform: translateY(-4px); }

/* 底部项目信息小卡 */
.sidebar-foot {
  padding: 12px 14px 4px; border-top: 1px solid var(--border-light, var(--border-l1)); margin-top: 10px;
  display: flex; align-items: center; gap: 8px;
}
.foot-count { font-size: 0.75rem; color: var(--text-secondary); }

/* ===== 主内容区 ===== */
.proj-content { flex: 1; background: transparent; overflow-y: auto; padding: 20px 24px; }

/* 滚动条 */
.proj-sidebar::-webkit-scrollbar, .proj-content::-webkit-scrollbar { width: 6px; }
.proj-sidebar::-webkit-scrollbar-thumb { background: rgba(120, 140, 180, 0.18); border-radius: 3px; }
.proj-content::-webkit-scrollbar-thumb { background: var(--scrollbar-thumb, #cdd5e2); border-radius: 3px; }
</style>
